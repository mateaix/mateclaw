package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import java.util.List;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 模型配置服务
 */
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final ModelConfigMapper modelConfigMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ModelCapabilityService modelCapabilityService;

    /**
     * Lazy to break circular dependency: ModelProviderService → ModelConfigService.
     * Used only in getDefaultModel() to skip models whose provider is unconfigured.
     */
    @Lazy
    @Autowired
    private ModelProviderService modelProviderService;

    public List<ModelConfigEntity> listModels() {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .orderByDesc(ModelConfigEntity::getIsDefault)
                .orderByAsc(ModelConfigEntity::getProvider)
                .orderByAsc(ModelConfigEntity::getName));
    }

    public List<ModelConfigEntity> listEnabledModels() {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getEnabled, true)
                // 仅 chat 类型（排除 embedding），NULL 兼容老数据
                .and(w -> w.isNull(ModelConfigEntity::getModelType)
                           .or().eq(ModelConfigEntity::getModelType, "chat"))
                .orderByAsc(ModelConfigEntity::getProvider)
                .orderByDesc(ModelConfigEntity::getIsDefault)
                .orderByAsc(ModelConfigEntity::getName));
    }

    public List<ModelConfigEntity> listModelsByProvider(String providerId) {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .orderByDesc(ModelConfigEntity::getBuiltin)
                .orderByAsc(ModelConfigEntity::getName));
    }

    /**
     * 按模型类型筛选（RFC: embedding UI 配置）。
     * <p>
     * modelType 参数：
     * <ul>
     *   <li>{@code "chat"} — 对话模型（默认，包括老数据 modelType IS NULL）</li>
     *   <li>{@code "embedding"} — 文本向量化模型</li>
     * </ul>
     */
    public List<ModelConfigEntity> listByType(String modelType) {
        return listByType(modelType, null);
    }

    /**
     * Optional modality filter (case-insensitive: {@code "vision" / "video" / "audio"}).
     * When non-null, only enabled rows whose resolved capability set contains the
     * requested modality survive — used by the multimodal sidecar settings UI to
     * populate "default vision model" / "default video model" dropdowns.
     */
    public List<ModelConfigEntity> listByType(String modelType, String modality) {
        List<ModelConfigEntity> rows;
        if ("chat".equals(modelType)) {
            rows = modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                    .and(w -> w.isNull(ModelConfigEntity::getModelType)
                               .or().eq(ModelConfigEntity::getModelType, "chat"))
                    .orderByDesc(ModelConfigEntity::getIsDefault)
                    .orderByAsc(ModelConfigEntity::getName));
        } else {
            rows = modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                    .eq(ModelConfigEntity::getModelType, modelType)
                    .orderByDesc(ModelConfigEntity::getIsDefault)
                    .orderByAsc(ModelConfigEntity::getName));
        }
        if (modality == null || modality.isBlank()) return rows;
        ModelCapabilityService.Modality required;
        try {
            required = ModelCapabilityService.Modality.valueOf(modality.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return rows;
        }
        return rows.stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                .filter(m -> modelCapabilityService.supports(m.getModelName(), m.getModalities(), required))
                .toList();
    }

    /**
     * 查找第一个 enabled 的 embedding 模型（WikiEmbeddingService 的 fallback 路径）
     */
    public ModelConfigEntity findFirstEnabledEmbedding() {
        return modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getModelType, "embedding")
                .eq(ModelConfigEntity::getEnabled, true)
                .orderByDesc(ModelConfigEntity::getIsDefault)
                .orderByAsc(ModelConfigEntity::getName)
                .last("LIMIT 1"));
    }

    public ModelConfigEntity getModel(Long id) {
        ModelConfigEntity entity = modelConfigMapper.selectById(id);
        if (entity == null) {
            throw new MateClawException("err.llm.model_config_not_found", "模型配置不存在: " + id);
        }
        return entity;
    }

    public ModelConfigEntity getDefaultModel() {
        // Prefer the explicitly marked default chat model when its provider is configured.
        ModelConfigEntity defaultMarked = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getIsDefault, true)
                .and(w -> w.isNull(ModelConfigEntity::getModelType)
                           .or().eq(ModelConfigEntity::getModelType, "chat"))
                .last("LIMIT 1"));
        if (defaultMarked != null && isProviderEnabledAndConfigured(defaultMarked.getProvider())) {
            return defaultMarked;
        }

        // Default model's provider is unavailable (or no default set) — scan all enabled
        // chat models and pick the first one whose provider is actually configured.
        List<ModelConfigEntity> candidates = modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getEnabled, true)
                .and(w -> w.isNull(ModelConfigEntity::getModelType)
                           .or().eq(ModelConfigEntity::getModelType, "chat"))
                .orderByDesc(ModelConfigEntity::getIsDefault)
                .orderByAsc(ModelConfigEntity::getName));
        for (ModelConfigEntity candidate : candidates) {
            if (isProviderEnabledAndConfigured(candidate.getProvider())) {
                return candidate;
            }
        }

        // No configured provider found — give a clearer error than the generic one.
        if (!candidates.isEmpty()) {
            String unconfiguredProvider = candidates.get(0).getProvider();
            throw new MateClawException("err.llm.no_configured_provider",
                    "所有已启用的模型 Provider 均未完成配置（默认 Provider: " + unconfiguredProvider
                    + "），请在「设置 → 模型」中填写 API Key");
        }
        throw new MateClawException("err.llm.no_available_model", "没有可用的模型配置");
    }

    /**
     * Checks whether a provider is fully configured (API key / credentials present).
     * Delegates to ModelProviderService which is injected lazily to avoid a circular
     * dependency. Falls back to {@code true} when the service is not yet available
     * (e.g., during early bootstrap) so we don't accidentally block startup.
     */
    private boolean isProviderEnabledAndConfigured(String providerId) {
        if (modelProviderService == null || providerId == null) {
            return true;
        }
        try {
            return modelProviderService.isProviderEnabledAndConfigured(providerId);
        } catch (Exception e) {
            return true; // conservative: don't filter if lookup fails
        }
    }

    public ModelConfigEntity getDefaultModelByProvider(String providerId) {
        return modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .eq(ModelConfigEntity::getIsDefault, true)
                .last("LIMIT 1"));
    }

    /**
     * Resolve a provider's primary chat model for routing.
     *
     * <p>Prefers the row carrying the system default flag when it happens to
     * belong to this provider; otherwise falls back to the provider's
     * earliest-configured enabled chat model. The {@code is_default} flag is a
     * single system-wide marker (see {@link #clearDefaultFlag}), so a provider
     * that does not own it has no row matching {@link #getDefaultModelByProvider}.
     * Without this fallback a preferred provider could never contribute a
     * primary model unless it already held the global default.
     *
     * @return the provider's primary chat model, or {@code null} when the
     *         provider has no enabled chat model configured
     */
    public ModelConfigEntity getPrimaryChatModelByProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) return null;
        ModelConfigEntity def = getDefaultModelByProvider(providerId);
        if (def != null) return def;
        return modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .eq(ModelConfigEntity::getEnabled, true)
                .eq(ModelConfigEntity::getModelType, "chat")
                .orderByAsc(ModelConfigEntity::getId)
                .last("LIMIT 1"));
    }

    public ModelConfigEntity createModel(ModelConfigEntity entity) {
        validateModel(entity, null);
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            clearDefaultFlag();
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        if (entity.getBuiltin() == null) {
            entity.setBuiltin(true);
        }
        if (entity.getIsDefault() == null) {
            entity.setIsDefault(false);
        }
        modelConfigMapper.insert(entity);
        ensureDefaultExists();
        publishConfigChanged("model-created");
        return entity;
    }

    public ModelConfigEntity updateModel(ModelConfigEntity entity) {
        ModelConfigEntity existing = getModel(entity.getId());
        validateModel(entity, existing.getId());
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            clearDefaultFlag();
        }
        if (existing.getIsDefault() && Boolean.FALSE.equals(entity.getEnabled())) {
            throw new MateClawException("err.llm.cannot_disable_default", "默认模型不能被禁用，请先切换默认模型");
        }
        modelConfigMapper.updateById(entity);
        ensureDefaultExists();
        publishConfigChanged("model-updated");
        return getModel(entity.getId());
    }

    public void deleteModel(Long id) {
        ModelConfigEntity entity = getModel(id);
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            throw new MateClawException("err.llm.cannot_delete_default", "默认模型不能删除，请先切换默认模型");
        }
        modelConfigMapper.deleteById(id);
        ensureDefaultExists();
        publishConfigChanged("model-deleted");
    }

    public ModelConfigEntity addModelToProvider(String providerId, String modelId, String displayName, boolean builtin) {
        if (!StringUtils.hasText(providerId) || !StringUtils.hasText(modelId)) {
            throw new MateClawException("err.llm.provider_model_required", "Provider 和模型标识不能为空");
        }
        ModelConfigEntity existing = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .eq(ModelConfigEntity::getModelName, modelId)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new MateClawException("err.llm.model_exists", "模型已存在: " + modelId);
        }
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setName(StringUtils.hasText(displayName) ? displayName : modelId);
        entity.setProvider(providerId);
        entity.setModelName(modelId);
        entity.setDescription("");
        entity.setTemperature(0.7);
        entity.setMaxTokens(4096);
        entity.setTopP(0.8);
        entity.setBuiltin(builtin);
        entity.setEnabled(true);
        entity.setIsDefault(false);
        modelConfigMapper.insert(entity);
        ensureDefaultExists();
        publishConfigChanged("provider-model-added");
        return entity;
    }

    public void removeModelFromProvider(String providerId, String modelId) {
        ModelConfigEntity entity = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .eq(ModelConfigEntity::getModelName, modelId)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new MateClawException("err.llm.model_not_found", "模型不存在: " + modelId);
        }
        if (Boolean.TRUE.equals(entity.getBuiltin())) {
            throw new MateClawException("err.llm.builtin_readonly", "内置模型不支持删除");
        }
        deleteModel(entity.getId());
    }

    public void deleteModelsByProvider(String providerId) {
        List<ModelConfigEntity> entities = listModelsByProvider(providerId);
        for (ModelConfigEntity entity : entities) {
            modelConfigMapper.deleteById(entity.getId());
        }
        ensureDefaultExists();
        publishConfigChanged("provider-models-deleted");
    }

    public ModelConfigEntity setDefaultModel(Long id) {
        ModelConfigEntity entity = getModel(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new MateClawException("err.llm.only_enabled_default", "只有启用状态的模型才能设为默认");
        }
        clearDefaultFlag();
        entity.setIsDefault(true);
        modelConfigMapper.updateById(entity);
        publishConfigChanged("default-model-updated");
        return entity;
    }

    public ModelConfigEntity setDefaultModel(String providerId, String modelName) {
        ModelConfigEntity entity = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, providerId)
                .eq(ModelConfigEntity::getModelName, modelName)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new MateClawException("err.llm.model_not_found", "模型不存在: " + providerId + "/" + modelName);
        }
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            // Auto-enable when setting as default (e.g. local Ollama models)
            entity.setEnabled(true);
        }
        clearDefaultFlag();
        entity.setIsDefault(true);
        modelConfigMapper.updateById(entity);
        publishConfigChanged("default-model-updated");
        return entity;
    }

    public ModelConfigEntity resolveModel(String agentModelName) {
        if (StringUtils.hasText(agentModelName)) {
            ModelConfigEntity entity = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                    .eq(ModelConfigEntity::getModelName, agentModelName)
                    .eq(ModelConfigEntity::getEnabled, true)
                    .last("LIMIT 1"));
            if (entity != null) {
                return entity;
            }
        }
        return getDefaultModel();
    }

    /**
     * Resolve an enabled model by its exact (provider, modelName) pair. Unlike
     * {@link #resolveModel(String)} this does NOT fall back to the default —
     * it returns {@code null} when nothing matches, leaving the fallback
     * decision to the caller. Used to honour a per-conversation model pin while
     * still degrading gracefully when that model was later disabled or deleted.
     */
    public ModelConfigEntity findEnabledModel(String provider, String modelName) {
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(modelName)) {
            return null;
        }
        return modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, provider)
                .eq(ModelConfigEntity::getModelName, modelName)
                .eq(ModelConfigEntity::getEnabled, true)
                .last("LIMIT 1"));
    }

    private void validateModel(ModelConfigEntity entity, Long currentId) {
        if (!StringUtils.hasText(entity.getName())) {
            throw new MateClawException("err.llm.name_required", "模型名称不能为空");
        }
        if (!StringUtils.hasText(entity.getProvider())) {
            entity.setProvider("dashscope");
        }
        if (!StringUtils.hasText(entity.getModelName())) {
            throw new MateClawException("err.llm.id_required", "模型标识不能为空");
        }
        ModelConfigEntity duplicate = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getProvider, entity.getProvider())
                .eq(ModelConfigEntity::getModelName, entity.getModelName())
                .eq(ModelConfigEntity::getDeleted, 0)
                .ne(currentId != null, ModelConfigEntity::getId, currentId)
                .last("LIMIT 1"));
        if (duplicate != null) {
            throw new MateClawException("err.llm.id_exists", "模型标识已存在: " + entity.getProvider() + "/" + entity.getModelName());
        }
    }

    private void clearDefaultFlag() {
        List<ModelConfigEntity> defaults = modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getIsDefault, true));
        for (ModelConfigEntity item : defaults) {
            item.setIsDefault(false);
            modelConfigMapper.updateById(item);
        }
    }

    private void ensureDefaultExists() {
        long defaultCount = modelConfigMapper.selectCount(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getIsDefault, true)
                .eq(ModelConfigEntity::getEnabled, true));
        if (defaultCount > 0) {
            return;
        }
        ModelConfigEntity firstEnabled = modelConfigMapper.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getEnabled, true)
                .orderByAsc(ModelConfigEntity::getName)
                .last("LIMIT 1"));
        if (firstEnabled != null) {
            firstEnabled.setIsDefault(true);
            modelConfigMapper.updateById(firstEnabled);
        }
    }

    private void publishConfigChanged(String reason) {
        eventPublisher.publishEvent(new ModelConfigChangedEvent(reason));
    }
}
