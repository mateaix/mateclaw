package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.runtime.RuntimeEventProjector;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.AgentRuntimeProvider;
import vip.mate.agent.runtime.contract.RuntimeCapabilities;
import vip.mate.agent.runtime.contract.RuntimeContextUsage;
import vip.mate.agent.runtime.contract.RuntimeValidation;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfigService;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfiguration;
import vip.mate.agent.AgentService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter for the official DeepSeek Harness SDK JSON-RPC runtime.
 *
 * <p>The runtime is intentionally an external process. This keeps the Node
 * plugin graph out of the Spring classpath and lets deployments pin the DSH
 * runtime independently from MateClaw.</p>
 */
@Service
@Slf4j
public class DshRuntimeService implements AgentRuntimeProvider {
    private final ObjectMapper objectMapper;
    private final ModelConfigService modelConfigService;
    private final ModelProviderService modelProviderService;
    private final DshRuntimeConfigService runtimeConfigService;

    public DshRuntimeService(
            ObjectMapper objectMapper,
            ModelConfigService modelConfigService,
            ModelProviderService modelProviderService,
            DshRuntimeConfigService runtimeConfigService) {
        this.objectMapper = objectMapper;
        this.modelConfigService = modelConfigService;
        this.modelProviderService = modelProviderService;
        this.runtimeConfigService = runtimeConfigService;
        DshRuntimeConfiguration configuration = runtimeConfig();
        log.info("[DSH] runtime configured: command={}, cordisConfig={}", configuration.executablePath(),
                configuration.cordisConfigPath().isBlank() ? "<empty>" : configuration.cordisConfigPath());
    }

    private DshRuntimeConfiguration runtimeConfig() {
        DshRuntimeConfiguration raw = runtimeConfigService.resolve();
        String command = raw.executablePath();
        if (command == null || command.isBlank()) command = "dsh-jsonrpc-agent";
        String cordis = resolveCordisConfig(raw.cordisConfigPath());
        String cwd = raw.workingDirectory();
        if (cwd == null || cwd.isBlank()) cwd = System.getProperty("user.dir");
        return new DshRuntimeConfiguration(command, cordis, cwd, raw.baseUrl(), raw.modelName(), raw.apiKey());
    }

