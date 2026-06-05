package vip.mate.tool.mcp.runtime;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Enhanced stdio MCP transport with:
 * <ul>
 *   <li>Working directory (cwd) support</li>
 *   <li>Automatic PATH enrichment for Desktop app environments where
 *       Node.js/npx may not be in the JRE process's PATH</li>
 *   <li>Resilient inbound processing: non-JSON lines from the server
 *       (e.g. debug output) are skipped instead of killing the
 *       inbound reader thread</li>
 * </ul>
 */
@Slf4j
public class CwdAwareStdioClientTransport extends StdioClientTransport {

    private final String cwd;

    /** Common Node.js installation paths across platforms */
    private static final String[] NODE_PATH_CANDIDATES = {
        // macOS Homebrew
        "/usr/local/bin",
        "/opt/homebrew/bin",
        // macOS nvm
        System.getProperty("user.home") + "/.nvm/current/bin",
        // Linux common
        "/usr/bin",
        "/usr/local/bin",
        // Linux nvm
        System.getProperty("user.home") + "/.nvm/current/bin",
        // Windows common
        System.getenv("APPDATA") != null ? System.getenv("APPDATA") + "\\npm" : "",
        "C:\\Program Files\\nodejs",
        // pnpm global
        System.getProperty("user.home") + "/.local/share/pnpm",
        System.getProperty("user.home") + "/Library/pnpm",
        // Volta
        System.getProperty("user.home") + "/.volta/bin",
        // fnm
        System.getProperty("user.home") + "/.fnm/current/bin",
    };

    public CwdAwareStdioClientTransport(ServerParameters params, McpJsonMapper jsonMapper, String cwd) {
        super(params, jsonMapper);
        this.cwd = cwd;
    }

