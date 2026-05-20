package vip.mate.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.*;
import vip.mate.llm.oauth.OpenAIOAuthService;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDiscoveryService {

    private final ModelProviderService modelProviderService;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;
    private final OpenAIOAuthService openAIOAuthService;

    /**
     * The Codex models endpoint that the ChatGPT subscription OAuth path exposes.
     * Returns a JSON object {@code {"models": [{slug, supported_in_api, visibility,
     * priority, ...}]}} once authenticated with a Bearer access token.
     */
    static final String CHATGPT_CODEX_MODELS_URL =
            "https://chatgpt.com/backend-api/codex/models?client_version=1.0.0";

    /**
     * Synthetic forward-compat catalog: when a newer Codex slug is not surfaced
     * by the live API but a known older sibling is, append the newer slug so
     * users can opt into models OpenAI is rolling out without waiting for the
     * metadata to flip. Mirrors the upstream Codex CLI behaviour.
     */
    private static final List<Map.Entry<String, List<String>>> CHATGPT_FORWARD_COMPAT =
            List.of(
                    Map.entry("gpt-5.5", List.of("gpt-5.4", "gpt-5.4-mini", "gpt-5.3-codex")),
                    Map.entry("gpt-5.4-mini", List.of("gpt-5.3-codex", "gpt-5.2-codex")),
                    Map.entry("gpt-5.4", List.of("gpt-5.3-codex", "gpt-5.2-codex")),
                    Map.entry("gpt-5.3-codex", List.of("gpt-5.2-codex"))
            );

    private RestClient chatgptCodexClient = RestClient.create();

    /** Test seam — let unit tests point this at a {@link org.springframework.test.web.client.MockRestServiceServer}. */
    void setChatgptCodexClient(RestClient client) {
        this.chatgptCodexClient = client;
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Virtual-thread executor for parallel model probing (lightweight, short-lived)
    private static final ExecutorService PROBE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    // Probe concurrency cap — reduced from 5 to 3 because higher parallelism
    // triggered 429 Throttling.RateQuota on DashScope during bulk refresh
    private static final int MAX_PROBE_CONCURRENCY = 3;

    // Per-model probe timeout (short; we only need to know "yes/no usable")
    private static final long PROBE_TIMEOUT_SECONDS = 12;

    /**
     * Explicit deny list: model ids listed by DashScope compatible-mode that are known
     * to fail on the native protocol. Updated as we observe new failures.
     * Note: the DASHSCOPE_NATIVE_UNSUPPORTED_PATTERN below also catches the whole
     * dot-versioned family; this set makes individual blocked names searchable/auditable.
     */
    private static final Set<String> DASHSCOPE_NATIVE_DENY = Set.of(
            "qwen3.5-max",
            "qwen3.5-plus",
            "qwen3.6-plus",
            "qwen3.6-max"
    );

    /**
     * Pattern matching DashScope model ids that use a dot-versioned family (e.g.
     * "qwen3.5-max", "qwen3.6-plus"). These are only offered on compatible-mode
     * and consistently fail on the native endpoint with
     * "[InvalidParameter] url error". Block them regardless of exact name.
     */
    private static final java.util.regex.Pattern DASHSCOPE_NATIVE_UNSUPPORTED_PATTERN =
            java.util.regex.Pattern.compile("^qwen\\d+\\.\\d+.*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Parameter-size suffixes used by DashScope open-source base models (e.g.
     * {@code qwen3-0.6b}, {@code qwen3-8b}, {@code qwen3-32b}, {@code qwen3-30b-a3b}).
     * These are catalog entries, not DashScope-hosted chat endpoints, and the native
     * protocol returns {@code InvalidParameter: parameter.enable_thinking ...} for
     * any attempt to invoke them. Pre-filter them out of discovery.
     */
    private static final java.util.regex.Pattern DASHSCOPE_OPEN_SOURCE_SIZE_PATTERN =
            java.util.regex.Pattern.compile("^qwen\\d+-\\d+(?:\\.\\d+)?b(?:-.*)?$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Prefixes that identify non-chat modalities (image generation, vision understanding,
     * TTS, ASR, omni/multimodal bases, realtime speech, live translation, OCR, speech-to-
     * speech, voice-clone). They are catalog-visible but have different endpoints than
     * the native chat-generation API, so probing them via chat always fails with
     * "url error". Blocking them up-front cuts discovery time and log noise dramatically.
     */
    private static final Set<String> DASHSCOPE_NON_CHAT_PREFIXES = Set.of(
            // Vision understanding / OCR
            "qwen-vl-",
            "qwen3-vl-",
            // Image generation / edit
            "qwen-image-",
            "qwen3-image-",
            // TTS / ASR / speech-to-speech / voice
            "qwen-tts-",
            "qwen3-tts-",
            "qwen-asr-",
            "qwen3-asr-",
            "qwen-s2s-",
            "qwen3-s2s-",
            // Omni multimodal bases
            "qwen-omni-",
            "qwen3-omni-",
            // Audio understanding
            "qwen-audio-",
            // Live translation
            "qwen-livetranslate-",
            "qwen3-livetranslate-"
    );

    /**
     * Model-id prefixes that mark an embedding model. These pass the chat-focused
     * acceptable-id check (so the manual "Add model" form works) but must be
     * excluded from chat-style runtime probes and from "new chat models" auto
     * suggestions — they only speak the embeddings protocol, not chat completion.
     */
    private static final Set<String> EMBEDDING_MODEL_PREFIXES = Set.of(
            "text-embedding-",
            "embedding-"
    );

    /**
     * Allow-list prefixes for DashScope models that are known to work on the native
     * protocol (chat or embedding). An empty set means "no prefix filter" (we still
     * apply DENY). Extend conservatively as new families are verified.
     */
    private static final Set<String> DASHSCOPE_NATIVE_ALLOW_PREFIXES = Set.of(
            "qwen-",          // qwen-max / qwen-plus / qwen-turbo / qwen-coder-* / qwen-long
            "qwen2-",         // qwen2 series
            "qwen3-",         // qwen3-max / qwen3-plus / qwen3-coder / qwen3-235b-*
            "deepseek-",      // deepseek-v3.x / deepseek-r1*
            "baichuan",
            "yi-",
            "llama",
            "text-embedding-" // DashScope embedding models (text-embedding-v1/v2/v3/v4)
    );

    // ==================== 模型发现 ====================

    public DiscoverResult discoverModels(String providerId) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        if (!Boolean.TRUE.equals(provider.getSupportModelDiscovery())) {
            throw new MateClawException("err.llm.discovery_not_supported", "该供应商不支持模型发现: " + providerId);
        }

        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        List<ModelInfoDTO> discovered = fetchRemoteModels(provider, protocol);

        // Layer 2: Protocol-aware allow/deny filtering. The listing endpoint
        // (compatible-mode /v1/models for DashScope) often returns models that
        // the native SDK does not accept — filter them out before the user sees
        // them.
        discovered = applyProtocolFilter(discovered, protocol, providerId);

        // Layer 3: Probe each remaining model with a real runtime-protocol call.
        // This catches any model the allow-list let through but the provider
        // actually rejects at request time. Failed probes are kept in the list
        // but marked probeOk=false so the UI can show a warning badge.
        discovered = probeInParallel(discovered, provider, protocol);

        // De-dupe against already-configured models for the "new" bucket
        Set<String> existingIds = modelConfigService.listModelsByProvider(providerId).stream()
                .map(ModelConfigEntity::getModelName)
                .collect(Collectors.toSet());

        // Only propose models that passed the probe (or were not probed) as "new".
        // Embedding models are catalog-visible but excluded from the chat-discovery
        // suggestion bucket — adding them as chat models would 400 at runtime.
        List<ModelInfoDTO> newModels = discovered.stream()
                .filter(m -> !existingIds.contains(m.getId()))
                .filter(m -> !Boolean.FALSE.equals(m.getProbeOk()))
                .filter(m -> !isEmbeddingModelId(m.getId()))
                .toList();

        return new DiscoverResult(discovered, newModels, discovered.size(), newModels.size());
    }

    /**
     * Apply protocol-aware allow/deny filtering to the raw discovery list.
     * <p>
     * Currently only DashScope is filtered: the compatible-mode listing includes
     * many models the native SDK rejects. Other providers pass through unchanged.
     */
    private List<ModelInfoDTO> applyProtocolFilter(List<ModelInfoDTO> discovered,
                                                    ModelProtocol protocol,
                                                    String providerId) {
        if (protocol != ModelProtocol.DASHSCOPE_NATIVE) {
            return discovered;
        }
        int before = discovered.size();
        List<ModelInfoDTO> filtered = discovered.stream()
                .filter(m -> isDashScopeModelIdAcceptable(m.getId()))
                .toList();
        if (filtered.size() < before) {
            log.info("[ModelDiscovery] Filtered {} -> {} DashScope models for provider={} (allow/deny rules)",
                    before, filtered.size(), providerId);
        }
        return filtered;
    }

    /**
     * Return true if a DashScope model id is allowed on the native chat protocol.
     * Rejection rules (in order):
     *   1. Explicit DENY set (e.g. qwen3.5-max)
     *   2. Dot-version family pattern (qwen3.5-*, qwen3.6-*, ...)
     *   3. Non-chat modality prefix (vl, image, tts, asr, omni, audio, s2s, ocr, livetranslate)
     *   4. Open-source parameter-size suffix (qwen3-8b, qwen3-32b, qwen3-30b-a3b, qwen3-0.6b ...)
     *   5. Must start with a known ALLOW prefix (qwen-, qwen2-, qwen3-, deepseek-, ...)
     */
    private static boolean isDashScopeModelIdAcceptable(String modelId) {
        if (modelId == null || modelId.isBlank()) return false;
        String lower = modelId.toLowerCase();
        if (DASHSCOPE_NATIVE_DENY.contains(lower)) return false;
        if (DASHSCOPE_NATIVE_UNSUPPORTED_PATTERN.matcher(lower).matches()) return false;
        if (DASHSCOPE_NON_CHAT_PREFIXES.stream().anyMatch(lower::startsWith)) return false;
        if (DASHSCOPE_OPEN_SOURCE_SIZE_PATTERN.matcher(lower).matches()) return false;
        if (DASHSCOPE_NATIVE_ALLOW_PREFIXES.isEmpty()) return true;
        return DASHSCOPE_NATIVE_ALLOW_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /**
     * Returns true when a model id looks like an embedding model (e.g.
     * {@code text-embedding-v3}). Used to skip chat-style probes and to keep
     * embedding entries out of the "new chat models to add" auto-suggestion.
     * Package-private for unit tests.
     */
    static boolean isEmbeddingModelId(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        return EMBEDDING_MODEL_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /**
     * Defensive guard for code paths that persist a model id without going through
     * discovery (e.g. the manual "Add model" form). Throws a MateClawException with
     * a user-friendly message if the id is known to be unusable under the provider's
     * runtime protocol.
     */
    public static void assertModelIdAcceptable(String providerId, ModelProviderEntity provider, String modelId) {
        if (provider == null) return;
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        if (protocol == ModelProtocol.DASHSCOPE_NATIVE && !isDashScopeModelIdAcceptable(modelId)) {
            throw new MateClawException(
                    "err.llm.model_not_supported",
                    "Model id '" + modelId + "' is not supported on DashScope native protocol. " +
                            "Dot-versioned families (e.g. qwen3.5-*, qwen3.6-*) are only available via compatible-mode. " +
                            "Use an allowed id such as qwen-max / qwen-plus / qwen3-max."
            );
        }
    }

    /**
     * Probe each discovered model in parallel (bounded concurrency) using the same
     * protocol the runtime will use. Populates {@code probeOk}/{@code probeError}
     * on each DTO; does not remove failed entries so the UI can surface the reason.
     */
    private List<ModelInfoDTO> probeInParallel(List<ModelInfoDTO> discovered,
                                                 ModelProviderEntity provider,
                                                 ModelProtocol protocol) {
        if (discovered.isEmpty()) return discovered;

        // OpenAI ChatGPT has no model-level test, skip probe for it
        if (protocol == ModelProtocol.OPENAI_CHATGPT) return discovered;

        Semaphore sem = new Semaphore(MAX_PROBE_CONCURRENCY);
        List<CompletableFuture<Void>> futures = new ArrayList<>(discovered.size());
        for (ModelInfoDTO dto : discovered) {
            if (isEmbeddingModelId(dto.getId())) {
                // Embedding models don't speak chat completion — sendTestPrompt would
                // 400 with InvalidParameter. Leave probeOk null (no warning badge)
                // and surface a clear, non-error reason in probeError.
                dto.setProbeError("Embedding model — not chat-probeable");
                continue;
            }
            futures.add(CompletableFuture.runAsync(() -> {
                try { sem.acquire(); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                try {
                    sendTestPrompt(provider, protocol, dto.getId());
                    dto.setProbeOk(true);
                } catch (Exception e) {
                    dto.setProbeOk(false);
                    dto.setProbeError(shortError(e));
                    // DEBUG level — per-model probe failures are an expected part of bulk
                    // discovery (DashScope lists many deprecated/restricted models). The
                    // aggregate "Probe results: X passed, Y failed" summary below is
                    // sufficient for normal operations. Enable DEBUG for ModelDiscovery
                    // if you need to inspect individual reasons.
                    log.debug("[ModelDiscovery] Probe failed for model={}: {}", dto.getId(), dto.getProbeError());
                } finally {
                    sem.release();
                }
            }, PROBE_EXECUTOR));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(PROBE_TIMEOUT_SECONDS * Math.max(1, discovered.size() / MAX_PROBE_CONCURRENCY + 1),
                         TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            log.warn("[ModelDiscovery] Probe batch timeout; {} models may be marked unknown",
                    futures.stream().filter(f -> !f.isDone()).count());
        } catch (Exception e) {
            log.warn("[ModelDiscovery] Probe batch wait failed: {}", e.getMessage());
        }
        long passed = discovered.stream().filter(m -> Boolean.TRUE.equals(m.getProbeOk())).count();
        long failed = discovered.stream().filter(m -> Boolean.FALSE.equals(m.getProbeOk())).count();
        log.info("[ModelDiscovery] Probe results: {} passed, {} failed, {} unknown (of {})",
                passed, failed, discovered.size() - passed - failed, discovered.size());
        return discovered;
    }

    private String shortError(Exception e) {
        String msg = extractErrorMessage(e);
        if (msg == null) return "unknown error";
        // Clip to ~120 chars so the UI tooltip stays usable
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }

    // ==================== 连接测试 ====================

    public TestResult testConnection(String providerId) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        long start = System.currentTimeMillis();

        try {
            if (Boolean.TRUE.equals(provider.getSupportModelDiscovery())) {
                // 支持模型发现的 provider：调用模型列表 API 验证连接
                fetchRemoteModels(provider, protocol);
                long latency = System.currentTimeMillis() - start;
                return TestResult.ok(latency, "连接成功");
            } else {
                // 不支持模型发现（如智谱）：用第一个已配置模型发送测试请求
                List<ModelConfigEntity> models = modelConfigService.listModelsByProvider(providerId);
                if (models.isEmpty()) {
                    throw new MateClawException("err.llm.no_model_for_test", "该供应商没有已配置的模型，无法测试连接");
                }
                String testModelId = models.get(0).getModelName();
                String response = sendTestPrompt(provider, protocol, testModelId);
                long latency = System.currentTimeMillis() - start;
                return TestResult.ok(latency, response);
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return TestResult.fail(latency, extractErrorMessage(e));
        }
    }

    // ==================== 单模型测试 ====================

    public TestResult testModel(String providerId, String modelId) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        long start = System.currentTimeMillis();

        try {
            String response = sendTestPrompt(provider, protocol, modelId);
            long latency = System.currentTimeMillis() - start;
            return TestResult.ok(latency, response);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return TestResult.fail(latency, extractErrorMessage(e));
        }
    }

    // ==================== 批量添加发现的模型 ====================

    public int batchAddModels(String providerId, List<String> modelIds) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        Set<String> existingIds = modelConfigService.listModelsByProvider(providerId).stream()
                .map(ModelConfigEntity::getModelName)
                .collect(Collectors.toSet());

        int added = 0;
        int skipped = 0;
        for (String modelId : modelIds) {
            if (modelId == null || modelId.isBlank()) continue;
            if (existingIds.contains(modelId)) continue;
            // Defense-in-depth: never add a DashScope model that fails the protocol-aware check
            if (protocol == ModelProtocol.DASHSCOPE_NATIVE && !isDashScopeModelIdAcceptable(modelId)) {
                log.warn("[ModelDiscovery] Refusing to add {} — blocked by DashScope native protocol filter", modelId);
                skipped++;
                continue;
            }
            modelConfigService.addModelToProvider(providerId, modelId, modelId, false);
            added++;
        }
        if (skipped > 0) {
            log.info("[ModelDiscovery] batchAddModels: added={}, skipped(deny)={}", added, skipped);
        }
        return added;
    }

    // ==================== 协议分派：模型列表 ====================

    private List<ModelInfoDTO> fetchRemoteModels(ModelProviderEntity provider, ModelProtocol protocol) {
        return switch (protocol) {
            case OPENAI_COMPATIBLE -> fetchOpenAiCompatibleModels(provider);
            case DASHSCOPE_NATIVE -> fetchDashScopeModels(provider);
            case GEMINI_NATIVE -> fetchGeminiModels(provider);
            case ANTHROPIC_MESSAGES -> fetchAnthropicModels(provider);
            // ChatGPT OAuth has its own discovery endpoint at chatgpt.com/backend-api/codex.
            case OPENAI_CHATGPT -> fetchChatGPTOAuthModels(provider);
            // Claude Code OAuth has a fixed model catalog — Anthropic doesn't
            // expose a discovery endpoint to Bearer-auth requests, so models
            // are seeded via Flyway.
            case ANTHROPIC_CLAUDE_CODE ->
                    throw new MateClawException("err.llm.oauth_no_discovery",
                            "OAuth provider 不支持模型发现");
        };
    }

    private List<ModelInfoDTO> fetchOpenAiCompatibleModels(ModelProviderEntity provider) {
        String baseUrl = normalizeBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.llm.base_url_missing", "Base URL 未配置");
        }
        String apiKey = provider.getApiKey();

        RestClient client = openAiCompatibleClientBuilder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        RestClient.RequestHeadersSpec<?> spec = client.get().uri(resolveModelsPath(baseUrl));
        if (modelProviderService.hasUsableApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }
        // Apply any custom headers declared in generateKwargs.
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        applyCustomHeaders(spec, kwargs);

        String body = spec.retrieve().body(String.class);
        return parseOpenAiModelsResponse(body);
    }

    private List<ModelInfoDTO> fetchDashScopeModels(ModelProviderEntity provider) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.dashscope_key_missing", "DashScope API Key 未配置");
        }

        // DashScope 兼容模式暴露了 OpenAI 兼容的 /v1/models 端点
        RestClient client = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .build();

        String body = client.get().uri("/v1/models").retrieve().body(String.class);
        return parseOpenAiModelsResponse(body);
    }

    private List<ModelInfoDTO> fetchGeminiModels(ModelProviderEntity provider) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.gemini_key_missing", "Gemini API Key 未配置");
        }

        RestClient client = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        String body = client.get()
                .uri("/v1beta/models?key={key}", apiKey.trim())
                .retrieve()
                .body(String.class);
        return parseGeminiModelsResponse(body);
    }

    private List<ModelInfoDTO> fetchAnthropicModels(ModelProviderEntity provider) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.anthropic_key_missing", "Anthropic API Key 未配置");
        }

        String baseUrl = StringUtils.hasText(provider.getBaseUrl())
                ? normalizeBaseUrl(provider.getBaseUrl())
                : "https://api.anthropic.com";

        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey.trim())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();

        String body = client.get().uri("/v1/models").retrieve().body(String.class);
        return parseAnthropicModelsResponse(body);
    }

    /**
     * Fetch the ChatGPT subscription OAuth model catalog. Uses the user's
     * already-stored OAuth access token (auto-refreshed if it's near expiry)
     * and hits the same Codex endpoint the upstream client uses. Filters out
     * models the API marks as not exposed ({@code supported_in_api == false})
     * or hidden, sorts by priority, then layers in synthetic forward-compat
     * entries (e.g. surface {@code gpt-5.5} when only older siblings are
     * returned).
     */
    private List<ModelInfoDTO> fetchChatGPTOAuthModels(ModelProviderEntity provider) {
        String accessToken;
        try {
            accessToken = openAIOAuthService.ensureValidAccessToken();
        } catch (MateClawException e) {
            // Surface the precise i18n key from OpenAIOAuthService (e.g.
            // err.llm.oauth_not_connected) so the UI can prompt the user to
            // sign in. Wrapping would lose that signal.
            throw e;
        }

        String body;
        try {
            body = chatgptCodexClient.get()
                    .uri(CHATGPT_CODEX_MODELS_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("[ModelDiscovery] ChatGPT OAuth models fetch failed: {}", e.getMessage());
            throw new MateClawException("err.llm.chatgpt_models_fetch_failed",
                    "拉取 ChatGPT 可用模型失败: " + e.getMessage());
        }

        return addChatGPTForwardCompatModels(parseChatGPTCodexModelsResponse(body));
    }

    /**
     * Parse the {@code {"models": [{slug, supported_in_api, visibility, priority}]}}
     * response. Drops entries the API hides from the OAuth catalog and orders
     * the rest by ascending {@code priority} (lower = higher precedence in
     * the upstream client's UX).
     */
    List<ModelInfoDTO> parseChatGPTCodexModelsResponse(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode entries = root.path("models");
            if (!entries.isArray()) return List.of();

            // Sort by priority ascending while preserving the API-listed slug
            List<int[]> indices = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                JsonNode item = entries.get(i);
                if (!item.isObject()) continue;
                String slug = item.path("slug").asText("").trim();
                if (slug.isEmpty()) continue;
                if (item.path("supported_in_api").asBoolean(true) == false) continue;
                String vis = item.path("visibility").asText("").trim().toLowerCase();
                if ("hide".equals(vis) || "hidden".equals(vis)) continue;
                int priority = item.has("priority") && item.get("priority").isNumber()
                        ? item.get("priority").asInt()
                        : 10_000;
                indices.add(new int[]{priority, i});
            }
            indices.sort(Comparator.comparingInt((int[] a) -> a[0]).thenComparingInt(a -> a[1]));

            Set<String> seen = new LinkedHashSet<>();
            List<ModelInfoDTO> out = new ArrayList<>();
            for (int[] idx : indices) {
                JsonNode item = entries.get(idx[1]);
                String slug = item.path("slug").asText("").trim();
                if (!seen.add(slug)) continue;
                out.add(new ModelInfoDTO(slug, slug));
            }
            return out;
        } catch (Exception e) {
            log.warn("[ModelDiscovery] Failed to parse ChatGPT codex models response: {}",
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * Append synthetic forward-compat entries for newer slugs that the API
     * has not yet surfaced but a known older sibling is present for. Mirrors
     * the reference client's behaviour so users can opt into {@code gpt-5.5}
     * during a staged rollout.
     */
    static List<ModelInfoDTO> addChatGPTForwardCompatModels(List<ModelInfoDTO> input) {
        Set<String> seen = new LinkedHashSet<>();
        List<ModelInfoDTO> out = new ArrayList<>(input.size() + CHATGPT_FORWARD_COMPAT.size());
        for (ModelInfoDTO m : input) {
            if (m.getId() != null && seen.add(m.getId())) out.add(m);
        }
        for (Map.Entry<String, List<String>> e : CHATGPT_FORWARD_COMPAT) {
            String synthetic = e.getKey();
            if (seen.contains(synthetic)) continue;
            if (e.getValue().stream().anyMatch(seen::contains)) {
                seen.add(synthetic);
                out.add(new ModelInfoDTO(synthetic, synthetic));
            }
        }
        return out;
    }

    // ==================== 协议分派：单模型测试 ====================

    private String sendTestPrompt(ModelProviderEntity provider, ModelProtocol protocol, String modelId) {
        return switch (protocol) {
            case OPENAI_COMPATIBLE -> sendOpenAiTestPrompt(provider, modelId);
            case DASHSCOPE_NATIVE -> sendDashScopeTestPrompt(provider, modelId);
            case GEMINI_NATIVE -> sendGeminiTestPrompt(provider, modelId);
            case ANTHROPIC_MESSAGES -> sendAnthropicTestPrompt(provider, modelId);
            case OPENAI_CHATGPT, ANTHROPIC_CLAUDE_CODE ->
                    throw new MateClawException("err.llm.oauth_no_test",
                            "OAuth provider 不支持模型测试");
        };
    }

    private String sendOpenAiTestPrompt(ModelProviderEntity provider, String modelId) {
        String baseUrl = normalizeBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.llm.base_url_missing", "Base URL 未配置");
        }

        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "请回复：连接正常")),
                "max_tokens", 10,
                "temperature", 0
        );

        // 从 generateKwargs 读取 completionsPath（智谱等用 /chat/completions 而非 /v1/chat/completions）
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        String completionsPath = resolveCompletionsPath(baseUrl, kwargs);

        RestClient.RequestHeadersSpec<?> spec = openAiCompatibleClientBuilder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri(completionsPath)
                .body(requestBody);

        if (modelProviderService.hasUsableApiKey(provider.getApiKey())) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey().trim());
        }
        applyCustomHeaders(spec, kwargs);

        String body = spec.retrieve().body(String.class);
        return extractOpenAiChatContent(body);
    }

    /**
     * Test a DashScope model using the **native** endpoint
     * ({@code /api/v1/services/aigc/text-generation/generation}).
     * <p>
     * This matches the protocol Spring AI Alibaba's {@code DashScopeChatModel} uses
     * at runtime. Using compatible-mode for testing (as the previous implementation
     * did) was the root cause of "test passed but chat fails" — compatible-mode
     * accepts a broader set of model names than the native API does.
     */
    private String sendDashScopeTestPrompt(ModelProviderEntity provider, String modelId) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.dashscope_key_missing", "DashScope API Key 未配置");
        }

        // DashScope native request shape: input.messages + parameters
        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "input", Map.of(
                        "messages", List.of(Map.of("role", "user", "content", "ping"))
                ),
                "parameters", Map.of(
                        "max_tokens", 1,
                        "temperature", 0,
                        "result_format", "message"
                )
        );

        String body = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .build()
                .post()
                .uri("/api/v1/services/aigc/text-generation/generation")
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return extractDashScopeNativeContent(body);
    }

    /**
     * Extract content from DashScope native response:
     * {@code { "output": { "choices": [ { "message": { "content": "..." } } ] } } }
     * Falls back to the raw body preview if the shape differs.
     */
    private String extractDashScopeNativeContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("output").path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText("");
                if (!content.isBlank()) return content;
            }
            // Older shape: output.text
            String legacyText = root.path("output").path("text").asText("");
            if (!legacyText.isBlank()) return legacyText;
        } catch (Exception ignored) {}
        return body == null ? "" : (body.length() > 200 ? body.substring(0, 200) : body);
    }

    private String sendGeminiTestPrompt(ModelProviderEntity provider, String modelId) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.gemini_key_missing", "Gemini API Key 未配置");
        }

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", "请回复：连接正常"))
                )),
                "generationConfig", Map.of("maxOutputTokens", 10, "temperature", 0)
        );

        String body = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri("/v1beta/models/{model}:generateContent?key={key}", modelId, apiKey.trim())
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return extractGeminiContent(body);
    }

    private String sendAnthropicTestPrompt(ModelProviderEntity provider, String modelId) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.anthropic_key_missing", "Anthropic API Key 未配置");
        }

        String baseUrl = StringUtils.hasText(provider.getBaseUrl())
                ? normalizeBaseUrl(provider.getBaseUrl())
                : "https://api.anthropic.com";

        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "请回复：连接正常")),
                "max_tokens", 10
        );

        String body = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey.trim())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build()
                .post()
                .uri("/v1/messages")
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return extractAnthropicContent(body);
    }

    // ==================== JSON 解析 ====================

    private List<ModelInfoDTO> parseOpenAiModelsResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return Collections.emptyList();
            }
            List<ModelInfoDTO> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                if (StringUtils.hasText(id)) {
                    models.add(new ModelInfoDTO(id, id));
                }
            }
            return models;
        } catch (Exception e) {
            log.warn("解析 OpenAI 模型列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ModelInfoDTO> parseGeminiModelsResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode models = root.path("models");
            if (!models.isArray()) {
                return Collections.emptyList();
            }
            List<ModelInfoDTO> result = new ArrayList<>();
            for (JsonNode node : models) {
                String name = node.path("name").asText("");
                String displayName = node.path("displayName").asText(name);
                // Gemini 返回 "models/gemini-1.5-pro" 格式，去掉 "models/" 前缀
                if (name.startsWith("models/")) {
                    name = name.substring(7);
                }
                if (StringUtils.hasText(name)) {
                    result.add(new ModelInfoDTO(name, displayName));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析 Gemini 模型列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ModelInfoDTO> parseAnthropicModelsResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return Collections.emptyList();
            }
            List<ModelInfoDTO> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                String displayName = node.path("display_name").asText(id);
                if (StringUtils.hasText(id)) {
                    models.add(new ModelInfoDTO(id, displayName));
                }
            }
            return models;
        } catch (Exception e) {
            log.warn("解析 Anthropic 模型列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String extractOpenAiChatContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("choices").path(0).path("message").path("content").asText("连接正常");
        } catch (Exception e) {
            return "连接正常（响应解析异常）";
        }
    }

    private String extractGeminiContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("连接正常");
        } catch (Exception e) {
            return "连接正常（响应解析异常）";
        }
    }

    private String extractAnthropicContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("content").path(0).path("text").asText("连接正常");
        } catch (Exception e) {
            return "连接正常（响应解析异常）";
        }
    }

    // ==================== 工具方法 ====================

    // Trailing "/v{digits}" segment in a base URL — restricted to numeric major versions,
    // which is the OpenAI-compatible convention (/v1 OpenAI, /v3 Volcano Ark, /v4 Zhipu).
    private static final java.util.regex.Pattern BASE_URL_VERSION_SUFFIX =
            java.util.regex.Pattern.compile(".*/v\\d+$");

    /**
     * Resolve the chat-completions path. An explicit {@code completionsPath} in
     * {@code generateKwargs} is always honored as-is. Otherwise we default to
     * {@code /v1/chat/completions} and dedupe the {@code /v1} prefix when the
     * baseUrl already carries a {@code /v{N}} segment (e.g. Volcano Engine Ark
     * base {@code https://ark.cn-beijing.volces.com/api/v3}).
     */
    private String resolveCompletionsPath(String baseUrl, Map<String, Object> kwargs) {
        if (kwargs != null) {
            Object raw = kwargs.get("completionsPath");
            if (raw instanceof String value && StringUtils.hasText(value)) {
                String path = value.trim();
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
                return path;
            }
        }
        String path = "/v1/chat/completions";
        // If baseUrl already ends with /v{N}, strip the /v1 prefix from the default
        // so we don't end up with /api/v3/v1/chat/completions (404).
        if (baseUrl != null && BASE_URL_VERSION_SUFFIX.matcher(baseUrl).matches() && path.startsWith("/v1/")) {
            path = path.substring(3);
        }
        return path;
    }

    /**
     * Resolve the OpenAI-compatible {@code /v1/models} path against a base URL,
     * stripping the {@code /v1} prefix when the base already carries a {@code /v{N}}
     * suffix (Volcano Engine Ark, etc.).
     */
    private String resolveModelsPath(String baseUrl) {
        String path = "/v1/models";
        if (baseUrl != null && BASE_URL_VERSION_SUFFIX.matcher(baseUrl).matches()) {
            path = "/models";
        }
        return path;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    /**
     * Build a RestClient.Builder pinned to HTTP/1.1 for self-hosted OpenAI-compatible
     * servers. Java's HttpClient defaults to HTTP/2 and over cleartext attempts an
     * H2C upgrade ({@code Upgrade: h2c, Connection: Upgrade, HTTP2-Settings: ...}).
     * Uvicorn-based stacks (vLLM, lmstudio, llama.cpp, ollama) reject the upgrade
     * by closing the socket mid-handshake — surfacing as either
     * "header parser received no bytes" on the chat path or, more subtly, a
     * 400 with body=None on the test path because the body never makes it past
     * the upgrade negotiation.
     */
    private RestClient.Builder openAiCompatibleClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient));
    }

    @SuppressWarnings("unchecked")
    private void applyCustomHeaders(RestClient.RequestHeadersSpec<?> spec, Map<String, Object> kwargs) {
        if (kwargs == null) {
            return;
        }
        Object customHeaders = kwargs.get("customHeaders");
        if (customHeaders instanceof Map) {
            ((Map<String, Object>) customHeaders).forEach((key, value) -> {
                if (value != null) {
                    spec.header(key, value.toString());
                }
            });
        }
    }

    private String extractErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return "未知错误: " + e.getClass().getSimpleName();
        }
        // 截取合理长度
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return msg;
    }
}
