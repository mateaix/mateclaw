package vip.mate.system.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SystemSettingsDTO {
    private String language;
    private Boolean streamEnabled;
    private Boolean debugMode;
    private Boolean stateGraphEnabled;

    // ===== 搜索服务配置 =====
    private Boolean searchEnabled;
    /** serper / tavily */
    private String searchProvider;
    private Boolean searchFallbackEnabled;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String serperApiKey;
    private String serperBaseUrl;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String tavilyApiKey;
    private String tavilyBaseUrl;

    // ===== Keyless 搜索 provider 配置 =====
    /** DuckDuckGo 是否启用（默认 true，作为零配置兜底） */
    private Boolean duckduckgoEnabled;
    /** SearXNG 实例地址（如 http://searxng:8080），为空则不使用 */
    private String searxngBaseUrl;

    // 用于前端回显脱敏后的 API Key
    private String serperApiKeyMasked;
    private String tavilyApiKeyMasked;

    // ===== WeChat Official Account (公众号) publish credentials =====
    /** 公众号 AppID (plain — not sensitive). */
    private String weixinoaAppId;
    /** 公众号 AppSecret — write-only from the client; never echoed in plaintext. */
    private String weixinoaAppSecret;
    /** Masked AppSecret for display. */
    private String weixinoaAppSecretMasked;

    // ===== 视频生成配置 =====
    /** 是否启用视频生成能力 */
    private Boolean videoEnabled;
    /** 首选视频 provider: auto / dashscope / zhipu-cogvideo / fal / kling */
    private String videoProvider;
    /** 是否启用 provider 级 fallback */
    private Boolean videoFallbackEnabled;

    // --- 智谱 CogVideo ---
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String zhipuApiKey;
    private String zhipuBaseUrl;
    private String zhipuApiKeyMasked;

    // --- fal.ai ---
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String falApiKey;
    private String falApiKeyMasked;

    // --- 快手可灵 Kling ---
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String klingAccessKey;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String klingSecretKey;
    private String klingAccessKeyMasked;
    private String klingSecretKeyMasked;

    // --- Runway ---
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String runwayApiKey;
    private String runwayApiKeyMasked;

    // --- MiniMax (Hailuo) ---
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String minimaxApiKey;
    private String minimaxApiKeyMasked;
    /**
     * MiniMax API region — selects which host to call. Shared by image + video
     * providers because the API key is the same across both:
     * <ul>
     *   <li>{@code "global"} (default) → {@code https://api.minimax.io}</li>
     *   <li>{@code "cn"} → {@code https://api.minimaxi.com} (lower latency from
     *       mainland China; required for accounts registered there).</li>
     * </ul>
     */
    private String minimaxRegion;

    // ===== 图片生成配置 =====
    /** 是否启用图片生成能力 */
    private Boolean imageEnabled;
    /** 首选图片 provider: auto / dashscope / openai / fal / zhipu-cogview */
    private String imageProvider;
    /** 是否启用 provider 级 fallback */
    private Boolean imageFallbackEnabled;

    // ===== TTS 语音合成配置 =====
    /** 是否启用 TTS */
    private Boolean ttsEnabled;
    /** 首选 TTS provider: auto / edge-tts / openai / dashscope */
    private String ttsProvider;
    /** 是否启用 provider 级 fallback */
    private Boolean ttsFallbackEnabled;
    /** 自动 TTS 模式: off / always */
    private String ttsAutoMode;
    /** 默认语音 */
    private String ttsDefaultVoice;
    /** 默认语速 0.5-2.0 */
    private Double ttsSpeed;

    // ===== STT 语音识别配置 =====
    private Boolean sttEnabled;
    /** 首选 STT provider: auto / openai / dashscope */
    private String sttProvider;
    private Boolean sttFallbackEnabled;
    /**
     * Issue #76: which {@code mate_model_provider} row should the OpenAI STT
     * provider read its baseUrl + apiKey from. Defaults to "openai" so existing
     * deployments keep working; swap to a custom OpenAI-compatible provider row
     * (FunASR / SiliconFlow / Groq / Together / Volcano / etc.) to point STT
     * at any compatible endpoint without a code change.
     */
    private String sttOpenAiCompatProviderId;
    /**
     * Issue #76: model id sent in the multipart "model" field. Defaults to
     * whisper-1; override with paraformer-large / FunAudioLLM-Whisper / etc.
     * when the configured provider exposes a different identifier.
     */
    private String sttOpenAiCompatModel;

    // ===== 音乐生成配置 =====
    private Boolean musicEnabled;
    /** 首选音乐 provider: auto / google-lyria / minimax */
    private String musicProvider;
    private Boolean musicFallbackEnabled;

    // ===== 3D 模型生成配置 =====
    private Boolean model3dEnabled;
    /** 首选 3D provider: auto / hunyuan-3d */
    private String model3dProvider;
    private Boolean model3dFallbackEnabled;

    // ===== Multimodal sidecar routing =====
    /**
     * Default vision-capable model id used to caption image attachments when the
     * agent's primary model lacks the VISION modality. References mate_model_config.id;
     * provider+model_name pairs are not unique so we store the surrogate key.
     * null / non-existent / disabled rows are treated as "not configured" — the
     * runtime then leaves the attachment out and asks the user to pick a model.
     */
    private Long defaultVisionModelId;

    /**
     * Default video-capable model id used when the agent's primary model lacks
     * the VIDEO modality. Same semantics as defaultVisionModelId. v1 routing does
     * not yet implement video sidecar; this is reserved for the next iteration so
     * the configuration surface is stable.
     */
    private Long defaultVideoModelId;
}