    /**
     * Override parent {@code connect()} to add resilient inbound processing.
     *
     * <p>The upstream {@link StdioClientTransport} breaks out of its inbound
     * read loop on any JSON parse error, killing the reader thread permanently.
     * Some MCP servers write non-JSON debug output to stdout (e.g.
     * {@code "=== Document parser messages ==="}), which triggers this and
     * causes subsequent valid JSON-RPC responses to be lost → 30 s timeout.
     *
     * <p>This override replaces the inbound processing with a version that
     * {@link Log#debug logs} and {@code continue}s past non-JSON lines
     * instead of breaking. Outbound and error processing remain unchanged.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.<Void>fromRunnable(() -> {
            log.info("MCP server starting (resilient mode).");

            // Wire up the parent's inbound + error sinks so downstream
            // McpSyncClient message dispatch works unchanged.
            Sinks.Many<McpSchema.JSONRPCMessage> inboundSink = getPrivateField("inboundSink");
            Sinks.Many<String> errorSink = getPrivateField("errorSink");

            if (inboundSink != null) {
                inboundSink.asFlux()
                        .flatMap(msg -> Mono.just(msg).transform(handler))
                        .subscribe();
            }
            if (errorSink != null) {
                errorSink.asFlux().subscribe(line -> log.info("MCP stdio stderr: {}", line));
            }

            // Build and start the server process (reuses parent's ProcessBuilder hook)
            List<String> fullCommand = new ArrayList<>();
            fullCommand.add(params().getCommand());
            fullCommand.addAll(params().getArgs());

            ProcessBuilder processBuilder = getProcessBuilder();
            processBuilder.command(fullCommand);
            processBuilder.environment().putAll(params().getEnv());

            Process process;
            try {
                process = processBuilder.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start MCP process: " + fullCommand, e);
            }

            if (process.getInputStream() == null || process.getOutputStream() == null) {
                process.destroy();
                throw new RuntimeException("MCP process input or output stream is null");
            }

            // --- Resilient inbound reader (the key fix) ---
            Thread inboundThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        try {
                            McpSchema.JSONRPCMessage message =
                                    McpSchema.deserializeJsonRpcMessage(jsonMapper(), line);
                            if (inboundSink != null && !inboundSink.tryEmitNext(message).isSuccess()) {
                                log.error("Failed to enqueue inbound MCP message");
                                break;
                            }
                        } catch (Exception e) {
                            // Non-JSON line from server (debug output, etc.) — skip it.
                            log.debug("MCP server stdout non-JSON line (skipped): {}", line);
                        }
                    }
                } catch (Exception e) {
                    log.error("MCP inbound reader error", e);
                } finally {
                    if (inboundSink != null) inboundSink.tryEmitComplete();
                    if (errorSink != null) errorSink.tryEmitComplete();
                }
            }, "mcp-inbound-resilient");
            inboundThread.setDaemon(true);
            inboundThread.start();

            // Outbound writer — delegate to parent infrastructure
            startOutboundFromConnect(process);

            // Stderr reader
            Thread errThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        if (errorSink != null) errorSink.tryEmitNext(line);
                    }
                } catch (Exception e) {
                    log.error("MCP stderr reader error", e);
                }
            }, "mcp-stderr");
            errThread.setDaemon(true);
            errThread.start();

            log.info("MCP server started (resilient mode).");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Read a private field from the parent {@link StdioClientTransport}. */
    @SuppressWarnings("unchecked")
    private <T> T getPrivateField(String name) {
        try {
            Field field = StdioClientTransport.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(this);
        } catch (Exception e) {
            log.debug("Cannot access parent field {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** Accessor for the jsonMapper (needed by the resilient inbound reader). */
    private io.modelcontextprotocol.json.McpJsonMapper jsonMapper() {
        try {
            Field f = StdioClientTransport.class.getDeclaredField("jsonMapper");
            f.setAccessible(true);
            return (io.modelcontextprotocol.json.McpJsonMapper) f.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access jsonMapper", e);
        }
    }

    /** Accessor for the ServerParameters (needed by connect). */
    private io.modelcontextprotocol.client.transport.ServerParameters params() {
        try {
            Field f = StdioClientTransport.class.getDeclaredField("params");
            f.setAccessible(true);
            return (io.modelcontextprotocol.client.transport.ServerParameters) f.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access params", e);
        }
    }

    /**
     * Wire up outbound message writing from the parent's outboundSink.
     * Reads from the sink's flux and writes JSON to the process stdout.
     */
    private void startOutboundFromConnect(Process process) {
        try {
            Field f = StdioClientTransport.class.getDeclaredField("outboundSink");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Sinks.Many<McpSchema.JSONRPCMessage> outboundSink =
                    (Sinks.Many<McpSchema.JSONRPCMessage>) f.get(this);
            if (outboundSink == null) return;

            Thread outThread = new Thread(() -> {
                try (var writer = new java.io.BufferedWriter(
                        new java.io.OutputStreamWriter(process.getOutputStream()))) {
                    outboundSink.asFlux().subscribe(message -> {
                        try {
                            String json = jsonMapper().writeValueAsString(message);
                            json = json.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
                            writer.write(json);
                            writer.newLine();
                            writer.flush();
                        } catch (Exception e) {
                            log.error("MCP outbound write error", e);
                        }
                    });
                    // Keep thread alive while process runs
                    process.waitFor();
                } catch (Exception e) {
                    log.error("MCP outbound writer error", e);
                }
            }, "mcp-outbound-resilient");
            outThread.setDaemon(true);
            outThread.start();
        } catch (Exception e) {
            log.warn("Cannot wire outbound sink: {}", e.getMessage());
        }
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        ProcessBuilder builder = super.getProcessBuilder();
        if (cwd != null && !cwd.isBlank()) {
            builder.directory(new File(cwd));
        }
        enrichPath(builder);
        return builder;
    }

    /**
     * Enrich the process PATH with common Node.js installation directories.
     * Desktop apps (Electron/JRE) often don't inherit the user's shell PATH,
     * causing "npx: command not found" errors.
     */
    private void enrichPath(ProcessBuilder builder) {
        Map<String, String> env = builder.environment();
        String currentPath = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        StringBuilder enriched = new StringBuilder(currentPath);

        for (String candidate : NODE_PATH_CANDIDATES) {
            if (candidate == null || candidate.isEmpty()) continue;
            if (currentPath.contains(candidate)) continue;
            if (Files.isDirectory(Path.of(candidate))) {
                enriched.append(File.pathSeparator).append(candidate);
            }
        }

        // Also try to resolve nvm's actual current version directory
        String nvmDir = System.getenv("NVM_DIR");
        if (nvmDir == null) nvmDir = System.getProperty("user.home") + "/.nvm";
        Path nvmDefault = Path.of(nvmDir, "versions", "node");
        if (Files.isDirectory(nvmDefault)) {
            try (var stream = Files.list(nvmDefault)) {
                stream.filter(Files::isDirectory)
                      .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                      .findFirst()
                      .ifPresent(nodeDir -> {
                          String binPath = nodeDir.resolve("bin").toString();
                          if (!currentPath.contains(binPath)) {
                              enriched.append(File.pathSeparator).append(binPath);
                          }
                      });
            } catch (Exception ignored) {}
        }

        String finalPath = enriched.toString();
        env.put("PATH", finalPath);
        // Windows uses "Path" key
        if (env.containsKey("Path")) {
            env.put("Path", finalPath);
        }

        if (!finalPath.equals(currentPath)) {
            log.debug("[MCP] Enriched PATH for subprocess: added {} entries",
                      finalPath.split(File.pathSeparator).length - currentPath.split(File.pathSeparator).length);
        }
    }
}
