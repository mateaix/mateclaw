package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.sse.WikiProgressBus;

import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the LLM error classification + the one-hop fallback that
 * {@link WikiProcessingService#callLlmWithResilientRetry} relies on.
 * <p>
 * Both helpers are private — we reach them with reflection rather than
 * promote them to package-private just for testing, because they're
 * deliberately implementation details of the retry loop and shouldn't
 * be reused elsewhere. If the tests get heavy, the right next step is
 * to extract them into a package-level utility class.
 */
class WikiProcessingFallbackTest {

    private ModelConfigService modelConfigService;
    private AgentGraphBuilder agentGraphBuilder;
    private WikiProcessingService service;

    private Method isFatal;
    private Method pickFallback;

    private ModelProviderService modelProviderService;
    private ProviderHealthTracker healthTracker;

    @BeforeEach
    void setUp() throws Exception {
        modelConfigService = mock(ModelConfigService.class);
        agentGraphBuilder = mock(AgentGraphBuilder.class);
        modelProviderService = mock(ModelProviderService.class);
        healthTracker = mock(ProviderHealthTracker.class);

        service = new WikiProcessingService(
                mock(WikiKnowledgeBaseService.class),
                mock(WikiRawMaterialService.class),
                mock(WikiPageService.class),
                mock(WikiChunkService.class),
                mock(WikiEmbeddingService.class),
                new WikiProperties(),
                modelConfigService,
                agentGraphBuilder,
                new ObjectMapper(),
                mock(WikiProgressBus.class),
                mock(WikiCitationService.class),
                mock(ApplicationEventPublisher.class));

        // Inject the optional fields via reflection — Spring would do this
        // post-construction in production, but the test instantiates directly.
        java.lang.reflect.Field mpsField = WikiProcessingService.class.getDeclaredField("modelProviderService");
        mpsField.setAccessible(true);
        mpsField.set(service, modelProviderService);
        java.lang.reflect.Field phtField = WikiProcessingService.class.getDeclaredField("providerHealthTracker");
        phtField.setAccessible(true);
        phtField.set(service, healthTracker);

        isFatal = WikiProcessingService.class.getDeclaredMethod("isFatalModelError", Throwable.class);
        isFatal.setAccessible(true);

        pickFallback = WikiProcessingService.class.getDeclaredMethod("pickFallbackChatModel", Long.class);
        pickFallback.setAccessible(true);
    }

    private static ModelProviderEntity provider(String id, int priority) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setFallbackPriority(priority);
        return p;
    }

    private boolean classify(String message) throws Exception {
        return (boolean) isFatal.invoke(service, new RuntimeException(message));
    }

    private static ModelConfigEntity model(Long id, String provider, String type) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setId(id);
        m.setProvider(provider);
        m.setModelType(type);
        m.setEnabled(true);
        return m;
    }

    // ==================== classification ====================

    @Test
    @DisplayName("Chinese 余额不足 message classifies as fatal — no more 5×retry waste")
    void chineseBalanceClassifiesFatal() throws Exception {
        assertThat(classify("429 - {\"error\":{\"code\":\"1113\",\"message\":\"余额不足或无可用资源包,请充值。\"}}"))
                .isTrue();
    }

    @Test
    @DisplayName("Zhipu code 1113 alone (English wrapping) classifies as fatal")
    void zhipuCode1113ClassifiesFatal() throws Exception {
        assertThat(classify("Provider returned error 429: {\"error\":{\"code\":\"1113\",\"message\":\"x\"}}"))
                .isTrue();
        assertThat(classify("HTTP 429: {\"code\":1113,\"message\":\"out of credit\"}"))
                .isTrue();
    }

    @Test
    @DisplayName("AccountBalanceNotEnough (case-insensitive) classifies as fatal")
    void accountBalanceClassifiesFatal() throws Exception {
        assertThat(classify("AccountBalanceNotEnough: please topup")).isTrue();
        assertThat(classify("accountbalancenotenough: please topup")).isTrue();
        assertThat(classify("balance not enough on this account")).isTrue();
    }

    @Test
    @DisplayName("请充值 alone (without 余额不足) still classifies as fatal")
    void rechargePromptClassifiesFatal() throws Exception {
        assertThat(classify("付款失败：请充值后重试")).isTrue();
    }

    @Test
    @DisplayName("regression: existing English fatal patterns still trip")
    void existingFatalPatternsHold() throws Exception {
        assertThat(classify("HTTP 401 unauthorized")).isTrue();
        assertThat(classify("invalid api key")).isTrue();
        assertThat(classify("model_not_found")).isTrue();
        assertThat(classify("insufficient_quota")).isTrue();
    }

    @Test
    @DisplayName("regression: transient errors remain transient (not fatal)")
    void transientStillTransient() throws Exception {
        assertThat(classify("HTTP 429 too many requests")).isFalse();
        assertThat(classify("Connection reset by peer")).isFalse();
        assertThat(classify("503 Service Unavailable")).isFalse();
        assertThat(classify((String) null)).isFalse();
    }

    @Test
    @DisplayName("UnknownHostException classifies as fatal regardless of message")
    void unknownHostFatal() throws Exception {
        boolean result = (boolean) isFatal.invoke(service, new UnknownHostException("bigmodel.cn"));
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Plain TimeoutException stays transient")
    void timeoutTransient() throws Exception {
        boolean result = (boolean) isFatal.invoke(service, new TimeoutException("read timed out"));
        assertThat(result).isFalse();
    }

    // ==================== fallback selection ====================

    @Test
    @DisplayName("pickFallback returns null when no other models exist")
    void noFallback_returnsNull() throws Exception {
        ModelConfigEntity onlyOne = model(100L, "zhipu-cn", "chat");
        when(modelConfigService.getModel(100L)).thenReturn(onlyOne);
        when(modelConfigService.listEnabledModels()).thenReturn(List.of(onlyOne));

        Object result = pickFallback.invoke(service, 100L);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("pickFallback skips models with the same provider (avoid hitting same wedged account)")
    void skipsSameProvider() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        ModelConfigEntity sibling = model(101L, "zhipu-cn", "chat");
        when(modelConfigService.getModel(100L)).thenReturn(primary);
        when(modelConfigService.listEnabledModels()).thenReturn(List.of(primary, sibling));

        Object result = pickFallback.invoke(service, 100L);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("pickFallback prefers a model from a different provider")
    void picksDifferentProvider() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        ModelConfigEntity alt = model(200L, "dashscope", "chat");
        when(modelConfigService.getModel(100L)).thenReturn(primary);
        when(modelConfigService.listEnabledModels()).thenReturn(List.of(primary, alt));

        ChatModel built = mock(ChatModel.class);
        when(agentGraphBuilder.buildRuntimeChatModel(any(ModelConfigEntity.class), any())).thenReturn(built);

        Object result = pickFallback.invoke(service, 100L);
        assertThat(result).isNotNull();
        // result is the private record ResolvedChatModel(modelId, chatModel)
        Long pickedId = (Long) result.getClass().getDeclaredMethod("modelId").invoke(result);
        ChatModel pickedChatModel = (ChatModel) result.getClass().getDeclaredMethod("chatModel").invoke(result);
        assertThat(pickedId).isEqualTo(200L);
        assertThat(pickedChatModel).isSameAs(built);
    }

    @Test
    @DisplayName("pickFallback skips embedding-type models — they can't serve a chat call")
    void skipsEmbeddingModels() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        ModelConfigEntity embedding = model(200L, "dashscope", "embedding");
        when(modelConfigService.getModel(100L)).thenReturn(primary);
        when(modelConfigService.listEnabledModels()).thenReturn(List.of(primary, embedding));

        Object result = pickFallback.invoke(service, 100L);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("pickFallback accepts models with null modelType (backward compat with old seed rows)")
    void acceptsNullModelType() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        ModelConfigEntity legacy = model(200L, "dashscope", null);
        when(modelConfigService.getModel(100L)).thenReturn(primary);
        when(modelConfigService.listEnabledModels()).thenReturn(List.of(primary, legacy));
        when(agentGraphBuilder.buildRuntimeChatModel(any(ModelConfigEntity.class), any()))
                .thenReturn(mock(ChatModel.class));

        Object result = pickFallback.invoke(service, 100L);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("pickFallback returns null when chat model build throws on every candidate")
    void allCandidatesUnbuildable() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        ModelConfigEntity alt = model(200L, "dashscope", "chat");
        when(modelConfigService.getModel(100L)).thenReturn(primary);
        when(modelConfigService.listEnabledModels()).thenReturn(List.of(primary, alt));
        when(agentGraphBuilder.buildRuntimeChatModel(any(ModelConfigEntity.class), any()))
                .thenThrow(new RuntimeException("provider key missing"));

        Object result = pickFallback.invoke(service, 100L);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("pickFallback handles registry throwing without propagating — returns null")
    void registryThrows_failSoft() throws Exception {
        when(modelConfigService.getModel(100L)).thenThrow(new RuntimeException("db down"));

        Object result = pickFallback.invoke(service, 100L);
        assertThat(result).isNull();
    }

    // ==================== H1: priority-ordered failover ====================

    @Test
    @DisplayName("H1: pickFallback walks listFallbackChain in fallback_priority order, lowest first")
    void priorityOrdered_pickLowestFirst() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        ModelConfigEntity altDash = model(200L, "dashscope", "chat");
        ModelConfigEntity altKimi = model(300L, "kimi", "chat");

        when(modelConfigService.getModel(100L)).thenReturn(primary);
        // Order matters here — the chain returns priority=1 first.
        when(modelProviderService.listFallbackChain()).thenReturn(List.of(
                provider("dashscope", 1),
                provider("kimi", 2)));
        when(modelConfigService.listModelsByProvider("dashscope")).thenReturn(List.of(altDash));
        when(modelConfigService.listModelsByProvider("kimi")).thenReturn(List.of(altKimi));

        ChatModel built = mock(ChatModel.class);
        when(agentGraphBuilder.buildRuntimeChatModel(any(ModelConfigEntity.class), any())).thenReturn(built);

        Object result = pickFallback.invoke(service, 100L);
        Long pickedId = (Long) result.getClass().getDeclaredMethod("modelId").invoke(result);
        assertThat(pickedId).isEqualTo(200L);   // dashscope (priority 1) wins, not kimi
    }

    @Test
    @DisplayName("H1: providers swapped in chain order — confirms ordering, not array index")
    void priorityOrdered_confirmsOrderNotIndex() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        ModelConfigEntity altDash = model(200L, "dashscope", "chat");
        ModelConfigEntity altKimi = model(300L, "kimi", "chat");

        when(modelConfigService.getModel(100L)).thenReturn(primary);
        // Reverse priority: kimi=1 wins, dashscope=2 loses.
        when(modelProviderService.listFallbackChain()).thenReturn(List.of(
                provider("kimi", 1),
                provider("dashscope", 2)));
        when(modelConfigService.listModelsByProvider("kimi")).thenReturn(List.of(altKimi));
        when(modelConfigService.listModelsByProvider("dashscope")).thenReturn(List.of(altDash));

        when(agentGraphBuilder.buildRuntimeChatModel(any(ModelConfigEntity.class), any()))
                .thenReturn(mock(ChatModel.class));

        Object result = pickFallback.invoke(service, 100L);
        Long pickedId = (Long) result.getClass().getDeclaredMethod("modelId").invoke(result);
        assertThat(pickedId).isEqualTo(300L);   // kimi
    }

    @Test
    @DisplayName("H1: failed provider is skipped even when listed in the chain")
    void priorityOrdered_skipsFailedProvider() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        ModelConfigEntity altDash = model(200L, "dashscope", "chat");
        when(modelConfigService.getModel(100L)).thenReturn(primary);

        // The chain still includes zhipu-cn (it's a fallback target for someone
        // else's primary), but our failed provider is zhipu-cn so we skip it.
        when(modelProviderService.listFallbackChain()).thenReturn(List.of(
                provider("zhipu-cn", 1),
                provider("dashscope", 2)));
        when(modelConfigService.listModelsByProvider("dashscope")).thenReturn(List.of(altDash));

        when(agentGraphBuilder.buildRuntimeChatModel(any(ModelConfigEntity.class), any()))
                .thenReturn(mock(ChatModel.class));

        Object result = pickFallback.invoke(service, 100L);
        Long pickedId = (Long) result.getClass().getDeclaredMethod("modelId").invoke(result);
        assertThat(pickedId).isEqualTo(200L);
    }

    @Test
    @DisplayName("H1: empty chain → returns null without falling back to listEnabledModels")
    void priorityOrdered_emptyChainNoLegacy() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        when(modelConfigService.getModel(100L)).thenReturn(primary);
        when(modelProviderService.listFallbackChain()).thenReturn(List.of());

        Object result = pickFallback.invoke(service, 100L);
        assertThat(result).isNull();
    }

    // ==================== H2: cooldown skip ====================

    @Test
    @DisplayName("H2: pickFallback skips a provider currently in ProviderHealthTracker cooldown")
    void cooldownSkipped() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        ModelConfigEntity altDash = model(200L, "dashscope", "chat");
        ModelConfigEntity altKimi = model(300L, "kimi", "chat");

        when(modelConfigService.getModel(100L)).thenReturn(primary);
        when(modelProviderService.listFallbackChain()).thenReturn(List.of(
                provider("dashscope", 1),
                provider("kimi", 2)));
        // dashscope is in cooldown (recent failure elsewhere) — skip it.
        when(healthTracker.isInCooldown("dashscope")).thenReturn(true);
        when(healthTracker.isInCooldown("kimi")).thenReturn(false);
        when(modelConfigService.listModelsByProvider("kimi")).thenReturn(List.of(altKimi));
        when(agentGraphBuilder.buildRuntimeChatModel(any(ModelConfigEntity.class), any()))
                .thenReturn(mock(ChatModel.class));

        Object result = pickFallback.invoke(service, 100L);
        Long pickedId = (Long) result.getClass().getDeclaredMethod("modelId").invoke(result);
        assertThat(pickedId).isEqualTo(300L);   // kimi wins, dashscope skipped despite priority 1
    }

    @Test
    @DisplayName("H2: all providers in cooldown → returns null")
    void cooldownAll_returnsNull() throws Exception {
        ModelConfigEntity primary = model(100L, "zhipu-cn", "chat");
        when(modelConfigService.getModel(100L)).thenReturn(primary);
        when(modelProviderService.listFallbackChain()).thenReturn(List.of(
                provider("dashscope", 1),
                provider("kimi", 2)));
        when(healthTracker.isInCooldown(any())).thenReturn(true);

        Object result = pickFallback.invoke(service, 100L);
        assertThat(result).isNull();
    }
}