    private String resolveCordisConfig(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) return "";
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) return path.toString();
        // The documented source checkout path points at the package directory;
        // the checked-in composition lives below its runtime subdirectory.
        Path packageDirectory = Files.isDirectory(path) ? path : path.getParent();
        Path packagedConfig = packageDirectory == null
                ? path
                : packageDirectory.resolve("runtime").resolve("cordis.yml");
        return Files.isRegularFile(packagedConfig) ? packagedConfig.toString() : path.toString();
    }

    @Override
    public String type() {
        return "dsh";
    }

    @Override
    public RuntimeValidation validate(RuntimeSession session) {
        DshRuntimeConfiguration configuration = runtimeConfig();
        if (session == null || session.workspaceId() == null) {
            return RuntimeValidation.invalid("dsh.workspace_required", "DSH runtime requires a workspace");
        }
        if (session.workingDirectory() == null || !Files.isDirectory(session.workingDirectory())) {
            return RuntimeValidation.invalid("dsh.working_directory_unavailable", "DSH working directory is unavailable");
        }
        if (configuration.executablePath().isBlank()) {
            return RuntimeValidation.invalid("dsh.command_missing", "DSH runtime command is not configured");
        }
        Path executable = Path.of(commandLine(configuration.executablePath()).get(0));
        if (!executable.isAbsolute() || !Files.isExecutable(executable)) {
            return RuntimeValidation.invalid("dsh.command_unavailable", "DSH runtime command is not executable");
        }
        if (!configuration.cordisConfigPath().isBlank() && !Files.isRegularFile(Path.of(configuration.cordisConfigPath()))) {
            return RuntimeValidation.invalid("dsh.cordis_missing", "DSH Cordis configuration is unavailable");
        }
        return RuntimeValidation.success();
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(true, false, true, true);
    }

    public Map<String, Object> diagnostics() {
        DshRuntimeConfiguration configuration = runtimeConfig();
        Path executable = configuration.executablePath().isBlank() ? null : Path.of(commandLine(configuration.executablePath()).get(0));
        return Map.of(
                "type", type(),
                "commandConfigured", !configuration.executablePath().isBlank(),
                "command", configuration.executablePath(),
                "executable", executable == null ? "" : executable.toString(),
                "executableAvailable", executable != null && Files.isExecutable(executable),
                "cordisConfig", configuration.cordisConfigPath(),
                "cordisConfigAvailable", !configuration.cordisConfigPath().isBlank() && Files.isRegularFile(Path.of(configuration.cordisConfigPath())),
                "workingDirectory", configuration.workingDirectory(),
                "apiKeyConfigured", configuration.apiKey() != null && !configuration.apiKey().isBlank(),
                "capabilities", Map.of(
                        "cancellation", true,
                        "approvals", false,
                        "subagents", true,
                        "contextUsage", true));
    }

    public void validateAgentConfiguration(AgentEntity agent) {
        if (agent == null || agent.getWorkspaceId() == null) {
            throw new IllegalArgumentException("dsh.workspace_required: DSH runtime requires a workspace");
        }
        if (agent.getRuntimeConfig() != null && !agent.getRuntimeConfig().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(agent.getRuntimeConfig());
                if (node == null || !node.isObject()) throw new IllegalArgumentException();
            } catch (Exception error) {
                throw new IllegalArgumentException("dsh.runtime_config_invalid: runtime config must be a JSON object", error);
            }
        }
    }

    @Override
    public AgentRuntimeConnection start(RuntimeSession session) {
        RuntimeValidation validation = validate(session);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.code() + ": " + validation.message());
        }
        AgentEntity agent = new AgentEntity();
        agent.setId(session.agentId());
        agent.setWorkspaceId(session.workspaceId());
        agent.setModelName(session.modelName());
        AtomicReference<Process> activeProcess = new AtomicReference<>();
        AtomicReference<RuntimeContextUsage> latestUsage = new AtomicReference<>(
                new RuntimeContextUsage(0, 0, 0));
        return new AgentRuntimeConnection() {
            @Override
            public Flux<RuntimeEvent> prompt(String message) {
                return stream(agent, message, session.conversationId(), session.modelName(),
                        session.workingDirectory(), activeProcess, latestUsage)
                        .map(DshRuntimeService.this::toRuntimeEvent);
            }

            @Override
            public reactor.core.publisher.Mono<Void> cancel() {
                return reactor.core.publisher.Mono.fromRunnable(
                        () -> cancelProcess(activeProcess.get()));
            }

            @Override
            public reactor.core.publisher.Mono<RuntimeContextUsage> contextUsage() {
                return reactor.core.publisher.Mono.just(latestUsage.get());
            }
        };
    }

    private RuntimeEvent toRuntimeEvent(AgentService.StreamDelta delta) {
        if (delta == null) return RuntimeEvent.of("dsh", 0, RuntimeEventType.RUNTIME_READY, null, Map.of());
        if (delta.content() != null) {
            return RuntimeEvent.of("dsh", 0, RuntimeEventType.ASSISTANT_DELTA, delta.content(), Map.of());
        }
        if (delta.thinking() != null) {
            return RuntimeEvent.of("dsh", 0, RuntimeEventType.THINKING_DELTA, delta.thinking(), Map.of());
        }
        RuntimeEventType type = switch (delta.eventType() == null ? "" : delta.eventType()) {
            case "done" -> RuntimeEventType.COMPLETED;
            case "error" -> RuntimeEventType.FAILED;
            case "cancelled" -> RuntimeEventType.CANCELLED;
            case "tool_call_started" -> RuntimeEventType.TOOL_STARTED;
            case "tool_call_completed" -> RuntimeEventType.TOOL_FINISHED;
            case "tool_approval_requested" -> RuntimeEventType.TOOL_APPROVAL_REQUIRED;
            default -> RuntimeEventType.RUNTIME_READY;
        };
        return type.terminal()
                ? RuntimeEvent.terminal("dsh", 0, type, delta.eventData())
                : RuntimeEvent.of("dsh", 0, type, null, delta.eventData());
    }

    public Flux<AgentService.StreamDelta> stream(AgentEntity agent, String message,
                                                   String conversationId, String modelName) {
        DshRuntimeConfiguration configuration = runtimeConfig();
        return stream(agent, message, conversationId, modelName,
                resolveWorkingDirectory(null, configuration), new AtomicReference<>(),
                new AtomicReference<>(new RuntimeContextUsage(0, 0, 0)));
    }

    private Flux<AgentService.StreamDelta> stream(AgentEntity agent, String message,
                                                   String conversationId, String modelName,
                                                   Path workingDirectory,
                                                   AtomicReference<Process> processRef,
                                                   AtomicReference<RuntimeContextUsage> latestUsage) {
        return Flux.<AgentService.StreamDelta>create(sink -> {
            Process process = null;
            try {
                if (sink.isCancelled()) return;
                DshRuntimeConfiguration configuration = runtimeConfig();
                RuntimeSession session = new RuntimeSession(
                        conversationId,
                        conversationId,
                        agent.getId(),
                        agent.getWorkspaceId(),
                        modelName,
                        workingDirectory,
                        Map.of());
                // Each prompt runs in a fresh child process. DSH persists its
                // own session log, so reusing the MateClaw conversation id
                // would make the next turn look like a conflicting live session.
                String dshSessionId = conversationId + "-" + UUID.randomUUID();
                Files.createDirectories(session.workingDirectory());
                String requestedModel = modelName == null || modelName.isBlank() ? configuration.modelName() : modelName;
                ModelProviderEntity provider = resolveProvider(requestedModel);
                String effectiveModelName = resolveModelName(requestedModel);
                log.debug("[DSH] model route: requestedModel={}, effectiveModel={}, provider={}, apiKeyConfigured={}, baseUrlConfigured={}",
                        modelName == null || modelName.isBlank() ? "<default>" : modelName,
                        effectiveModelName,
                        provider == null ? "<missing>" : provider.getProviderId(),
                        provider != null && provider.getApiKey() != null && !provider.getApiKey().isBlank(),
                        provider != null && provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank());
                List<String> command = commandLine(configuration.executablePath());
                ProcessBuilder builder = new ProcessBuilder(command)
                        .directory(session.workingDirectory().toFile())
                        .redirectError(ProcessBuilder.Redirect.PIPE);
                Map<String, String> environment = builder.environment();
                Map<String, String> childEnvironment = childEnvironment(environment, session, configuration, provider);
                environment.clear();
                environment.putAll(childEnvironment);
                log.debug("[DSH] child environment: keys={}, cordisConfig={}, exists={}",
                        environment.keySet(),
                        environment.getOrDefault("DSH_CORDIS_CONFIG", "<empty>"),
                        !configuration.cordisConfigPath().isBlank() && Files.isRegularFile(Path.of(configuration.cordisConfigPath())));
                process = builder.start();
                processRef.set(process);
                if (sink.isCancelled()) {
                    cancelProcess(process);
                    return;
                }
                Process startedProcess = process;
                Thread stderrLogger = new Thread(() -> logProcessStderr(startedProcess),
                        "dsh-runtime-stderr-" + conversationId);
                stderrLogger.setDaemon(true);
                stderrLogger.start();
                sink.onCancel(() -> cancelProcess(startedProcess));
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8));
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             process.getInputStream(), StandardCharsets.UTF_8))) {
                    send(writer, request("initialize", "init-" + conversationId, Map.of(
                            "cwd", session.workingDirectory().toString(),
                            "provider", "deepseek-official",
                            "model", effectiveModelName)));
                    awaitResponse(reader, "init-" + conversationId);
                    long sequence = 0;
                    sink.next(RuntimeEventProjector.project(RuntimeEvent.of(
                            conversationId, sequence++, RuntimeEventType.RUNTIME_READY, null,
                            Map.of("runtimeProvider", "dsh", "runtimeCommand", configuration.executablePath()))));

                    String promptId = "prompt-" + conversationId;
                    send(writer, request("session/prompt", promptId, Map.of(
                            "sessionId", dshSessionId,
                            "contentBlocks", List.of(Map.of("type", "text", "text", message)))));

                    // DSH may emit session events before the JSON-RPC response
                    // for session/prompt. Read both on the same loop so those
                    // notifications are not discarded while waiting for id.
                    boolean terminal = false;
                    boolean promptResponseReceived = false;
                    String line;
                    while (!terminal && (line = reader.readLine()) != null) {
                        JsonNode payload = objectMapper.readTree(line);
                        if (payload == null) continue;
                        if (payload.has("id") && promptId.equals(payload.path("id").asText(null))) {
                            promptResponseReceived = true;
                            log.debug("[DSH] prompt response received: id={}, error={}", promptId,
                                    payload.has("error"));
                            if (payload.has("error")) {
                                throw new IllegalStateException(payload.path("error").path("message")
                                        .asText("DSH prompt failed"));
                            }
                            continue;
                        }
                        if (!payload.has("method")) continue;
                        String method = payload.path("method").asText();
                        JsonNode params = payload.path("params");
                        if (payload.has("id")) {
                            send(writer, errorResponse(payload.get("id"), -32601, "MateClaw does not support runtime request: " + method));
                            continue;
                        }
                        if ("session.event".equals(method)) {
                            JsonNode event = params.path("event");
                            log.debug("[DSH] event: type={}", event.path("type").asText("<missing>"));
                            logChunkMetadata(event);
                            logTerminalReason(event);
                            RuntimeEvent mapped = mapEvent(conversationId, sequence++, event);
                            if (mapped != null) {
                                if (mapped.type() == RuntimeEventType.CONTEXT_USAGE) {
                                    latestUsage.set(usageFrom(mapped));
                                }
                                sink.next(RuntimeEventProjector.project(mapped));
                                terminal = mapped.terminal();
                            }
                        } else if ("session.status".equals(method)
                                && promptResponseReceived
                                && "idle".equals(params.path("status").asText())) {
                            log.debug("[DSH] session idle after prompt");
                            sink.next(RuntimeEventProjector.project(RuntimeEvent.terminal(
                                    conversationId, sequence++, RuntimeEventType.COMPLETED, Map.of())));
                            terminal = true;
                        }
                    }
                    if (!terminal) {
                        int exitCode = process.waitFor();
                        sink.next(RuntimeEventProjector.project(RuntimeEvent.terminal(
                                conversationId, sequence, RuntimeEventType.FAILED,
                                Map.of("error", "DSH runtime closed before completion (exit=" + exitCode + ")"))));
                    }
                    sink.complete();
                }
            } catch (Exception error) {
                sink.error(new IllegalStateException("DSH runtime unavailable: " + error.getMessage(), error));
                if (process != null) process.destroyForcibly();
            } finally {
                if (process != null) processRef.compareAndSet(process, null);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    static Path resolveWorkingDirectory(RuntimeSession session, DshRuntimeConfiguration configuration) {
        if (session != null && session.workingDirectory() != null) {
            return session.workingDirectory().toAbsolutePath().normalize();
        }
        return Path.of(configuration.workingDirectory()).toAbsolutePath().normalize();
    }

    static void cancelProcess(Process process) {
        if (process == null || !process.isAlive()) return;

        // DSH tools can spawn commands such as `sleep` that inherit the
        // JSON-RPC process' stdout pipe. Close the pipes and terminate the
        // descendants first; otherwise the parent may die while readLine()
        // remains blocked until the child exits naturally.
        try {
            var descendants = process.descendants();
            if (descendants != null) {
                descendants.toList().forEach(DshRuntimeService::cancelProcessHandle);
            }
        } catch (Exception ignored) {
            // The parent teardown below is still the best-effort fallback.
        }
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }

    private static void cancelProcessHandle(ProcessHandle process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Exception ignored) {
            // Cancellation is best effort; the process termination is authoritative.
        }
    }

    private void logProcessStderr(Process process) {
        try (BufferedReader errors = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = errors.readLine()) != null) {
                log.warn("[DSH] {}", line);
            }
        } catch (IOException error) {
            log.debug("[DSH] stderr reader closed: {}", error.getMessage());
        }
    }

    static List<String> commandLine(String commandLine) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (char current : commandLine == null ? "".toCharArray() : commandLine.toCharArray()) {
            if (escaped) {
                token.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (current == quote) quote = 0;
                else token.append(current);
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (Character.isWhitespace(current)) {
                if (!token.isEmpty()) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(current);
            }
        }
        if (escaped) token.append('\\');
        if (quote != 0) throw new IllegalArgumentException("DSH runtime command has an unterminated quote");
        if (!token.isEmpty()) result.add(token.toString());
        if (result.isEmpty()) throw new IllegalStateException("DSH runtime command is empty");
        log.debug("[DSH] launching command: {}", result);
        return result;
    }

    static Map<String, String> childEnvironment(Map<String, String> inherited,
                                                RuntimeSession session,
                                                DshRuntimeConfiguration configuration,
                                                ModelProviderEntity provider) {
        Map<String, String> environment = new LinkedHashMap<>();
        copyIfPresent(inherited, environment, "PATH");
        copyIfPresent(inherited, environment, "HOME");
        copyIfPresent(inherited, environment, "USERPROFILE");
        copyIfPresent(inherited, environment, "TMPDIR");
        copyIfPresent(inherited, environment, "TEMP");
        copyIfPresent(inherited, environment, "TMP");
        copyIfPresent(inherited, environment, "SystemRoot");
        copyIfPresent(inherited, environment, "WINDIR");

        environment.put("DSH_CWD", session.workingDirectory().toString());
        putIfPresent(environment, "DSH_CORDIS_CONFIG", configuration.cordisConfigPath());
        putIfPresent(environment, "DEEPSEEK_API_KEY",
                firstNonBlank(configuration.apiKey(), provider == null ? null : provider.getApiKey()));
        putIfPresent(environment, "DEEPSEEK_BASE_URL",
                firstNonBlank(configuration.baseUrl(), provider == null ? null : provider.getBaseUrl()));
        return environment;
    }

    private static void copyIfPresent(Map<String, String> source, Map<String, String> target, String key) {
        if (source == null) return;
        putIfPresent(target, key, source.get(key));
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value == null || value.isBlank()) return;
        target.put(key, value);
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private ModelProviderEntity resolveProvider(String modelName) {
        ModelConfigEntity model = null;
        try {
            model = modelConfigService.resolveModel(modelName);
        } catch (RuntimeException ignored) {
            // Fall back to the dedicated DeepSeek provider below.
        }
        if (model != null && model.getProvider() != null && !model.getProvider().isBlank()) {
            try {
                return modelProviderService.getProviderConfig(model.getProvider());
            } catch (RuntimeException ignored) {
                // The model row may outlive its provider row; use the runtime default.
            }
        }
        try {
            return modelProviderService.getProviderConfig("deepseek");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveModelName(String modelName) {
        try {
            ModelConfigEntity model = modelConfigService.resolveModel(modelName);
            if (model != null && model.getModelName() != null && !model.getModelName().isBlank()) {
                return model.getModelName();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the DSH catalog default for a not-yet-configured agent.
        }
        return modelName == null || modelName.isBlank() ? "deepseek-v4-flash" : modelName;
    }

    RuntimeEvent mapEvent(String sessionId, long sequence, JsonNode event) {
        String type = event.path("type").asText("");
        JsonNode data = event.path("data");
        if ("assistant/chunk".equals(type)) {
            JsonNode chunk = data.has("chunk") ? data.path("chunk") : data;
            if ("usage".equals(chunk.path("type").asText())) {
                JsonNode usage = chunk.path("usage");
                long inputTokens = usage.path("inputTokens").asLong(0);
                long outputTokens = usage.path("outputTokens").asLong(0);
                return RuntimeEvent.of(sessionId, sequence, RuntimeEventType.CONTEXT_USAGE,
                        null, Map.of(
                                "promptTokens", inputTokens,
                                "completionTokens", outputTokens,
                                "inputTokens", inputTokens,
                                "outputTokens", outputTokens));
            }
            String text = firstText(chunk, data);
            if (text != null && !text.isEmpty()) {
                RuntimeEventType eventType = "reasoning-delta".equals(chunk.path("type").asText())
                        ? RuntimeEventType.THINKING_DELTA
                        : RuntimeEventType.ASSISTANT_DELTA;
                return RuntimeEvent.of(sessionId, sequence, eventType, text,
                        Map.of("chunkType", chunk.path("type").asText("unknown")));
            }
            if ("finish".equals(chunk.path("type").asText())
                    && "error".equals(chunk.path("reason").path("kind").asText())) {
                JsonNode failure = chunk.path("reason").path("failure");
                return RuntimeEvent.terminal(sessionId, sequence, RuntimeEventType.FAILED,
                        Map.of("error", failure.path("message").asText("DSH assistant failed"),
                                "code", failure.path("code").asText("DSH_RUNTIME_ERROR")));
            }
        }
        // The DSH stream emits text-delta chunks followed by an assistant/message
        // snapshot. Mapping both would append the same answer twice to the UI.
        if ("text-delta".equals(type)) {
            String text = firstText(data, event);
            if (text != null && !text.isEmpty()) {
                return RuntimeEvent.of(sessionId, sequence, RuntimeEventType.ASSISTANT_DELTA, text, Map.of());
            }
        }
        if (type.contains("tool") && (type.contains("start") || type.contains("call"))) {
            return RuntimeEvent.of(sessionId, sequence, RuntimeEventType.TOOL_STARTED, null,
                    Map.of("toolName", data.path("toolName").asText("dsh-tool")));
        }
        if (type.contains("tool") && (type.contains("end") || type.contains("result"))) {
            return RuntimeEvent.of(sessionId, sequence, RuntimeEventType.TOOL_FINISHED, null, Map.of());
        }
        if ("turn/end".equals(type)) {
            String kind = data.path("reason").path("kind").asText("");
            if ("error".equals(kind)) {
                return RuntimeEvent.terminal(sessionId, sequence, RuntimeEventType.FAILED,
                        Map.of("error", data.path("reason").path("error").path("message").asText("DSH turn failed")));
            }
        }
        return null;
    }

    private RuntimeContextUsage usageFrom(RuntimeEvent event) {
        return new RuntimeContextUsage(
                number(event.data().get("inputTokens")),
                number(event.data().get("outputTokens")),
                number(event.data().get("contextWindow")));
    }

    private long number(Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private String firstText(JsonNode primary, JsonNode fallback) {
        String text = primary.path("text").asText(null);
        if (text != null) return text;
        text = primary.path("delta").path("text").asText(null);
        if (text != null) return text;
        text = fallback.path("text").asText(null);
        if (text != null) return text;
        return fallback.path("delta").path("text").asText(null);
    }

    private void logChunkMetadata(JsonNode event) {
        if (!"assistant/chunk".equals(event.path("type").asText())) return;
        JsonNode data = event.path("data");
        JsonNode chunk = data.has("chunk") ? data.path("chunk") : data;
        log.debug("[DSH] assistant chunk: type={}, fields={}, dataFields={}, textPresent={}, textLength={}",
                chunk.path("type").asText("<missing>"),
                chunk.fieldNames().hasNext(), data.fieldNames().hasNext(),
                chunk.has("text"), chunk.path("text").isTextual() ? chunk.path("text").textValue().length() : 0);
    }

    private void logTerminalReason(JsonNode event) {
        String type = event.path("type").asText("");
        if (!"assistant/chunk".equals(type) && !"turn/end".equals(type)) return;
        JsonNode reason = "assistant/chunk".equals(type)
                ? event.path("data").path("chunk").path("reason")
                : event.path("data").path("reason");
        if (reason.isMissingNode() || reason.isNull()) return;
        JsonNode failure = reason.path("failure").isMissingNode()
                ? reason.path("error") : reason.path("failure");
        log.warn("[DSH] terminal reason: eventType={}, kind={}, code={}, message={}",
                type,
                reason.path("kind").asText("<missing>"),
                failure.path("code").asText("<none>"),
                failure.path("message").asText("<none>"));
    }

    private void awaitResponse(BufferedReader reader, String id) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode payload = objectMapper.readTree(line);
            if (payload != null && id.equals(payload.path("id").asText(null))) {
                if (payload.has("error")) {
                    throw new IllegalStateException(payload.path("error").path("message").asText("DSH JSON-RPC error"));
                }
                return;
            }
        }
        throw new IOException("DSH runtime closed while waiting for " + id);
    }

    private Map<String, Object> request(String method, String id, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private Map<String, Object> errorResponse(JsonNode id, int code, String message) {
        return Map.of("jsonrpc", "2.0", "id", objectMapper.convertValue(id, Object.class),
                "error", Map.of("code", code, "message", message));
    }

    private void send(BufferedWriter writer, Map<String, Object> payload) throws IOException {
        writer.write(objectMapper.writeValueAsString(payload));
        writer.newLine();
        writer.flush();
    }
}
