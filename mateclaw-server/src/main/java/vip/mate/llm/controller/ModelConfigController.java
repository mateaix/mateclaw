package vip.mate.llm.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.llm.model.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import vip.mate.llm.embedding.EmbeddingModelFactory;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelDiscoveryService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.repository.SystemSettingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

@Slf4j
@Tag(name = "模型配置管理")
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;
    private final ModelProviderService modelProviderService;
    private final ModelDiscoveryService modelDiscoveryService;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final SystemSettingMapper systemSettingMapper;

    private static final String SYSTEM_SETTING_DEFAULT_EMBEDDING_ID = "embedding.default.model.id";

    @Operation(summary = "获取 Provider 列表（仅 enabled）")
    @GetMapping
    @RequireGlobalAdmin
    public R<List<ProviderInfoDTO>> list() {
        return R.ok(modelProviderService.listProviders());
    }

    @Operation(summary = "RFC-074: 获取 Provider 全量目录（含未启用），供 Add Provider 抽屉使用")
    @GetMapping("/catalog")
    @RequireGlobalAdmin
    public R<List<ProviderInfoDTO>> catalog() {
        return R.ok(modelProviderService.listCatalog());
    }

    @Operation(summary = "RFC-074: 启用 Provider")
    @PostMapping("/{providerId}/enable")
    @RequireGlobalAdmin
    public R<EnableResult> enableProvider(@PathVariable String providerId) {
        return R.ok(modelProviderService.setEnabled(providerId, true));
    }

    @Operation(summary = "RFC-074: 禁用 Provider（如其下模型为当前默认会自动切换）")
    @PostMapping("/{providerId}/disable")
    @RequireGlobalAdmin
    public R<EnableResult> disableProvider(@PathVariable String providerId) {
        return R.ok(modelProviderService.setEnabled(providerId, false));
    }

    // Viewers need to know which models are available + which one is active so
    // the chat runtime can render correctly. None of these endpoints return
    // API keys or base URLs — those live on ProviderInfoDTO (GET /models)
    // which stays admin-only.

    @Operation(summary = "获取启用模型列表")
    @GetMapping("/enabled")
    @RequireWorkspaceRole("viewer")
    public R<List<ModelConfigEntity>> listEnabled() {
        return R.ok(modelConfigService.listEnabledModels());
    }

    @Operation(summary = "获取默认模型")
    @GetMapping("/default")
    @RequireWorkspaceRole("viewer")
    public R<ModelConfigEntity> getDefaultModel() {
        return R.ok(modelConfigService.getDefaultModel());
    }

    @Operation(summary = "获取当前激活模型")
    @GetMapping("/active")
    @RequireWorkspaceRole("viewer")
    public R<ActiveModelsInfo> getActiveModel() {
        ModelConfigEntity model = modelConfigService.getDefaultModel();
        ActiveModelsInfo info = new ActiveModelsInfo();
        info.setActiveLlm(new ModelSlotConfig(model.getProvider(), model.getModelName()));
        return R.ok(info);
    }

    @Operation(summary = "设置当前激活模型")
    @PutMapping("/active")
    @RequireGlobalAdmin
    public R<ActiveModelsInfo> setActiveModel(@RequestBody ModelSlotRequest request) {
        ModelConfigEntity model = modelConfigService.setDefaultModel(request.getProviderId(), request.getModel());
        ActiveModelsInfo info = new ActiveModelsInfo();
        info.setActiveLlm(new ModelSlotConfig(model.getProvider(), model.getModelName()));
        return R.ok(info);
    }

    @Operation(summary = "更新 Provider 配置")
    @PutMapping("/{providerId}/config")
    @RequireGlobalAdmin
    public R<ProviderInfoDTO> updateProviderConfig(@PathVariable String providerId,
                                                   @RequestBody ProviderConfigRequest request) {
        ProviderInfoDTO updated = modelProviderService.updateProviderConfig(providerId, request);
        // Provider 的 apiKey/baseUrl 变化时，清空 embedding factory 的缓存，确保下次用新凭证
        embeddingModelFactory.evictAll();
        return R.ok(updated);
    }

    @Operation(summary = "创建自定义 Provider")
    @PostMapping("/custom-providers")
    @RequireGlobalAdmin
    public R<ProviderInfoDTO> createCustomProvider(@RequestBody CreateCustomProviderRequest request) {
        return R.ok(modelProviderService.createCustomProvider(request));
    }

    @Operation(summary = "删除自定义 Provider")
    @DeleteMapping("/custom-providers/{providerId}")
    @RequireGlobalAdmin
    public R<Void> deleteCustomProvider(@PathVariable String providerId) {
        modelProviderService.deleteCustomProvider(providerId);
        return R.ok();
    }

    /**
     * Issue #39 fallback: query-param variant for provider IDs that cannot be
     * expressed as a single path segment (slashes, spaces, etc.). The path
     * variant above is the primary entry point — this exists so users with
     * already-persisted invalid IDs can still clean up their data, since
     * Spring's {@code {providerId}} doesn't match across {@code /} and the
     * dispatcher would otherwise fall through to the static-resource handler.
     */
    @Operation(summary = "删除自定义 Provider（查询参数变体，兼容含特殊字符的旧 ID）")
    @DeleteMapping("/custom-providers")
    @RequireGlobalAdmin
    public R<Void> deleteCustomProviderByQuery(@RequestParam("providerId") String providerId) {
        modelProviderService.deleteCustomProvider(providerId);
        return R.ok();
    }

    @Operation(summary = "向 Provider 添加模型")
    @PostMapping("/{providerId}/models")
    @RequireGlobalAdmin
    public R<ProviderInfoDTO> addProviderModel(@PathVariable String providerId,
                                               @RequestBody AddProviderModelRequest request) {
        return R.ok(modelProviderService.addModel(providerId, request));
    }

    @Operation(summary = "从 Provider 删除模型")
    @DeleteMapping("/{providerId}/models")
    @RequireGlobalAdmin
    public R<ProviderInfoDTO> removeProviderModel(@PathVariable String providerId,
                                                  @RequestParam String modelId) {
        return R.ok(modelProviderService.removeModel(providerId, modelId));
    }

    @Operation(summary = "获取模型详情")
    @GetMapping("/{id}")
    @RequireGlobalAdmin
    public R<ModelConfigEntity> get(@PathVariable Long id) {
        return R.ok(modelConfigService.getModel(id));
    }

    @Operation(summary = "创建模型")
    @PostMapping
    @RequireGlobalAdmin
    public R<ModelConfigEntity> create(@RequestBody ModelConfigEntity entity) {
        return R.ok(modelConfigService.createModel(entity));
    }

    @Operation(summary = "更新模型")
    @PutMapping("/{id}")
    @RequireGlobalAdmin
    public R<ModelConfigEntity> update(@PathVariable Long id, @RequestBody ModelConfigEntity entity) {
        entity.setId(id);
        return R.ok(modelConfigService.updateModel(entity));
    }

    @Operation(summary = "删除模型")
    @DeleteMapping("/{id}")
    @RequireGlobalAdmin
    public R<Void> delete(@PathVariable Long id) {
        modelConfigService.deleteModel(id);
        return R.ok();
    }

    @Operation(summary = "设置默认模型")
    @PostMapping("/{id}/default")
    @RequireGlobalAdmin
    public R<ModelConfigEntity> setDefault(@PathVariable Long id) {
        return R.ok(modelConfigService.setDefaultModel(id));
    }

    // ==================== 模型发现与连接测试 ====================

    @Operation(summary = "发现远端模型")
    @PostMapping("/{providerId}/discover")
    @RequireGlobalAdmin
    public R<DiscoverResult> discoverModels(@PathVariable String providerId) {
        return R.ok(modelDiscoveryService.discoverModels(providerId));
    }

    @Operation(summary = "批量添加发现的模型")
    @PostMapping("/{providerId}/discover/apply")
    @RequireGlobalAdmin
    public R<Map<String, Integer>> applyDiscoveredModels(@PathVariable String providerId,
                                                          @RequestBody ApplyDiscoveredModelsRequest request) {
        int added = modelDiscoveryService.batchAddModels(providerId, request.getModelIds());
        return R.ok(Map.of("added", added));
    }

    @Operation(summary = "测试供应商连接")
    @PostMapping("/{providerId}/test-connection")
    @RequireGlobalAdmin
    public R<TestResult> testConnection(@PathVariable String providerId) {
        return R.ok(modelDiscoveryService.testConnection(providerId));
    }

    @Operation(summary = "测试单个模型可用性")
    @PostMapping("/{providerId}/models/test")
    @RequireGlobalAdmin
    public R<TestResult> testModel(@PathVariable String providerId,
                                   @RequestParam String modelId) {
        return R.ok(modelDiscoveryService.testModel(providerId, modelId));
    }

    // ==================== Embedding 模型管理 ====================

    @Operation(summary = "按类型筛选模型（chat / embedding），可选 modality 过滤")
    @GetMapping("/by-type")
    @RequireWorkspaceRole("member")
    public R<List<ModelConfigEntity>> listByType(
            @RequestParam(defaultValue = "chat") String modelType,
            @RequestParam(required = false) String modality) {
        return R.ok(modelConfigService.listByType(modelType, modality));
    }

    @Operation(summary = "测试 Embedding 模型连通性（嵌入一个短文本验证 API key）")
    @PostMapping("/embedding/{modelId}/test")
    @RequireGlobalAdmin
    public R<Map<String, Object>> testEmbedding(@PathVariable Long modelId) {
        Map<String, Object> result = new HashMap<>();
        try {
            ModelConfigEntity config = modelConfigService.getModel(modelId);
            if (!"embedding".equals(config.getModelType())) {
                result.put("success", false);
                result.put("message", "模型类型不是 embedding: " + config.getModelType());
                return R.ok(result);
            }
            // 清除缓存，确保本次测试用最新的 API key
            embeddingModelFactory.evict(modelId);
            EmbeddingModel model = embeddingModelFactory.build(config);
            EmbeddingResponse resp = model.call(new EmbeddingRequest(List.of("test"), null));
            float[] vec = resp.getResults().get(0).getOutput();
            result.put("success", true);
            result.put("dimensions", vec.length);
            result.put("model", config.getModelName());
            result.put("message", "连通性测试成功");
        } catch (Exception e) {
            // log.warn (not error) — connectivity test failures are user-input /
            // upstream-API problems, not system-side incidents. The stack trace
            // is still attached so operators can diagnose, but it stops firing
            // monitoring alerts that key off ERROR-level events.
            log.warn("[EmbeddingTest] modelId={} test failed", modelId, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return R.ok(result);
    }

    @Operation(summary = "获取系统默认 Embedding 模型 ID")
    @GetMapping("/embedding/default")
    @RequireGlobalAdmin
    public R<Map<String, Object>> getDefaultEmbedding() {
        SystemSettingEntity entity = systemSettingMapper.selectOne(
                new LambdaQueryWrapper<SystemSettingEntity>()
                        .eq(SystemSettingEntity::getSettingKey, SYSTEM_SETTING_DEFAULT_EMBEDDING_ID)
                        .last("LIMIT 1"));
        if (entity == null || entity.getSettingValue() == null || entity.getSettingValue().isBlank()) {
            return R.ok(Map.of("defaultModelId", ""));
        }
        return R.ok(Map.of("defaultModelId", entity.getSettingValue()));
    }

    @Operation(summary = "设置系统默认 Embedding 模型")
    @PostMapping("/embedding/default")
    @RequireGlobalAdmin
    public R<Void> setDefaultEmbedding(@RequestBody Map<String, Object> body) {
        Object v = body.get("modelId");
        String value = v == null ? "" : v.toString();

        SystemSettingEntity existing = systemSettingMapper.selectOne(
                new LambdaQueryWrapper<SystemSettingEntity>()
                        .eq(SystemSettingEntity::getSettingKey, SYSTEM_SETTING_DEFAULT_EMBEDDING_ID)
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setSettingValue(value);
            systemSettingMapper.updateById(existing);
        } else {
            SystemSettingEntity fresh = new SystemSettingEntity();
            fresh.setSettingKey(SYSTEM_SETTING_DEFAULT_EMBEDDING_ID);
            fresh.setSettingValue(value);
            fresh.setDescription("Default embedding model id for wiki semantic search");
            systemSettingMapper.insert(fresh);
        }
        return R.ok();
    }
}
