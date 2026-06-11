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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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

    /** The child process started by this override's {@link #connect}, retained so
     *  {@link #closeGracefully()} can terminate it — the parent's private
     *  {@code process} field is never set by our override and so the parent's
     *  shutdown would otherwise orphan the child. */
    private volatile Process startedProcess;

    /** Cooperative-shutdown flag checked by the reader loops, mirroring the
     *  parent's own {@code isClosing} signal (which our override bypasses). */
    private volatile boolean closing = false;

    /** Invoked when the child process exits while {@link #closing} is still
     *  {@code false} — i.e. the server died on its own rather than being torn
     *  down by {@link #closeGracefully()}. Lets the manager request a reconnect.
     *  Null until armed by the manager for a long-lived (non-test) client. */
    private volatile Runnable onUnexpectedExit;

    /** I/O threads spawned by {@link #connect}, interrupted on shutdown. */
    private final List<Thread> ioThreads = new CopyOnWriteArrayList<>();

    /** Common Node.js installation paths across platforms */
    private static final String[] NODE_PATH_CANDIDATES = {
        // macOS Homebrew
        "/usr/local/bin",
        "/opt/homebrew/bin",
        // nvm (current symlink)
        System.getProperty("user.home") + "/.nvm/current/bin",
        // Linux common
        "/usr/bin",
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
     * Arm a callback fired when the child process exits unexpectedly (not via
     * {@link #closeGracefully()}). The manager uses this to request an
     * asynchronous reconnect — stdio is the one transport the MCP SDK cannot
     * self-heal, because a dead subprocess can only be recovered by respawning
     * it, which the SDK's lazy re-initialization never does.
     */
    public void setOnUnexpectedExit(Runnable handler) {
        this.onUnexpectedExit = handler;
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
            // McpSyncClient message dispatch works unchanged. These are required:
            // if the SDK ever renames them, fail loudly here rather than connecting
            // a transport whose every call silently times out at 30 s.
            Sinks.Many<McpSchema.JSONRPCMessage> inboundSink = getRequiredPrivateField("inboundSink");
            Sinks.Many<String> errorSink = getRequiredPrivateField("errorSink");

            inboundSink.asFlux()
                    .flatMap(msg -> Mono.just(msg).transform(handler))
                    .subscribe();
            errorSink.asFlux().subscribe(line -> log.info("MCP stdio stderr: {}", line));

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
            // Retain the child so closeGracefully() can terminate it.
            this.startedProcess = process;

            // Detect an unexpected death (crash / external `kill` / the user
            // restarting the MCP service). Suppressed when `closing` is set,
            // which is how an intentional close/replace tears the process down.
            process.onExit().thenAccept(p -> {
                if (closing) {
                    return;
                }
                Runnable exitHandler = this.onUnexpectedExit;
                log.warn("MCP stdio process exited unexpectedly (exit code {})", p.exitValue());
                if (exitHandler != null) {
                    try {
                        exitHandler.run();
                    } catch (Exception e) {
                        log.warn("MCP stdio unexpected-exit handler failed: {}", e.getMessage());
                    }
                }
            });

            // --- Resilient inbound reader (the key fix) ---
            Thread inboundThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!closing && (line = reader.readLine()) != null) {
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
            ioThreads.add(inboundThread);
            inboundThread.start();

            // Outbound writer — delegate to parent infrastructure
            startOutboundFromConnect(process);

            // Stderr reader
            Thread errThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!closing && (line = errReader.readLine()) != null) {
                        if (errorSink != null) errorSink.tryEmitNext(line);
                    }
                } catch (Exception e) {
                    log.error("MCP stderr reader error", e);
                }
            }, "mcp-stderr");
            errThread.setDaemon(true);
            ioThreads.add(errThread);
            errThread.start();

            log.info("MCP server started (resilient mode).");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Read a required private field from the parent {@link StdioClientTransport},
     * failing loudly if it is missing or null. These fields are load-bearing for
     * the resilient override; a silent null would yield a transport that connects
     * but times out on every call, which is undebuggable at default log level.
     */
    @SuppressWarnings("unchecked")
    private <T> T getRequiredPrivateField(String name) {
        try {
            Field field = StdioClientTransport.class.getDeclaredField(name);
            field.setAccessible(true);
            T value = (T) field.get(this);
            if (value == null) {
                throw new IllegalStateException("MCP SDK incompatible: field '" + name
                        + "' on StdioClientTransport is null; CwdAwareStdioClientTransport"
                        + " needs updating for this SDK version");
            }
            return value;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("MCP SDK incompatible: cannot access field '" + name
                    + "' on StdioClientTransport; CwdAwareStdioClientTransport needs"
                    + " updating for this SDK version", e);
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
                        new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
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
            ioThreads.add(outThread);
            outThread.start();
        } catch (Exception e) {
            log.warn("Cannot wire outbound sink: {}", e.getMessage());
        }
    }

    /**
     * Terminate the child process and the I/O threads this override spawned.
     *
     * <p>The parent's {@code closeGracefully()} only acts on its private
     * {@code process} field, which our {@link #connect} never assigns — so the
     * parent alone would log "Process not started" and leave the child running,
     * its readers blocked on {@code readLine()} and the outbound thread parked on
     * {@code waitFor()}. Here we destroy the process we actually started, then
     * delegate to the parent for any remaining sink/scheduler cleanup.
     */
    @Override
    public Mono<Void> closeGracefully() {
        closing = true;
        return Mono.<Void>fromRunnable(() -> {
            Process p = this.startedProcess;
            if (p != null && p.isAlive()) {
                p.destroy();
                try {
                    if (!p.waitFor(2, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                }
            }
            for (Thread t : ioThreads) {
                t.interrupt();
            }
            ioThreads.clear();
        }).subscribeOn(Schedulers.boundedElastic())
          .then(Mono.defer(super::closeGracefully));
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
