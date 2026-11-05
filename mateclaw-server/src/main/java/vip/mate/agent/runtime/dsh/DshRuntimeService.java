package vip.mate.agent.runtime.dsh;

import java.util.concurrent.TimeUnit;
import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;
import vip.mate.workspace.core.service.MemberFileIsolation;
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
import vip.mate.agent.runtime.dsh.management.DshGenerationStore;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfiguration;
import vip.mate.agent.AgentService;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ConversationWindowProperties windowProperties;
    @Value("${mateclaw.agent.runtime.dsh.initialize-timeout-ms:30000}")
    private long initializeTimeoutMs = 30000;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    private static final int DEFAULT_CONTEXT_WINDOW = 128000;

    public DshRuntimeService(
            ObjectMapper objectMapper,
            ModelConfigService modelConfigService,
            ModelProviderService modelProviderService,
            DshRuntimeConfigService runtimeConfigService,
            ConversationWindowProperties windowProperties) {
        this.objectMapper = objectMapper;
        this.modelConfigService = modelConfigService;
        this.modelProviderService = modelProviderService;
        this.runtimeConfigService = runtimeConfigService;
        this.windowProperties = windowProperties;
        // Validate lazily so administrators can repair invalid stored settings after startup.
        log.info("[DSH] SDK runtime adapter initialized");
    }

    private DshProcessSupervisor processSupervisor = new DshProcessSupervisor("native", "docker", "", new ObjectMapper());

    @org.springframework.beans.factory.annotation.Autowired
    public void setProcessSupervisor(DshProcessSupervisor supervisor) { this.processSupervisor = supervisor; }

    private DshRuntimeConfiguration runtimeConfig() {
        DshRuntimeConfiguration raw = runtimeConfigService.resolve();
        String cwd = raw.workingDirectory();
        if (cwd == null || cwd.isBlank()) cwd = System.getProperty("user.dir");
        return new DshRuntimeConfiguration(raw.executablePath(), raw.cordisConfigPath(), cwd,
                raw.baseUrl(), raw.modelName(), raw.apiKey(), raw.profile(), raw.patchPaths(), raw.homeRoot());
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
        try {
            DshLaunchSpec.create(configuration, session.workspaceId(), session.agentId(), session.workingDirectory());
        } catch (IllegalArgumentException error) {
            return RuntimeValidation.invalid(configuration.migrationRequired() ? "dsh.migration_required" : "dsh.config_invalid", error.getMessage());
        }
        return RuntimeValidation.success();
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(true, false, true, true);
    }

    public Map<String, Object> diagnostics() {
        DshRuntimeConfiguration configuration = runtimeConfig();
        Path executable = configuration.executablePath().isBlank() ? null : Path.of(configuration.executablePath());
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
        if (MemberFileIsolation.isEnabled()) {
            throw new SecurityException("DSH runtime is unavailable in member file isolation mode");
        }
        RuntimeValidation validation = validate(session);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.code() + ": " + validation.message());
        }
        AgentEntity agent = new AgentEntity();
        agent.setId(session.agentId());
        agent.setWorkspaceId(session.workspaceId());
        agent.setModelName(session.modelName());
        AtomicReference<Process> activeProcess = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<RuntimeContextUsage> latestUsage = new AtomicReference<>(
                new RuntimeContextUsage(0, 0, 0));
        return new AgentRuntimeConnection() {
            @Override
            public Flux<RuntimeEvent> prompt(String message) {
                return stream(agent, message, session.conversationId(), session.modelName(),
                        session.workingDirectory(), activeProcess, latestUsage, cancelled)
                        .map(DshRuntimeService.this::toRuntimeEvent);
            }

            @Override
            public Mono<Void> cancel() {
                return Mono.fromRunnable(
                        () -> { cancelled.set(true); cancelProcess(activeProcess.get()); });
            }

            @Override
            public Mono<RuntimeContextUsage> contextUsage() {
                return Mono.just(latestUsage.get());
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
            case "_usage_final" -> RuntimeEventType.CONTEXT_USAGE;
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
        return stream(agent, message, conversationId, modelName,
                null, new AtomicReference<>(),
                new AtomicReference<>(new RuntimeContextUsage(0, 0, 0)), new AtomicBoolean());
    }

    private Flux<AgentService.StreamDelta> stream(AgentEntity agent, String message,
                                                   String conversationId, String modelName,
                                                   Path workingDirectory,
                                                   AtomicReference<Process> processRef,
                                                   AtomicReference<RuntimeContextUsage> latestUsage, AtomicBoolean cancelled) {
        if (MemberFileIsolation.isEnabled()) {
            return Flux.error(new SecurityException("DSH runtime is unavailable in member file isolation mode"));
        }
        return Flux.<AgentService.StreamDelta>create(sink -> {
            Process process = null;
            DshGenerationStore.Lease lease = null;
            DshSdkTurn turn = null;
            boolean answerDelivered = false;
            long sequence = 0;
            String credential = "";
            try {
                if (sink.isCancelled()) return;
                if (cancelled.get()) throw new InterruptedException("DSH_CANCELLED");
                if (runtimeConfigService.generations() != null)
                    lease = runtimeConfigService.generations().acquireLease(String.valueOf(agent.getWorkspaceId()), String.valueOf(agent.getId()));
                DshRuntimeConfiguration configuration = runtimeConfig();
                RuntimeSession session = new RuntimeSession(
                        conversationId,
                        conversationId,
                        agent.getId(),
                        agent.getWorkspaceId(),
                        modelName,
                        workingDirectory == null ? resolveWorkingDirectory(null, configuration) : workingDirectory,
                        Map.of());
                // Each prompt runs in a fresh child process. DSH persists its
                // own session log, so reusing the MateClaw conversation id
                // would make the next turn look like a conflicting live session.
                String dshSessionId = conversationId + "-" + UUID.randomUUID();
                Files.createDirectories(session.workingDirectory());
                String requestedModel = modelName == null || modelName.isBlank() ? configuration.modelName() : modelName;
                ModelConfigEntity model = resolveModel(requestedModel);
                ModelProviderEntity provider = resolveProvider(model);
                credential = firstNonBlank(configuration.apiKey(), provider == null ? null : provider.getApiKey());
                String effectiveModelName = resolveModelName(requestedModel, model);
                int maxOutputTokens = resolveMaxOutputTokens(model, windowProperties.getDefaultMaxInputTokens());
                log.debug("[DSH] model route: requestedModel={}, effectiveModel={}, provider={}, apiKeyConfigured={}, baseUrlConfigured={}",
                        modelName == null || modelName.isBlank() ? "<default>" : modelName,
                        effectiveModelName,
                        provider == null ? "<missing>" : provider.getProviderId(),
                        provider != null && provider.getApiKey() != null && !provider.getApiKey().isBlank(),
                        provider != null && provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank());
                DshLaunchSpec launch = DshLaunchSpec.create(configuration, agent.getWorkspaceId(), agent.getId(), session.workingDirectory());
                Map<String, String> environment = childEnvironment(System.getenv(), session, configuration, provider);
                modelEnvironment(environment, effectiveModelName, model, maxOutputTokens);
                process = processSupervisor.start(launch, environment);
                processRef.set(process);
                if (process instanceof DshDockerProcess owned) {
                    var retainedLease = lease;
                    Process retainedProcess = process;
                    owned.afterCleanup(() -> {
                        if (retainedLease != null) retainedLease.close();
                        processRef.compareAndSet(retainedProcess, null);
                    });
                }
                Process startedProcess = process;
                sink.onCancel(() -> { cancelled.set(true); cancelProcess(startedProcess); });
                if (sink.isCancelled()) return;
                if (cancelled.get()) throw new InterruptedException("DSH_CANCELLED");
                try (DshSdkProcess sdk = new DshSdkProcess(process, objectMapper,
                        firstNonBlank(configuration.apiKey(), provider == null ? null : provider.getApiKey()))) {
                    sdk.initialize(Map.of("cwd", session.workingDirectory().toString(),
                            "provider", "deepseek-official", "model", effectiveModelName, "maxTokens", maxOutputTokens),
                            Duration.ofMillis(initializeTimeoutMs));
                    sink.next(RuntimeEventProjector.project(RuntimeEvent.of(conversationId, sequence++,
                            RuntimeEventType.RUNTIME_READY, null, Map.of("runtimeProvider", "dsh"))));
                    String promptId = "prompt-" + conversationId;
                    turn = new DshSdkTurn(dshSessionId, promptId);
                    sdk.send("session/prompt", promptId, Map.of("sessionId", dshSessionId,
                            "contentBlocks", List.of(Map.of("type", "text", "text", message))));
                    while (!turn.complete() && !sink.isCancelled()) {
                        JsonNode payload = sdk.next(Duration.ofMinutes(10));
                        if (payload.has("method") && payload.has("id")) {
                            sdk.send(errorResponse(payload.get("id"), -32601, "Unsupported runtime request"));
                            continue;
                        }
                        for (JsonNode event : turn.accept(payload)) {
                            String type = event.path("type").asText();
                            if ("assistant/message".equals(type)) {
                                for (JsonNode record : event.path("data").path("stream")) {
                                    JsonNode part = record.has("chunk") ? record.path("chunk") : record;
                                    if ("reasoning-chunks".equals(record.path("type").asText())) {
                                        StringBuilder reasoning = new StringBuilder();
                                        for (JsonNode text : record.path("texts")) reasoning.append(text.asText(""));
                                        sink.next(RuntimeEventProjector.project(RuntimeEvent.of(conversationId, sequence++,
                                                RuntimeEventType.THINKING_DELTA, reasoning.toString(), Map.of())));
                                    } else if ("reasoning-delta".equals(part.path("type").asText())) {
                                        sink.next(RuntimeEventProjector.project(RuntimeEvent.of(conversationId, sequence++,
                                                RuntimeEventType.THINKING_DELTA, part.path("text").asText(""), Map.of())));
                                    }
                                }
                            } else if ("turn/end".equals(type) && !answerDelivered && !turn.answer().isEmpty()) {
                                // The exact final step is known now; do not wait for idle or process shutdown.
                                sink.next(RuntimeEventProjector.project(RuntimeEvent.of(conversationId, sequence++,
                                        RuntimeEventType.ASSISTANT_DELTA, turn.answer(), Map.of())));
                                answerDelivered = true;
                            } else if ("tool/call".equals(type) || "tool/result".equals(type)) {
                                sink.next(RuntimeEventProjector.project(mapEvent(conversationId, sequence++, event)));
                            }
                        }
                    }
                }
                if (cancelled.get()) throw new InterruptedException("DSH_CANCELLED");
                if (!sink.isCancelled()) {
                    if (!answerDelivered && !turn.answer().isEmpty()) sink.next(RuntimeEventProjector.project(RuntimeEvent.of(
                            conversationId, sequence++, RuntimeEventType.ASSISTANT_DELTA, turn.answer(), Map.of())));
                    latestUsage.set(new RuntimeContextUsage(turn.inputTokens(), turn.outputTokens(), 0));
                    sink.next(RuntimeEventProjector.project(RuntimeEvent.of(conversationId, sequence++, RuntimeEventType.CONTEXT_USAGE,
                            null, Map.of("inputTokens", turn.inputTokens(), "outputTokens", turn.outputTokens(),
                                    "promptTokens", turn.inputTokens(), "completionTokens", turn.outputTokens(),
                                    "cacheReadTokens", turn.cacheReadTokens(), "cacheWriteTokens", turn.cacheWriteTokens()))));
                    sink.next(RuntimeEventProjector.project(RuntimeEvent.terminal(conversationId, sequence,
                            turn.terminalType(), turn.terminalType() == RuntimeEventType.COMPLETED ? Map.of()
                                    : Map.of("code", "DSH_INCOMPLETE_TURN", "error", "DSH turn ended: " + turn.reason()))));
                    sink.complete();
                }

            } catch (Exception error) {
                if (!sink.isCancelled() && !answerDelivered && turn != null && !turn.answer().isEmpty())
                    sink.next(RuntimeEventProjector.project(RuntimeEvent.of(conversationId, sequence++,
                            RuntimeEventType.ASSISTANT_DELTA, turn.answer(), Map.of())));
                boolean cancellationConfirmed = cancelled.get();
                if (process instanceof DshDockerProcess owned && !owned.cleanupConfirmed()) {
                    try { owned.destroy(); }
                    catch (RuntimeException unconfirmed) { cancellationConfirmed = false; error = unconfirmed; }
                }
                if (cancellationConfirmed) {
                    if (!sink.isCancelled()) {
                        sink.next(RuntimeEventProjector.project(RuntimeEvent.terminal(conversationId, 0, RuntimeEventType.CANCELLED, Map.of())));
                        sink.complete();
                    }
                } else sink.error(new IllegalStateException("DSH runtime unavailable: " + DshSdkProcess.redact(error.getMessage(), credential)));
                if (process != null && !(process instanceof DshDockerProcess)) process.destroyForcibly();
            } finally {
                if (process instanceof DshDockerProcess owned) {
                    // The ownership callback registered before SDK startup retains the
                    // lease until both removal and bookkeeping have succeeded.
                    try { cancelProcess(process); }
                    catch (RuntimeException unconfirmed) { log.warn("[DSH] Docker cleanup remains unconfirmed; admission retained"); }
                } else {
                    try { cancelProcess(process); }
                    finally {
                        if (process != null) processRef.compareAndSet(process, null);
                        if (lease != null) lease.close();
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Candidate checks deliberately bypass admission; the upgrade coordinator owns its gate. */
    public Map<String, Object> testConnection(DshRuntimeConfiguration configuration) {
        return healthCheck(configuration, false, null);
    }

    public Map<String, Object> testTask(DshRuntimeConfiguration configuration) {
        return healthCheck(configuration, true, null);
    }

    public Map<String, Object> testConnectionAtHome(DshRuntimeConfiguration configuration, Path home) {
        return healthCheck(configuration, false, home);
    }

    public Map<String, Object> testTaskAtHome(DshRuntimeConfiguration configuration, Path home) {
        return healthCheck(configuration, true, home);
    }

    private final ThreadLocal<DshDockerProcess> failedHealthCleanup = new ThreadLocal<>();
    private final java.util.Set<DshDockerProcess> pendingHealthCleanup = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void retryPendingHealthCleanup() {
        for (DshDockerProcess owned : pendingHealthCleanup) {
            owned.destroy();
            if (owned.cleanupConfirmed()) pendingHealthCleanup.remove(owned);
        }
    }

    public void releaseHealthLease(Runnable release) {
        DshDockerProcess owned = failedHealthCleanup.get();
        failedHealthCleanup.remove();
        if (owned == null) release.run();
        else owned.afterCleanup(release);
    }

    private Map<String, Object> healthCheck(DshRuntimeConfiguration configuration, boolean task, Path home) {
        long started = System.currentTimeMillis();
        Process process = null;
        failedHealthCleanup.remove();
        try {
            retryPendingHealthCleanup();
            if (MemberFileIsolation.isEnabled()) throw new SecurityException("DSH_MEMBER_ISOLATION_UNSUPPORTED");
            Path cwd = Path.of(configuration.workingDirectory());
            DshLaunchSpec launch = DshLaunchSpec.create(configuration, "health", "health", cwd);
            if (home != null) launch = new DshLaunchSpec(launch.command(), home.toAbsolutePath().normalize(), launch.cwd());
            ModelConfigEntity model = resolveModel(configuration.modelName());
            ModelProviderEntity provider = resolveProvider(model);
            RuntimeSession session = new RuntimeSession("health", "health", 0L, 0L, configuration.modelName(), cwd, Map.of());
            Map<String, String> environment = childEnvironment(System.getenv(), session, configuration, provider);
            modelEnvironment(environment, resolveModelName(configuration.modelName(), model), model,
                    task ? 64 : resolveMaxOutputTokens(model, windowProperties.getDefaultMaxInputTokens()));
            process = processSupervisor.start(launch, environment);
            try (DshSdkProcess sdk = new DshSdkProcess(process, objectMapper,
                    firstNonBlank(configuration.apiKey(), provider == null ? null : provider.getApiKey()))) {
                sdk.initialize(Map.of("cwd", cwd.toString(), "provider", "deepseek-official",
                                "model", resolveModelName(configuration.modelName(), model),
                                "maxTokens", task ? 64 : resolveMaxOutputTokens(model, windowProperties.getDefaultMaxInputTokens())),
                        Duration.ofMillis(initializeTimeoutMs));
                if (task) {
                    String id = UUID.randomUUID().toString();
                    DshSdkTurn turn = new DshSdkTurn(id, "health-prompt");
                    sdk.send("session/prompt", "health-prompt", Map.of("sessionId", id,
                            "contentBlocks", List.of(Map.of("type", "text", "text", "Reply with exactly OK. Do not use tools."))));
                    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                    while (!turn.complete()) {
                        if (System.nanoTime() >= deadline) throw new IOException("DSH_TASK_TIMEOUT");
                        turn.accept(sdk.next(Duration.ofNanos(deadline - System.nanoTime())));
                    }
                    if (turn.terminalType() != RuntimeEventType.COMPLETED || !"OK".equals(turn.answer().trim()))
                        throw new IOException("DSH_TASK_INCOMPLETE: " + turn.reason());
                }
            }
            return Map.of("success", true, "kind", task ? "task" : "handshake",
                    "message", task ? "SDK model task completed" : "SDK initialize handshake completed",
                    "checkedAt", Instant.now().toString(), "durationMs", System.currentTimeMillis() - started,
                    "versionStatus", "UNVERIFIED_VERSION");
        } catch (Exception error) {
            if (process instanceof DshDockerProcess owned && !owned.cleanupConfirmed()) {
                pendingHealthCleanup.add(owned);
                failedHealthCleanup.set(owned);
            }
            return Map.of("success", false, "kind", task ? "task" : "handshake",
                    "message", DshSdkProcess.redact(error.getMessage(), configuration.apiKey()),
                    "checkedAt", Instant.now().toString(), "durationMs", System.currentTimeMillis() - started);
        }
    }

    static Path resolveWorkingDirectory(RuntimeSession session, DshRuntimeConfiguration configuration) {
        if (session != null && session.workingDirectory() != null) {
            return session.workingDirectory().toAbsolutePath().normalize();
        }
        return Path.of(configuration.workingDirectory()).toAbsolutePath().normalize();
    }

    static void cancelProcess(Process process) {
        if (process == null) return;

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
        try { process.waitFor(1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void cancelProcessHandle(ProcessHandle process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }

    private static void closeQuietly(Closeable stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Exception ignored) {
            // Cancellation is best effort; the process termination is authoritative.
        }
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
        environment.put("DSH_HOME", Path.of(configuration.homeRoot()).toAbsolutePath().normalize()
                .resolve(UUID.nameUUIDFromBytes(String.valueOf(session.workspaceId()).getBytes(StandardCharsets.UTF_8)).toString())
                .resolve(UUID.nameUUIDFromBytes(String.valueOf(session.agentId()).getBytes(StandardCharsets.UTF_8)).toString()).toString());
        putIfPresent(environment, "DEEPSEEK_API_KEY",
                firstNonBlank(configuration.apiKey(), provider == null ? null : provider.getApiKey()));
        putIfPresent(environment, "DEEPSEEK_BASE_URL",
                normalizeBaseUrl(firstNonBlank(configuration.baseUrl(), provider == null ? null : provider.getBaseUrl())));
        return environment;
    }

    private void modelEnvironment(Map<String, String> environment, String modelName, ModelConfigEntity model, int output) {
        int window = model != null && model.getMaxInputTokens() != null && model.getMaxInputTokens() > 0
                ? model.getMaxInputTokens() : windowProperties.getDefaultMaxInputTokens() > 0
                ? windowProperties.getDefaultMaxInputTokens() : DEFAULT_CONTEXT_WINDOW;
        environment.put("MATECLAW_DSH_MODEL", modelName);
        environment.put("MATECLAW_DSH_CONTEXT_WINDOW", Integer.toString(window));
        environment.put("MATECLAW_DSH_MAX_TOKENS", Integer.toString(output));
    }

    static String normalizeBaseUrl(String url) {
        if (url == null) return null;
        return url.matches("https://api\\.deepseek\\.com(?:/v1)?/?") ? "https://api.deepseek.com/anthropic" : url;
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

    private ModelConfigEntity resolveModel(String modelName) {
        try {
            return modelConfigService.resolveModel(modelName);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ModelProviderEntity resolveProvider(ModelConfigEntity model) {
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

    private static String resolveModelName(String modelName, ModelConfigEntity model) {
        if (model != null && model.getModelName() != null && !model.getModelName().isBlank()) {
            return model.getModelName();
        }
        return modelName == null || modelName.isBlank() ? "deepseek-v4-flash" : modelName;
    }

    /**
     * SDK initialize.maxTokens is inherited by main and in-process child agents.
     * Never let an unset model cap fall through to DSH's 256000-token default.
     * As in the graph runtime, reserve at least half the known window for input.
     * This is a static bound, not a token count of DSH's growing tool history.
     */
    static int resolveMaxOutputTokens(ModelConfigEntity model, int defaultWindow) {
        int output = model != null && model.getMaxTokens() != null && model.getMaxTokens() > 0
                ? model.getMaxTokens() : DEFAULT_MAX_OUTPUT_TOKENS;
        int window = model != null && model.getMaxInputTokens() != null && model.getMaxInputTokens() > 0
                ? model.getMaxInputTokens() : defaultWindow > 0 ? defaultWindow : DEFAULT_CONTEXT_WINDOW;
        if (window < 2) {
            throw new IllegalArgumentException("DSH 模型上下文窗口过小，无法同时容纳输入和输出，请检查模型配置");
        }
        return Math.min(output, window / 2);
    }

    RuntimeEvent mapEvent(String sessionId, long sequence, JsonNode event) {
        String type = event.path("type").asText("");
        JsonNode data = event.path("data");
        if ("assistant/message".equals(type)) {
            JsonNode content = data.path("message").path("content");
            if (!content.isArray()) throw new IllegalArgumentException("DSH_PROTOCOL_ERROR: malformed assistant content");
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText(""));
            }
            return RuntimeEvent.of(sessionId, sequence, RuntimeEventType.ASSISTANT_DELTA, text.toString(), Map.of());
        }
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
                    Map.of("toolName", data.path("name").asText("dsh-tool"), "callId", data.path("callId").asText("")));
        }
        if (type.contains("tool") && (type.contains("end") || type.contains("result"))) {
            String code = data.path("error").path("code").asText("");
            boolean failed = data.path("message").path("isError").asBoolean(false);
            return RuntimeEvent.of(sessionId, sequence, RuntimeEventType.TOOL_FINISHED, null, Map.of(
                    "callId", data.path("message").path("toolCallId").asText(data.path("callId").asText("")),
                    "isError", failed, "success", !failed, "errorCode", code, "cancelled", failed && "ABORTED".equals(code)));
        }
        if ("turn/end".equals(type)) {
            String kind = data.path("reason").path("kind").asText("");
            return RuntimeEvent.terminal(sessionId, sequence,
                    "completed".equals(kind) ? RuntimeEventType.COMPLETED : RuntimeEventType.FAILED,
                    "completed".equals(kind) ? Map.of() : Map.of("code", "DSH_INCOMPLETE_TURN", "error", "DSH turn ended: " + kind));
        }
        return null;
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

    private Map<String, Object> errorResponse(JsonNode id, int code, String message) {
        return Map.of("jsonrpc", "2.0", "id", objectMapper.convertValue(id, Object.class),
                "error", Map.of("code", code, "message", message));
    }

}
