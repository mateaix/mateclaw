package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.failover.ProviderInitProbe;
import vip.mate.llm.failover.ProviderRequirements;
import vip.mate.llm.model.*;
import vip.mate.llm.repository.ModelProviderMapper;

import org.springframework.ai.chat.model.ChatModel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ModelProviderService {

    /** Provider id whose OAuth token lives on local disk (Keychain / ~/.claude/.credentials.json) instead of the database. */
    private static final String CLAUDE_CODE_PROVIDER_ID = "anthropic-claude-code";

    /**
     * Issue #39: provider id is used as a single path segment in
     * {@code /custom-providers/{providerId}}, {@code /{providerId}/config},
     * {@code /{providerId}/enable}, etc. Spring's PathPatternParser does not
     * match across {@code /}, so any unsafe character makes <em>every</em>
     * such endpoint fall through to the static-resource handler and become
     * undeletable. Reject on the create path.
     *
     * <p>Keep this in sync with {@code PROVIDER_ID_PATTERN} in
     * {@code mateclaw-ui/src/views/Settings/Models/composables/useProviderForm.ts}
     * and the routing fallback in {@code mateclaw-ui/src/api/index.ts}.</p>
     */
    static final java.util.regex.Pattern PROVIDER_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");

    private final ModelProviderMapper modelProviderMapper;
    private final ModelConfigService modelConfigService;
    private final ApplicationEventPublisher eventPublisher;
    /** Lazy provider — avoids forcing the bean to exist in test contexts that don't load the anthropic package. */
    private final ObjectProvider<ClaudeCodeOAuthService> claudeCodeOAuthServiceProvider;
    /** RFC-073: pool / cooldown / probe-completion signals that drive {@link Liveness}. */
    private final AvailableProviderPool providerPool;
    private final ProviderHealthTracker providerHealthTracker;
    /**
     * Lazy provider — {@link ProviderInitProbe} depends on this service, so direct injection
     * would create a startup cycle. The probe always exists at runtime; the indirection only
     * defers Spring's wiring decision past construction.
     */
    private final ObjectProvider<ProviderInitProbe> providerInitProbeProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Plugin-registered ChatModel instances: providerId -> ChatModel */
    private final Map<String, ChatModel> pluginChatModels = new ConcurrentHashMap<>();

    /**
     * Register a ChatModel from a plugin.
     */
    public void registerPluginChatModel(String providerId, ChatModel chatModel) {
        pluginChatModels.put(providerId, chatModel);
    }

    /**
     * Unregister a plugin ChatModel.
     */
    public void unregisterPluginChatModel(String providerId) {
        pluginChatModels.remove(providerId);
    }

    /**
     * Get a plugin-registered ChatModel.
     *
     * @return the ChatModel, or null if not registered by a plugin
     */
    public ChatModel getPluginChatModel(String providerId) {
        return pluginChatModels.get(providerId);
    }

    /** RFC-074: visible providers — only the rows the user has explicitly enabled.
     *  This is what powers the chat dropdown, the failover walker, and the
     *  Settings/Models main grid. Disabled rows live in {@link #listCatalog()}. */
    public List<ProviderInfoDTO> listProviders() {
        return listProvidersInternal(true);
    }

    /** RFC-074: full catalog — enabled and disabled rows alike. Drives the
     *  "Add Provider" drawer where the user opts into a hidden built-in. */
    public List<ProviderInfoDTO> listCatalog() {
        return listProvidersInternal(false);
    }

    private List<ProviderInfoDTO> listProvidersInternal(boolean enabledOnly) {
        LambdaQueryWrapper<ModelProviderEntity> qw = new LambdaQueryWrapper<>();
        if (enabledOnly) {
            qw.eq(ModelProviderEntity::getEnabled, true);
        }
        qw.orderByDesc(ModelProviderEntity::getIsLocal)
          .orderByAsc(ModelProviderEntity::getIsCustom)
          .orderByAsc(ModelProviderEntity::getName);
        List<ModelProviderEntity> providers = modelProviderMapper.selectList(qw);
        Map<String, List<ModelConfigEntity>> modelsByProvider = modelConfigService.listModels().stream()
                .collect(Collectors.groupingBy(ModelConfigEntity::getProvider));
        // RFC-073: batch the runtime snapshots once so each toProviderInfo call is O(1)
        // instead of N pool/tracker round-trips per render.
        LivenessContext liveness = livenessContext();

        return providers.stream()
                .map(provider -> toProviderInfo(provider, modelsByProvider.get(provider.getProviderId()), liveness))
                .toList();
    }

    public ProviderInfoDTO updateProviderConfig(String providerId, ProviderConfigRequest request) {
        ModelProviderEntity provider = getProvider(providerId);
        if (StringUtils.hasText(request.getApiKey())) {
            provider.setApiKey(request.getApiKey().trim());
        }
        provider.setBaseUrl(request.getBaseUrl());
        provider.setChatModel(ModelProtocol.resolveChatModel(request.getProtocol(), request.getChatModel()));
        provider.setGenerateKwargs(writeJson(request.getGenerateKwargs()));
        if (request.getRequireApiKey() != null) {
            provider.setRequireApiKey(request.getRequireApiKey());
        }
        // RFC-009 P3.5: only update fallback priority when the caller explicitly
        // sends a value. null leaves it untouched (existing chain unchanged).
        if (request.getFallbackPriority() != null) {
            int p = Math.max(0, request.getFallbackPriority());
            provider.setFallbackPriority(p);
        }
        modelProviderMapper.updateById(provider);
        tryAutoActivateModel(providerId, provider);
        eventPublisher.publishEvent(new ModelConfigChangedEvent("provider-config-updated"));
        return toProviderInfo(provider, modelConfigService.listModelsByProvider(providerId));
    }

    public ProviderInfoDTO createCustomProvider(CreateCustomProviderRequest request) {
        if (!StringUtils.hasText(request.getId()) || !StringUtils.hasText(request.getName())) {
            throw new MateClawException("err.llm.provider_fields_required", "Provider id 和名称不能为空");
        }
        // Issue #39: even though the UI now validates, defend the API directly —
        // see PROVIDER_ID_PATTERN above for why this matters.
        if (!PROVIDER_ID_PATTERN.matcher(request.getId()).matches()) {
            throw new MateClawException("err.llm.provider_id_invalid",
                    "Provider id 仅允许字母/数字及 . _ -（不允许斜杠或空格），首字符必须是字母或数字，长度 1-64: " + request.getId());
        }
        if (modelProviderMapper.selectById(request.getId()) != null) {
            throw new MateClawException("err.llm.provider_exists", "Provider 已存在: " + request.getId());
        }
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId(request.getId());
        provider.setName(request.getName());
        provider.setApiKeyPrefix(request.getApiKeyPrefix());
        provider.setChatModel(ModelProtocol.resolveChatModel(request.getProtocol(), request.getChatModel()));
        provider.setBaseUrl(request.getDefaultBaseUrl());
        provider.setGenerateKwargs("{}");
        provider.setIsCustom(true);
        provider.setIsLocal(false);
        // RFC-074: custom providers are user-created, so opt them in by default
        // — the user just made the row, no need to make them flip a second toggle.
        provider.setEnabled(true);
        provider.setSupportModelDiscovery(false);
        provider.setSupportConnectionCheck(false);
        provider.setFreezeUrl(false);
        provider.setRequireApiKey(request.getRequireApiKey() == null || Boolean.TRUE.equals(request.getRequireApiKey()));
        modelProviderMapper.insert(provider);

        if (request.getModels() != null) {
            for (ModelInfoDTO model : request.getModels()) {
                modelConfigService.addModelToProvider(request.getId(), model.getId(), model.getName(), false);
            }
        }
        eventPublisher.publishEvent(new ModelConfigChangedEvent("provider-created"));
        return toProviderInfo(provider, modelConfigService.listModelsByProvider(request.getId()));
    }

    public void deleteCustomProvider(String providerId) {
        ModelProviderEntity provider = getProvider(providerId);
        if (!Boolean.TRUE.equals(provider.getIsCustom())) {
            throw new MateClawException("err.llm.provider_builtin_readonly", "内置 Provider 不支持删除");
        }
        modelConfigService.deleteModelsByProvider(providerId);
        modelProviderMapper.deleteById(providerId);
        eventPublisher.publishEvent(new ModelConfigChangedEvent("provider-deleted"));
    }

    public ProviderInfoDTO addModel(String providerId, AddProviderModelRequest request) {
        getProvider(providerId);
        // Defense-in-depth: the manual "Add model" form must apply the same
        // protocol-level safety as auto-discovery — otherwise users can freely
        // type an unknown model id (e.g. "qwen3.6-plus") that DashScope native
        // rejects at runtime with the opaque "[InvalidParameter] url error".
        String modelId = request.getId();
        if (modelId != null && !modelId.isBlank()) {
            ModelDiscoveryService.assertModelIdAcceptable(providerId, this.getProvider(providerId), modelId);
        }
        modelConfigService.addModelToProvider(providerId, modelId, request.getName(), false);
        return toProviderInfo(getProvider(providerId), modelConfigService.listModelsByProvider(providerId));
    }

    public ProviderInfoDTO removeModel(String providerId, String modelId) {
        getProvider(providerId);
        modelConfigService.removeModelFromProvider(providerId, modelId);
        return toProviderInfo(getProvider(providerId), modelConfigService.listModelsByProvider(providerId));
    }

    public ModelProviderEntity getProviderConfig(String providerId) {
        return getProvider(providerId);
    }

    /**
     * RFC-009: ordered list of providers that participate in the multi-model
     * failover chain. Filters by {@code fallback_priority > 0} and sorts
     * ascending, so priority 1 is tried first after the primary model
     * exhausts retries. An empty list disables fallover entirely.
     */
    public List<ModelProviderEntity> listFallbackChain() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ModelProviderEntity> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.gt("fallback_priority", 0);
        qw.orderByAsc("fallback_priority");
        return modelProviderMapper.selectList(qw);
    }

    public boolean isProviderConfigured(String providerId) {
        return isProviderConfigured(getProvider(providerId));
    }

    public boolean isProviderAvailable(String providerId) {
        ModelProviderEntity provider = getProvider(providerId);
        return isProviderEnabledAndConfigured(provider) && hasModels(providerId);
    }

    public String getProviderUnavailableReason(String providerId) {
        ModelProviderEntity provider = getProvider(providerId);
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            return "Provider 未启用";
        }
        if (!isProviderConfigured(provider)) {
            // Issue #81: emit a precise reason based on which row-level fields are
            // missing, rather than the previous protocol-blind heuristic. The new
            // frontend reads suggestedActionHintKey/Args; this string remains for
            // logs and legacy callers.
            ProviderRequirements.Required req = ProviderRequirements.of(provider);
            boolean hasBaseUrl = StringUtils.hasText(provider.getBaseUrl());
            boolean hasApiKey  = hasUsableApiKey(provider.getApiKey());
            if (req.needsBaseUrl() && !hasBaseUrl && req.needsApiKey() && !hasApiKey) {
                return "Provider 未配置 Base URL 和 API Key";
            }
            if (req.needsBaseUrl() && !hasBaseUrl) {
                return "Provider 未配置 Base URL";
            }
            if (req.needsApiKey() && !hasApiKey) {
                return "Provider 未配置 API Key";
            }
            return "Provider 未完成配置";
        }
        if (!hasModels(providerId)) {
            return "Provider 下没有可用模型";
        }
        return null;
    }

    /**
     * RFC-074: flip the {@code enabled} flag for a provider. Enabling republishes
     * {@link ModelConfigChangedEvent} so {@code ProviderInitProbe} re-probes the
     * fresh row; disabling that owns the current default model auto-promotes
     * a replacement so chat doesn't break on the next request.
     *
     * @return an {@link EnableResult} describing whether the default model was
     *         switched and to what — the frontend uses it to fire a toast.
     */
    public EnableResult setEnabled(String providerId, boolean enabled) {
        ModelProviderEntity provider = getProvider(providerId);
        boolean current = Boolean.TRUE.equals(provider.getEnabled());
        if (current == enabled) {
            return EnableResult.unchanged();
        }
        provider.setEnabled(enabled);
        modelProviderMapper.updateById(provider);
        EnableResult result = enabled
                ? EnableResult.unchanged()
                : pickReplacementDefaultIfNeeded(providerId);
        eventPublisher.publishEvent(new ModelConfigChangedEvent(
                enabled ? "provider-enabled" : "provider-disabled"));
        return result;
    }

    /**
     * If the current default model belongs to {@code disabledProviderId}, find
     * the first enabled + configured provider that has at least one model and
     * promote its first model to default. Returns {@link EnableResult#unchanged()}
     * when no swap was needed (or no replacement exists — in that case the
     * default stays broken and the empty-state UI will catch it).
     */
    private EnableResult pickReplacementDefaultIfNeeded(String disabledProviderId) {
        ModelConfigEntity currentDefault;
        try {
            currentDefault = modelConfigService.getDefaultModel();
        } catch (MateClawException e) {
            // No default at all → nothing to switch.
            return EnableResult.unchanged();
        }
        if (!disabledProviderId.equals(currentDefault.getProvider())) {
            return EnableResult.unchanged();
        }
        // Walk enabled providers in DB order, take the first one with a model.
        List<ModelProviderEntity> candidates = modelProviderMapper.selectList(
                new LambdaQueryWrapper<ModelProviderEntity>()
                        .eq(ModelProviderEntity::getEnabled, true)
                        .ne(ModelProviderEntity::getProviderId, disabledProviderId)
                        .orderByDesc(ModelProviderEntity::getIsLocal)
                        .orderByAsc(ModelProviderEntity::getName));
        for (ModelProviderEntity candidate : candidates) {
            if (!isProviderConfigured(candidate)) continue;
            List<ModelConfigEntity> models = modelConfigService.listModelsByProvider(candidate.getProviderId());
            if (models.isEmpty()) continue;
            ModelConfigEntity first = models.get(0);
            modelConfigService.setDefaultModel(candidate.getProviderId(), first.getModelName());
            return EnableResult.switched(candidate.getProviderId(), first.getModelName());
        }
        return EnableResult.unchanged();
    }

    private void tryAutoActivateModel(String providerId, ModelProviderEntity provider) {
        if (!isProviderEnabledAndConfigured(provider)) {
            return;
        }
        List<ModelConfigEntity> providerModels = modelConfigService.listModelsByProvider(providerId);
        if (providerModels.isEmpty()) {
            return;
        }
        boolean shouldAutoActivate = false;
        try {
            ModelConfigEntity currentDefault = modelConfigService.getDefaultModel();
            ModelProviderEntity defaultProvider = modelProviderMapper.selectById(currentDefault.getProvider());
            if (!isProviderEnabledAndConfigured(defaultProvider)) {
                shouldAutoActivate = true;
            }
        } catch (MateClawException e) {
            shouldAutoActivate = true;
        }
        if (shouldAutoActivate) {
            ModelConfigEntity firstModel = providerModels.get(0);
            modelConfigService.setDefaultModel(providerId, firstModel.getModelName());
        }
    }

    /**
     * OAuth/device-code completion updates credentials outside the normal provider
     * config endpoint. Reuse the same default-model promotion logic so a freshly
     * connected OAuth provider is immediately selectable by chat.
     */
    public void activateFirstModelIfDefaultUnavailable(String providerId) {
        ModelProviderEntity provider = getProvider(providerId);
        tryAutoActivateModel(providerId, provider);
    }

    private ModelProviderEntity getProvider(String providerId) {
        ModelProviderEntity provider = modelProviderMapper.selectById(providerId);
        if (provider == null) {
            throw new MateClawException("err.llm.provider_not_found", "Provider 不存在: " + providerId);
        }
        return provider;
    }

    private ProviderInfoDTO toProviderInfo(ModelProviderEntity provider, List<ModelConfigEntity> models) {
        return toProviderInfo(provider, models, livenessContext());
    }

    private ProviderInfoDTO toProviderInfo(ModelProviderEntity provider,
                                           List<ModelConfigEntity> models,
                                           LivenessContext liveness) {
        ProviderInfoDTO dto = new ProviderInfoDTO();
        dto.setId(provider.getProviderId());
        dto.setName(provider.getName());
        dto.setProtocol(ModelProtocol.fromChatModel(provider.getChatModel()).getId());
        dto.setApiKeyPrefix(provider.getApiKeyPrefix());
        dto.setChatModel(provider.getChatModel());
        dto.setIsCustom(Boolean.TRUE.equals(provider.getIsCustom()));
        dto.setIsLocal(Boolean.TRUE.equals(provider.getIsLocal()));
        dto.setSupportModelDiscovery(Boolean.TRUE.equals(provider.getSupportModelDiscovery()));
        dto.setSupportConnectionCheck(Boolean.TRUE.equals(provider.getSupportConnectionCheck()));
        dto.setFreezeUrl(Boolean.TRUE.equals(provider.getFreezeUrl()));
        dto.setRequireApiKey(Boolean.TRUE.equals(provider.getRequireApiKey()));
        boolean configured = isProviderConfigured(provider);
        // RFC-073: `available` retains its boolean meaning ("usable right now") but is now
        // gated on Liveness.LIVE rather than just configuration completeness, so the chat
        // path and the dropdown stop disagreeing about local providers.
        Liveness providerLiveness = computeLiveness(provider, configured, liveness);
        boolean available = providerLiveness == Liveness.LIVE && models != null && !models.isEmpty();
        dto.setConfigured(configured);
        dto.setAvailable(available);
        dto.setLiveness(providerLiveness);
        dto.setEnabled(Boolean.TRUE.equals(provider.getEnabled()));
        applyLivenessDetails(dto, provider.getProviderId(), providerLiveness, liveness);
        dto.setApiKey(maskApiKey(provider.getApiKey()));
        dto.setBaseUrl(provider.getBaseUrl());
        dto.setGenerateKwargs(readJson(provider.getGenerateKwargs()));
        dto.setAuthType(provider.getAuthType() != null ? provider.getAuthType() : "api_key");
        if (CLAUDE_CODE_PROVIDER_ID.equals(provider.getProviderId())) {
            // Claude Code OAuth credentials live on disk (RFC-062), not in the
            // mate_model_provider row. Bypass the column lookup and ask the
            // service directly. Falls back to false if the bean isn't present
            // (e.g. minimal test contexts).
            ClaudeCodeOAuthService svc = claudeCodeOAuthServiceProvider.getIfAvailable();
            if (svc != null) {
                ClaudeCodeOAuthService.OAuthStatus status = svc.getStatus();
                dto.setOauthConnected(status.connected() && !status.expired());
                dto.setOauthExpiresAt(status.expiresAtMs() > 0L ? status.expiresAtMs() : null);
            } else {
                dto.setOauthConnected(false);
                dto.setOauthExpiresAt(null);
            }
        } else {
            dto.setOauthConnected(StringUtils.hasText(provider.getOauthAccessToken()));
            dto.setOauthExpiresAt(provider.getOauthExpiresAt());
        }
        dto.setFallbackPriority(provider.getFallbackPriority() != null ? provider.getFallbackPriority() : 0);
        List<ModelInfoDTO> builtinModels = new ArrayList<>();
        List<ModelInfoDTO> extraModels = new ArrayList<>();
        if (models != null) {
            for (ModelConfigEntity model : models) {
                // RFC-049 PR-1-UI: ModelInfoDTO(id, name) derives supportsReasoningEffort
                // from id via ModelFamily — no extra wiring needed here.
                ModelInfoDTO info = new ModelInfoDTO(model.getModelName(), model.getName());
                if (Boolean.TRUE.equals(model.getBuiltin())) {
                    builtinModels.add(info);
                } else {
                    extraModels.add(info);
                }
            }
        }
        dto.setModels(builtinModels);
        dto.setExtraModels(extraModels);
        applySuggestedAction(dto, provider, providerLiveness);
        return dto;
    }

    /**
     * Issue #81: derive the chat-popup recovery hint from row + liveness, so the
     * frontend can render a precise "next step" instead of a generic
     * "model unavailable" toast. Six fields populated:
     *   - authStatus: CONFIGURED / MISSING / NOT_REQUIRED / OAUTH_PENDING
     *   - baseUrlComplete: null when not applicable, true/false otherwise
     *   - missingFields: comma-joined ("apiKey", "baseUrl") for required-field UX
     *   - suggestedAction: machine-readable next-step key (frontend switches on this)
     *   - suggestedActionHintKey + suggestedActionHintArgs: i18n key/args, no raw text
     */
    private void applySuggestedAction(ProviderInfoDTO dto, ModelProviderEntity provider, Liveness liveness) {
        ProviderRequirements.Required req = ProviderRequirements.of(provider);
        boolean hasBaseUrl = StringUtils.hasText(provider.getBaseUrl());
        boolean hasApiKey  = hasUsableApiKey(provider.getApiKey());
        boolean hasModels  = (dto.getModels() != null && !dto.getModels().isEmpty())
                          || (dto.getExtraModels() != null && !dto.getExtraModels().isEmpty());

        // 1. authStatus
        if ("oauth".equals(provider.getAuthType())) {
            dto.setAuthStatus(Boolean.TRUE.equals(dto.getOauthConnected()) ? "CONFIGURED" : "OAUTH_PENDING");
        } else if (req.needsApiKey()) {
            dto.setAuthStatus(hasApiKey ? "CONFIGURED" : "MISSING");
        } else {
            dto.setAuthStatus("NOT_REQUIRED");
        }

        // 2. baseUrlComplete: null when this provider doesn't need a base URL.
        dto.setBaseUrlComplete(req.needsBaseUrl() ? hasBaseUrl : null);

        // 3. missingFields
        java.util.List<String> missing = new ArrayList<>();
        if (req.needsApiKey()  && !hasApiKey)  missing.add("apiKey");
        if (req.needsBaseUrl() && !hasBaseUrl) missing.add("baseUrl");
        dto.setMissingFields(String.join(",", missing));

        // 4. suggestedAction
        String action;
        if (liveness == Liveness.UNCONFIGURED) {
            if ("oauth".equals(provider.getAuthType())) {
                action = "start_oauth";
            } else if (missing.size() == 1 && missing.get(0).equals("baseUrl")) {
                action = "fill_base_url";
            } else if (missing.size() == 1 && missing.get(0).equals("apiKey")) {
                action = "fill_api_key";
            } else {
                action = "configure_required_fields";
            }
        } else if (liveness == Liveness.REMOVED) {
            action = "reprobe";
        } else if (liveness == Liveness.COOLDOWN) {
            action = "wait_cooldown";
        } else if (liveness == Liveness.UNPROBED) {
            action = "reprobe";
        } else if (liveness == Liveness.LIVE && !hasModels) {
            action = Boolean.TRUE.equals(provider.getSupportModelDiscovery())
                    ? "pull_model"
                    : "configure_required_fields";
        } else {
            action = "none";
        }
        dto.setSuggestedAction(action);

        // 5. hint key + args (NOT raw text). Frontend renders via t(key, args).
        // Only emit hint when it actually applies to the action; suppress for
        // REMOVED / COOLDOWN / UNPROBED to keep the popup clean.
        if ("fill_base_url".equals(action) || "configure_required_fields".equals(action)) {
            dto.setSuggestedActionHintKey(req.hintKey());
            dto.setSuggestedActionHintArgs(req.hintArgs() == null ? new java.util.LinkedHashMap<>()
                                                                  : new java.util.LinkedHashMap<>(req.hintArgs()));
        } else {
            dto.setSuggestedActionHintKey(null);
            dto.setSuggestedActionHintArgs(new java.util.LinkedHashMap<>());
        }
    }

    private boolean hasModels(String providerId) {
        return !modelConfigService.listModelsByProvider(providerId).isEmpty();
    }

    private boolean isProviderConfigured(ModelProviderEntity provider) {
        if (provider == null) {
            return false;
        }

        // OAuth providers store credentials elsewhere (DB column or disk for
        // Claude Code). Resolve them via the OAuth service rather than the
        // base-URL / api-key columns.
        if ("oauth".equals(provider.getAuthType())) {
            if (CLAUDE_CODE_PROVIDER_ID.equals(provider.getProviderId())) {
                ClaudeCodeOAuthService svc = claudeCodeOAuthServiceProvider.getIfAvailable();
                return svc != null && svc.isLoggedIn();
            }
            return StringUtils.hasText(provider.getOauthAccessToken());
        }

        // Issue #81: decide required fields from the provider row, not from the
        // protocol enum. Every OpenAI-compatible provider (cloud or local) shares
        // OPENAI_COMPATIBLE, so a protocol-keyed table cannot tell OpenAI cloud
        // (needs api_key, no base url) apart from llama.cpp local (no api_key,
        // needs base url). Without this, isLocal=true short-circuited to true
        // for llama.cpp regardless of an empty Base URL, hiding the real cause
        // behind a confusing REMOVED state.
        ProviderRequirements.Required req = ProviderRequirements.of(provider);
        if (req.needsApiKey() && !hasUsableApiKey(provider.getApiKey())) {
            return false;
        }
        if (req.needsBaseUrl() && !StringUtils.hasText(provider.getBaseUrl())) {
            return false;
        }
        return true;
    }

    public boolean hasUsableApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return false;
        }
        String normalized = apiKey.trim();
        // Reject masked display values (the UI sends "********" when the user
        // didn't re-type the key) and known placeholder sentinels — without this
        // check, the chat / embedding fallback chain happily forwards the
        // placeholder to the LLM endpoint, which then returns a 401 at request
        // time. "configure-in-admin-ui" is the application.yml default that
        // keeps DashScopeChatAutoConfiguration happy at startup when no env var
        // is set; "your-*-api-key-here" are legacy sentinels from earlier
        // .env.example / application.yml versions.
        return !normalized.contains("*")
                && !"configure-in-admin-ui".equalsIgnoreCase(normalized)
                && !"your-dashscope-api-key-here".equalsIgnoreCase(normalized)
                && !"your-api-key-here".equalsIgnoreCase(normalized);
    }

    public boolean isProviderEnabledAndConfigured(String providerId) {
        return isProviderEnabledAndConfigured(getProvider(providerId));
    }

    private boolean isProviderEnabledAndConfigured(ModelProviderEntity provider) {
        return provider != null
                && Boolean.TRUE.equals(provider.getEnabled())
                && isProviderConfigured(provider);
    }

    public Map<String, Object> readProviderGenerateKwargs(ModelProviderEntity provider) {
        return readJson(provider != null ? provider.getGenerateKwargs() : null);
    }

    private String maskApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "********";
        }
        return apiKey.substring(0, 4) + "********" + apiKey.substring(apiKey.length() - 4);
    }

    private Map<String, Object> readJson(String value) {
        if (!StringUtils.hasText(value)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Collections.emptyMap() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ============================================================
    // RFC-073: Liveness computation
    // ============================================================

    /** Take one snapshot per render-batch so {@link #toProviderInfo} stays O(1) per provider. */
    private LivenessContext livenessContext() {
        return new LivenessContext(providerPool.snapshot(), providerHealthTracker.snapshot(),
                providerInitProbeProvider.getIfAvailable());
    }

    private Liveness computeLiveness(ModelProviderEntity provider, boolean configured, LivenessContext ctx) {
        if (!configured) return Liveness.UNCONFIGURED;
        String id = provider.getProviderId();
        // Probe absent in test contexts → fail-open to LIVE so test fixtures don't trip on UNPROBED.
        if (ctx.initProbe() != null && !ctx.initProbe().hasBeenProbed(id)) {
            return Liveness.UNPROBED;
        }
        AvailableProviderPool.RemovalReason reason = ctx.poolSnapshot().get(id);
        boolean inPool = ctx.poolSnapshot().containsKey(id) && reason == null;
        if (!inPool) return Liveness.REMOVED;
        ProviderHealthTracker.ProviderHealthSnapshot health = ctx.healthSnapshot().get(id);
        if (health != null && health.cooldownRemainingMs() > 0) return Liveness.COOLDOWN;
        return Liveness.LIVE;
    }

    private void applyLivenessDetails(ProviderInfoDTO dto, String providerId,
                                       Liveness liveness, LivenessContext ctx) {
        switch (liveness) {
            case REMOVED -> {
                AvailableProviderPool.RemovalReason reason = ctx.poolSnapshot().get(providerId);
                if (reason != null) {
                    dto.setUnavailableReason(reason.message());
                    dto.setLastProbedAtMs(reason.removedAtMs());
                }
            }
            case COOLDOWN -> {
                ProviderHealthTracker.ProviderHealthSnapshot health = ctx.healthSnapshot().get(providerId);
                if (health != null) {
                    dto.setCooldownRemainingMs(health.cooldownRemainingMs());
                }
                dto.setUnavailableReason("provider in cooldown after consecutive failures");
            }
            default -> { /* LIVE / UNPROBED / UNCONFIGURED — no extra fields */ }
        }
    }

    /** Per-render snapshot of pool / cooldown / probe-completion state. */
    private record LivenessContext(
            Map<String, AvailableProviderPool.RemovalReason> poolSnapshot,
            Map<String, ProviderHealthTracker.ProviderHealthSnapshot> healthSnapshot,
            ProviderInitProbe initProbe) {}
}
