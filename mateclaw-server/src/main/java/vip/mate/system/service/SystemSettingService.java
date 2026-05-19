package vip.mate.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.repository.SystemSettingMapper;

@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private static final String LANGUAGE_KEY = "language";
    private static final String STREAM_ENABLED_KEY = "streamEnabled";
    private static final String DEBUG_MODE_KEY = "debugMode";
    private static final String STATEGRAPH_ENABLED_KEY = "stateGraphEnabled";

    // 搜索服务配置 keys
    private static final String SEARCH_ENABLED_KEY = "searchEnabled";
    private static final String SEARCH_PROVIDER_KEY = "searchProvider";
    private static final String SEARCH_FALLBACK_ENABLED_KEY = "searchFallbackEnabled";
    private static final String SERPER_API_KEY_KEY = "serperApiKey";
    private static final String SERPER_BASE_URL_KEY = "serperBaseUrl";
    private static final String TAVILY_API_KEY_KEY = "tavilyApiKey";
    private static final String TAVILY_BASE_URL_KEY = "tavilyBaseUrl";
    private static final String DUCKDUCKGO_ENABLED_KEY = "duckduckgoEnabled";
    private static final String SEARXNG_BASE_URL_KEY = "searxngBaseUrl";

    // 视频生成配置 keys
    private static final String VIDEO_ENABLED_KEY = "videoEnabled";
    private static final String VIDEO_PROVIDER_KEY = "videoProvider";
    private static final String VIDEO_FALLBACK_ENABLED_KEY = "videoFallbackEnabled";

    // 图片生成配置 keys
    private static final String IMAGE_ENABLED_KEY = "imageEnabled";
    private static final String IMAGE_PROVIDER_KEY = "imageProvider";
    private static final String IMAGE_FALLBACK_ENABLED_KEY = "imageFallbackEnabled";

    // TTS 配置 keys
    private static final String TTS_ENABLED_KEY = "ttsEnabled";
    private static final String TTS_PROVIDER_KEY = "ttsProvider";
    private static final String TTS_FALLBACK_ENABLED_KEY = "ttsFallbackEnabled";
    private static final String TTS_AUTO_MODE_KEY = "ttsAutoMode";
    private static final String TTS_DEFAULT_VOICE_KEY = "ttsDefaultVoice";
    private static final String TTS_SPEED_KEY = "ttsSpeed";

    // STT 配置 keys
    private static final String STT_ENABLED_KEY = "sttEnabled";
    private static final String STT_PROVIDER_KEY = "sttProvider";
    private static final String STT_FALLBACK_ENABLED_KEY = "sttFallbackEnabled";
    // Issue #76: let the OpenAI STT provider point at any OpenAI-compat endpoint.
    private static final String STT_OPENAI_COMPAT_PROVIDER_ID_KEY = "sttOpenAiCompatProviderId";
    private static final String STT_OPENAI_COMPAT_MODEL_KEY = "sttOpenAiCompatModel";

    // 音乐生成配置 keys
    private static final String MUSIC_ENABLED_KEY = "musicEnabled";
    private static final String MUSIC_PROVIDER_KEY = "musicProvider";
    private static final String MUSIC_FALLBACK_ENABLED_KEY = "musicFallbackEnabled";

    // 3D 模型生成配置 keys
    private static final String MODEL3D_ENABLED_KEY = "model3dEnabled";
    private static final String MODEL3D_PROVIDER_KEY = "model3dProvider";
    private static final String MODEL3D_FALLBACK_ENABLED_KEY = "model3dFallbackEnabled";

    // Multimodal sidecar routing keys (id values; references mate_model_config.id)
    private static final String DEFAULT_VISION_MODEL_KEY = "default.vision_model";
    private static final String DEFAULT_VIDEO_MODEL_KEY = "default.video_model";

    private static final String ZHIPU_API_KEY_KEY = "zhipuApiKey";
    private static final String ZHIPU_BASE_URL_KEY = "zhipuBaseUrl";
    private static final String FAL_API_KEY_KEY = "falApiKey";
    private static final String KLING_ACCESS_KEY_KEY = "klingAccessKey";
    private static final String KLING_SECRET_KEY_KEY = "klingSecretKey";
    private static final String RUNWAY_API_KEY_KEY = "runwayApiKey";
    private static final String MINIMAX_API_KEY_KEY = "minimaxApiKey";
    private static final String MINIMAX_REGION_KEY = "minimaxRegion";

    private final SystemSettingMapper systemSettingMapper;

    /**
     * Resolve the SearXNG base URL: DB value takes priority; fall back to the
     * {@code SEARXNG_BASE_URL} environment variable so Docker deployments work
     * out-of-the-box without manual configuration in the UI.
     */
    private String resolveSearxngBaseUrl() {
        String dbValue = getValue(SEARXNG_BASE_URL_KEY, "");
        if (dbValue != null && !dbValue.isBlank()) return dbValue;
        String envValue = System.getenv("SEARXNG_BASE_URL");
        return (envValue != null && !envValue.isBlank()) ? envValue : "";
    }

    public SystemSettingsDTO getSettings() {
        SystemSettingsDTO dto = new SystemSettingsDTO();
        dto.setLanguage(getValue(LANGUAGE_KEY, "zh-CN"));
        dto.setStreamEnabled(Boolean.parseBoolean(getValue(STREAM_ENABLED_KEY, "true")));
        dto.setDebugMode(Boolean.parseBoolean(getValue(DEBUG_MODE_KEY, "false")));
        dto.setStateGraphEnabled(Boolean.parseBoolean(getValue(STATEGRAPH_ENABLED_KEY, "false")));

        // 搜索服务配置
        dto.setSearchEnabled(Boolean.parseBoolean(getValue(SEARCH_ENABLED_KEY, "true")));
        dto.setSearchProvider(getValue(SEARCH_PROVIDER_KEY, "serper"));
        dto.setSearchFallbackEnabled(Boolean.parseBoolean(getValue(SEARCH_FALLBACK_ENABLED_KEY, "false")));
        dto.setSerperBaseUrl(getValue(SERPER_BASE_URL_KEY, "https://google.serper.dev/search"));
        dto.setTavilyBaseUrl(getValue(TAVILY_BASE_URL_KEY, "https://api.tavily.com/search"));
        // Keyless provider 配置
        dto.setDuckduckgoEnabled(Boolean.parseBoolean(getValue(DUCKDUCKGO_ENABLED_KEY, "true")));
        dto.setSearxngBaseUrl(resolveSearxngBaseUrl());
        // API Key 脱敏回显
        dto.setSerperApiKeyMasked(maskApiKey(getValue(SERPER_API_KEY_KEY, "")));
        dto.setTavilyApiKeyMasked(maskApiKey(getValue(TAVILY_API_KEY_KEY, "")));

        // 视频生成配置
        dto.setVideoEnabled(Boolean.parseBoolean(getValue(VIDEO_ENABLED_KEY, "false")));
        dto.setVideoProvider(getValue(VIDEO_PROVIDER_KEY, "auto"));
        dto.setVideoFallbackEnabled(Boolean.parseBoolean(getValue(VIDEO_FALLBACK_ENABLED_KEY, "true")));
        dto.setZhipuBaseUrl(getValue(ZHIPU_BASE_URL_KEY, ""));
        dto.setZhipuApiKeyMasked(maskApiKey(getValue(ZHIPU_API_KEY_KEY, "")));
        dto.setFalApiKeyMasked(maskApiKey(getValue(FAL_API_KEY_KEY, "")));
        dto.setKlingAccessKeyMasked(maskApiKey(getValue(KLING_ACCESS_KEY_KEY, "")));
        dto.setKlingSecretKeyMasked(maskApiKey(getValue(KLING_SECRET_KEY_KEY, "")));
        dto.setRunwayApiKeyMasked(maskApiKey(getValue(RUNWAY_API_KEY_KEY, "")));
        dto.setMinimaxApiKeyMasked(maskApiKey(getValue(MINIMAX_API_KEY_KEY, "")));
        dto.setMinimaxRegion(getValue(MINIMAX_REGION_KEY, "global"));

        // 图片生成配置
        dto.setImageEnabled(Boolean.parseBoolean(getValue(IMAGE_ENABLED_KEY, "false")));
        dto.setImageProvider(getValue(IMAGE_PROVIDER_KEY, "auto"));
        dto.setImageFallbackEnabled(Boolean.parseBoolean(getValue(IMAGE_FALLBACK_ENABLED_KEY, "true")));

        // TTS 配置
        dto.setTtsEnabled(Boolean.parseBoolean(getValue(TTS_ENABLED_KEY, "false")));
        dto.setTtsProvider(getValue(TTS_PROVIDER_KEY, "auto"));
        dto.setTtsFallbackEnabled(Boolean.parseBoolean(getValue(TTS_FALLBACK_ENABLED_KEY, "true")));
        dto.setTtsAutoMode(getValue(TTS_AUTO_MODE_KEY, "off"));
        dto.setTtsDefaultVoice(getValue(TTS_DEFAULT_VOICE_KEY, ""));
        String speedStr = getValue(TTS_SPEED_KEY, "1.0");
        try { dto.setTtsSpeed(Double.parseDouble(speedStr)); } catch (NumberFormatException e) { dto.setTtsSpeed(1.0); }

        // STT 配置
        dto.setSttEnabled(Boolean.parseBoolean(getValue(STT_ENABLED_KEY, "false")));
        dto.setSttProvider(getValue(STT_PROVIDER_KEY, "auto"));
        dto.setSttFallbackEnabled(Boolean.parseBoolean(getValue(STT_FALLBACK_ENABLED_KEY, "true")));
        // Issue #76: default to "openai" so upgrades behave identically to the
        // old hard-coded path; users can swap to any OpenAI-compat provider row.
        dto.setSttOpenAiCompatProviderId(getValue(STT_OPENAI_COMPAT_PROVIDER_ID_KEY, "openai"));
        dto.setSttOpenAiCompatModel(getValue(STT_OPENAI_COMPAT_MODEL_KEY, "whisper-1"));

        // 音乐生成配置
        dto.setMusicEnabled(Boolean.parseBoolean(getValue(MUSIC_ENABLED_KEY, "false")));
        dto.setMusicProvider(getValue(MUSIC_PROVIDER_KEY, "auto"));
        dto.setMusicFallbackEnabled(Boolean.parseBoolean(getValue(MUSIC_FALLBACK_ENABLED_KEY, "true")));

        // 3D 模型生成配置
        dto.setModel3dEnabled(Boolean.parseBoolean(getValue(MODEL3D_ENABLED_KEY, "false")));
        dto.setModel3dProvider(getValue(MODEL3D_PROVIDER_KEY, "auto"));
        dto.setModel3dFallbackEnabled(Boolean.parseBoolean(getValue(MODEL3D_FALLBACK_ENABLED_KEY, "true")));

        // Multimodal sidecar routing — empty string means "not configured"
        dto.setDefaultVisionModelId(parseIdOrNull(getValue(DEFAULT_VISION_MODEL_KEY, "")));
        dto.setDefaultVideoModelId(parseIdOrNull(getValue(DEFAULT_VIDEO_MODEL_KEY, "")));
        return dto;
    }

    private Long parseIdOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取全部配置（内部使用，包含明文 API Key）— 供 VideoGenerationService 等后端服务使用
     */
    public SystemSettingsDTO getAllSettings() {
        SystemSettingsDTO dto = getSettings();
        // 补充搜索明文 Key
        dto.setSerperApiKey(getValue(SERPER_API_KEY_KEY, ""));
        dto.setTavilyApiKey(getValue(TAVILY_API_KEY_KEY, ""));
        // 补充视频明文 Key
        dto.setZhipuApiKey(getValue(ZHIPU_API_KEY_KEY, ""));
        dto.setFalApiKey(getValue(FAL_API_KEY_KEY, ""));
        dto.setKlingAccessKey(getValue(KLING_ACCESS_KEY_KEY, ""));
        dto.setKlingSecretKey(getValue(KLING_SECRET_KEY_KEY, ""));
        dto.setRunwayApiKey(getValue(RUNWAY_API_KEY_KEY, ""));
        dto.setMinimaxApiKey(getValue(MINIMAX_API_KEY_KEY, ""));
        return dto;
    }

    /**
     * 获取搜索配置（内部使用，包含明文 API Key）
     */
    public SystemSettingsDTO getSearchSettings() {
        SystemSettingsDTO dto = new SystemSettingsDTO();
        dto.setSearchEnabled(Boolean.parseBoolean(getValue(SEARCH_ENABLED_KEY, "true")));
        dto.setSearchProvider(getValue(SEARCH_PROVIDER_KEY, "serper"));
        dto.setSearchFallbackEnabled(Boolean.parseBoolean(getValue(SEARCH_FALLBACK_ENABLED_KEY, "false")));
        dto.setSerperApiKey(getValue(SERPER_API_KEY_KEY, ""));
        dto.setSerperBaseUrl(getValue(SERPER_BASE_URL_KEY, "https://google.serper.dev/search"));
        dto.setTavilyApiKey(getValue(TAVILY_API_KEY_KEY, ""));
        dto.setTavilyBaseUrl(getValue(TAVILY_BASE_URL_KEY, "https://api.tavily.com/search"));
        dto.setDuckduckgoEnabled(Boolean.parseBoolean(getValue(DUCKDUCKGO_ENABLED_KEY, "true")));
        dto.setSearxngBaseUrl(resolveSearxngBaseUrl());
        return dto;
    }

    public SystemSettingsDTO saveSettings(SystemSettingsDTO dto) {
        saveValue(LANGUAGE_KEY, dto.getLanguage(), "当前界面语言");
        saveValue(STREAM_ENABLED_KEY, String.valueOf(Boolean.TRUE.equals(dto.getStreamEnabled())), "是否开启流式响应");
        saveValue(DEBUG_MODE_KEY, String.valueOf(Boolean.TRUE.equals(dto.getDebugMode())), "是否开启调试模式");
        saveValue(STATEGRAPH_ENABLED_KEY, String.valueOf(Boolean.TRUE.equals(dto.getStateGraphEnabled())), "启用 StateGraph 架构的 ReAct Agent");

        // 搜索服务配置
        if (dto.getSearchEnabled() != null) {
            saveValue(SEARCH_ENABLED_KEY, String.valueOf(dto.getSearchEnabled()), "是否启用搜索功能");
        }
        if (dto.getSearchProvider() != null) {
            saveValue(SEARCH_PROVIDER_KEY, dto.getSearchProvider(), "搜索服务提供商");
        }
        if (dto.getSearchFallbackEnabled() != null) {
            saveValue(SEARCH_FALLBACK_ENABLED_KEY, String.valueOf(dto.getSearchFallbackEnabled()), "搜索失败时是否回退到备用提供商");
        }
        // API Key 仅在非空时保存（前端不回传明文，避免覆盖为空）
        if (dto.getSerperApiKey() != null && !dto.getSerperApiKey().isBlank()) {
            saveValue(SERPER_API_KEY_KEY, dto.getSerperApiKey(), "Serper API Key");
        }
        if (dto.getSerperBaseUrl() != null) {
            saveValue(SERPER_BASE_URL_KEY, dto.getSerperBaseUrl(), "Serper 接口地址");
        }
        if (dto.getTavilyApiKey() != null && !dto.getTavilyApiKey().isBlank()) {
            saveValue(TAVILY_API_KEY_KEY, dto.getTavilyApiKey(), "Tavily API Key");
        }
        if (dto.getTavilyBaseUrl() != null) {
            saveValue(TAVILY_BASE_URL_KEY, dto.getTavilyBaseUrl(), "Tavily 接口地址");
        }
        // Keyless provider 配置
        if (dto.getDuckduckgoEnabled() != null) {
            saveValue(DUCKDUCKGO_ENABLED_KEY, String.valueOf(dto.getDuckduckgoEnabled()), "DuckDuckGo 免 Key 搜索（零配置兜底）");
        }
        if (dto.getSearxngBaseUrl() != null) {
            saveValue(SEARXNG_BASE_URL_KEY, dto.getSearxngBaseUrl(), "SearXNG 实例地址");
        }

        // 视频生成配置
        if (dto.getVideoEnabled() != null) {
            saveValue(VIDEO_ENABLED_KEY, String.valueOf(dto.getVideoEnabled()), "是否启用视频生成");
        }
        if (dto.getVideoProvider() != null) {
            saveValue(VIDEO_PROVIDER_KEY, dto.getVideoProvider(), "视频生成首选 Provider");
        }
        if (dto.getVideoFallbackEnabled() != null) {
            saveValue(VIDEO_FALLBACK_ENABLED_KEY, String.valueOf(dto.getVideoFallbackEnabled()), "视频 Provider 级 Fallback");
        }
        if (dto.getZhipuApiKey() != null && !dto.getZhipuApiKey().isBlank()) {
            saveValue(ZHIPU_API_KEY_KEY, dto.getZhipuApiKey(), "智谱 CogVideo API Key");
        }
        if (dto.getZhipuBaseUrl() != null) {
            saveValue(ZHIPU_BASE_URL_KEY, dto.getZhipuBaseUrl(), "智谱 API Base URL");
        }
        if (dto.getFalApiKey() != null && !dto.getFalApiKey().isBlank()) {
            saveValue(FAL_API_KEY_KEY, dto.getFalApiKey(), "fal.ai API Key");
        }
        if (dto.getKlingAccessKey() != null && !dto.getKlingAccessKey().isBlank()) {
            saveValue(KLING_ACCESS_KEY_KEY, dto.getKlingAccessKey(), "快手可灵 Access Key");
        }
        if (dto.getKlingSecretKey() != null && !dto.getKlingSecretKey().isBlank()) {
            saveValue(KLING_SECRET_KEY_KEY, dto.getKlingSecretKey(), "快手可灵 Secret Key");
        }
        if (dto.getRunwayApiKey() != null && !dto.getRunwayApiKey().isBlank()) {
            saveValue(RUNWAY_API_KEY_KEY, dto.getRunwayApiKey(), "Runway API Key");
        }
        if (dto.getMinimaxApiKey() != null && !dto.getMinimaxApiKey().isBlank()) {
            saveValue(MINIMAX_API_KEY_KEY, dto.getMinimaxApiKey(), "MiniMax API Key");
        }
        if (dto.getMinimaxRegion() != null && !dto.getMinimaxRegion().isBlank()) {
            saveValue(MINIMAX_REGION_KEY, dto.getMinimaxRegion(), "MiniMax API 区域 (global / cn)");
        }

        // 图片生成配置
        if (dto.getImageEnabled() != null) {
            saveValue(IMAGE_ENABLED_KEY, String.valueOf(dto.getImageEnabled()), "是否启用图片生成");
        }
        if (dto.getImageProvider() != null) {
            saveValue(IMAGE_PROVIDER_KEY, dto.getImageProvider(), "图片生成首选 Provider");
        }
        if (dto.getImageFallbackEnabled() != null) {
            saveValue(IMAGE_FALLBACK_ENABLED_KEY, String.valueOf(dto.getImageFallbackEnabled()), "图片 Provider 级 Fallback");
        }

        // TTS 配置
        if (dto.getTtsEnabled() != null) {
            saveValue(TTS_ENABLED_KEY, String.valueOf(dto.getTtsEnabled()), "是否启用 TTS 语音合成");
        }
        if (dto.getTtsProvider() != null) {
            saveValue(TTS_PROVIDER_KEY, dto.getTtsProvider(), "TTS 首选 Provider");
        }
        if (dto.getTtsFallbackEnabled() != null) {
            saveValue(TTS_FALLBACK_ENABLED_KEY, String.valueOf(dto.getTtsFallbackEnabled()), "TTS Provider 级 Fallback");
        }
        if (dto.getTtsAutoMode() != null) {
            saveValue(TTS_AUTO_MODE_KEY, dto.getTtsAutoMode(), "TTS 自动模式（off/always）");
        }
        if (dto.getTtsDefaultVoice() != null) {
            saveValue(TTS_DEFAULT_VOICE_KEY, dto.getTtsDefaultVoice(), "TTS 默认语音");
        }
        if (dto.getTtsSpeed() != null) {
            saveValue(TTS_SPEED_KEY, String.valueOf(dto.getTtsSpeed()), "TTS 默认语速");
        }

        // STT 配置
        if (dto.getSttEnabled() != null) {
            saveValue(STT_ENABLED_KEY, String.valueOf(dto.getSttEnabled()), "是否启用 STT 语音识别");
        }
        if (dto.getSttProvider() != null) {
            saveValue(STT_PROVIDER_KEY, dto.getSttProvider(), "STT 首选 Provider");
        }
        if (dto.getSttFallbackEnabled() != null) {
            saveValue(STT_FALLBACK_ENABLED_KEY, String.valueOf(dto.getSttFallbackEnabled()), "STT Provider 级 Fallback");
        }
        // Issue #76: persist the OpenAI-compatible STT routing target.
        if (dto.getSttOpenAiCompatProviderId() != null) {
            saveValue(STT_OPENAI_COMPAT_PROVIDER_ID_KEY, dto.getSttOpenAiCompatProviderId(),
                    "OpenAI-compat STT 凭证来源 provider id");
        }
        if (dto.getSttOpenAiCompatModel() != null) {
            saveValue(STT_OPENAI_COMPAT_MODEL_KEY, dto.getSttOpenAiCompatModel(),
                    "OpenAI-compat STT 模型名");
        }

        // 音乐生成配置
        if (dto.getMusicEnabled() != null) {
            saveValue(MUSIC_ENABLED_KEY, String.valueOf(dto.getMusicEnabled()), "是否启用音乐生成");
        }
        if (dto.getMusicProvider() != null) {
            saveValue(MUSIC_PROVIDER_KEY, dto.getMusicProvider(), "音乐生成首选 Provider");
        }
        if (dto.getMusicFallbackEnabled() != null) {
            saveValue(MUSIC_FALLBACK_ENABLED_KEY, String.valueOf(dto.getMusicFallbackEnabled()), "音乐 Provider 级 Fallback");
        }

        // 3D 模型生成配置
        if (dto.getModel3dEnabled() != null) {
            saveValue(MODEL3D_ENABLED_KEY, String.valueOf(dto.getModel3dEnabled()), "是否启用 3D 模型生成");
        }
        if (dto.getModel3dProvider() != null) {
            saveValue(MODEL3D_PROVIDER_KEY, dto.getModel3dProvider(), "3D 模型生成首选 Provider");
        }
        if (dto.getModel3dFallbackEnabled() != null) {
            saveValue(MODEL3D_FALLBACK_ENABLED_KEY, String.valueOf(dto.getModel3dFallbackEnabled()), "3D Provider 级 Fallback");
        }

        // Multimodal sidecar routing — guarded with null check, matching the
        // pattern used for music / 3D / image / video / tts / stt blocks
        // above. The bulk PUT /settings is used by every settings page (System,
        // Music, Video, Image, Stt, Tts, Model3D), each sending a partial
        // payload that omits sidecar fields. Without this guard, saving any
        // unrelated setting would silently write "" into the sidecar keys
        // (Long? defaultVisionModelId deserializes to null when absent), which
        // wiped users' configured vision/video models the moment they touched
        // an unrelated settings page. Explicit clearing via the sidecar UI now
        // routes through {@link #updateSidecarSettings} instead.
        if (dto.getDefaultVisionModelId() != null) {
            saveValue(DEFAULT_VISION_MODEL_KEY,
                    String.valueOf(dto.getDefaultVisionModelId()),
                    "Default vision-capable model id (mate_model_config.id) for sidecar routing");
        }
        if (dto.getDefaultVideoModelId() != null) {
            saveValue(DEFAULT_VIDEO_MODEL_KEY,
                    String.valueOf(dto.getDefaultVideoModelId()),
                    "Default video-capable model id (mate_model_config.id) for sidecar routing");
        }
        return getSettings();
    }

    /**
     * Dedicated update path for the multimodal sidecar configuration.
     * <p>
     * This endpoint is the ONLY place vision/video model ids can be written
     * unconditionally — null is treated as an explicit "clear" and writes
     * an empty string (parse-back returns null). The bulk
     * {@link #saveSettings} now guards both keys with non-null checks so
     * unrelated settings pages can't accidentally clobber sidecar config.
     * <p>
     * Both fields are always written so a single API call can independently
     * assign / clear either modality.
     */
    public SystemSettingsDTO updateSidecarSettings(Long visionModelId, Long videoModelId) {
        saveValue(DEFAULT_VISION_MODEL_KEY,
                visionModelId == null ? "" : String.valueOf(visionModelId),
                "Default vision-capable model id (mate_model_config.id) for sidecar routing");
        saveValue(DEFAULT_VIDEO_MODEL_KEY,
                videoModelId == null ? "" : String.valueOf(videoModelId),
                "Default video-capable model id (mate_model_config.id) for sidecar routing");
        return getSettings();
    }

    public String getLanguage() {
        return getValue(LANGUAGE_KEY, "zh-CN");
    }

    public String saveLanguage(String language) {
        saveValue(LANGUAGE_KEY, language, "当前界面语言");
        return getLanguage();
    }

    public boolean isStateGraphEnabled() {
        return Boolean.parseBoolean(getValue(STATEGRAPH_ENABLED_KEY, "false"));
    }

    /**
     * Read a boolean setting. Returns {@code defaultValue} when the key is
     * absent or stored as a non-boolean string.
     */
    public boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(getValue(key, String.valueOf(defaultValue)));
    }

    /** Persist a boolean setting. */
    public void saveBool(String key, boolean value, String description) {
        saveValue(key, String.valueOf(value), description);
    }

    /**
     * Read a raw string setting. Returns {@code defaultValue} (which may be
     * {@code null}) when the key is absent.
     */
    public String getString(String key, String defaultValue) {
        return getValue(key, defaultValue);
    }

    /** Persist a raw string setting. */
    public void saveString(String key, String value, String description) {
        saveValue(key, value, description);
    }

    private String getValue(String key, String defaultValue) {
        SystemSettingEntity entity = systemSettingMapper.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
                .eq(SystemSettingEntity::getSettingKey, key)
                .last("LIMIT 1"));
        return entity != null && entity.getSettingValue() != null ? entity.getSettingValue() : defaultValue;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    private void saveValue(String key, String value, String description) {
        SystemSettingEntity entity = systemSettingMapper.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
                .eq(SystemSettingEntity::getSettingKey, key)
                .last("LIMIT 1"));
        if (entity == null) {
            entity = new SystemSettingEntity();
            entity.setSettingKey(key);
            entity.setDescription(description);
            entity.setSettingValue(value);
            systemSettingMapper.insert(entity);
            return;
        }
        entity.setSettingValue(value);
        entity.setDescription(description);
        systemSettingMapper.updateById(entity);
    }
}
