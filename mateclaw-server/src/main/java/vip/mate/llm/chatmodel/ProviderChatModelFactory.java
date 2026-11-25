package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routes {@link ChatModel} construction to the appropriate
 * {@link ChatModelBuilder} based on the provider's declared protocol.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>Look up the {@link ModelProviderEntity} for {@code model.getProvider()}</li>
 *   <li>Map its {@code chatModel} string to a {@link ModelProtocol}</li>
 *   <li>Dispatch to the matching {@link ChatModelBuilder}; throw if none registered</li>
 * </ol>
 *
 * <p>This factory is the single seam that {@code AgentGraphBuilder} and
 * {@code ProviderInitProbe} share for building Spring AI clients. Keeping it
 * in the {@code llm} package preserves the dependency direction
 * {@code agent → llm} and lets the failover layer probe providers without
 * pulling in the {@code agent} package.</p>
 */
@Slf4j
@Component
public class ProviderChatModelFactory {

    private final Map<ModelProtocol, ChatModelBuilder> builders;
    private final ModelProviderService modelProviderService;

    public ProviderChatModelFactory(List<ChatModelBuilder> allBuilders,
                                    ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
        Map<ModelProtocol, ChatModelBuilder> map = new EnumMap<>(ModelProtocol.class);
        for (ChatModelBuilder b : allBuilders) {
            ChatModelBuilder previous = map.put(b.supportedProtocol(), b);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two ChatModelBuilders registered for the same protocol "
                                + b.supportedProtocol() + ": "
                                + previous.getClass().getName() + " vs " + b.getClass().getName());
            }
        }
        this.builders = Map.copyOf(map);
        log.info("[ProviderChatModelFactory] registered builders for protocols: {}", builders.keySet());
    }

    /**
     * Build a {@link ChatModel} for the given runtime model, looking up the
     * provider on the fly. Throws {@link MateClawException} when no builder
     * is registered for the resolved protocol — callers should treat this as
     * a configuration error, not a transient failure.
     */
    public ChatModel buildFor(ModelConfigEntity model, RetryTemplate retry) {
        return buildFor(model, retry, false);
    }

    /** Coordinator clients cannot acquire capabilities through provider request options. */
    public ChatModel buildFor(ModelConfigEntity model, RetryTemplate retry, boolean coordinator) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(model.getProvider());
        if (coordinator) {
            String name = model.getModelName() == null ? "" : model.getModelName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("search") || name.startsWith("sonar") || name.contains("deep-research")) {
                throw unsupportedOptions("search-only model: " + model.getModelName());
            }
            ModelConfigEntity scopedModel = new ModelConfigEntity();
            org.springframework.beans.BeanUtils.copyProperties(model, scopedModel);
            scopedModel.setEnableSearch(false);
            ModelProviderEntity scopedProvider = new ModelProviderEntity();
            org.springframework.beans.BeanUtils.copyProperties(provider, scopedProvider);
            Map<String, Object> scopedKwargs = coordinatorKwargs(modelProviderService.readProviderGenerateKwargs(provider));
            scopedKwargs.put("enableSearch", false);
            try {
                scopedProvider.setGenerateKwargs(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(scopedKwargs));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw unsupportedOptions("invalid JSON");
            }
            model = scopedModel;
            provider = scopedProvider;
        }
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        ChatModelBuilder builder = builders.get(protocol);
        if (builder == null) {
            throw new MateClawException("err.agent.protocol_limited",
                    "No ChatModelBuilder registered for protocol: " + protocol.getId());
        }
        return builder.build(model, provider, retry);
    }
    private static MateClawException unsupportedOptions(String option) {
        return new MateClawException("err.team.coordinator_provider_options", 400,
                "Unsupported coordinator provider setting: " + option + ". Use a standard chat model and generation options without hosted tools.");
    }

    // Allow only known generation/transport knobs. Unknown passthrough body keys
    // can introduce hosted tools, so reject them explicitly instead of guessing.
    private static Map<String, Object> coordinatorKwargs(Map<String, Object> kwargs) {
        Map<String, Object> scoped = new java.util.LinkedHashMap<>();
        if (kwargs == null) return scoped;
        kwargs.forEach((key, value) -> {
            if ("enableSearch".equals(key) || "enable_search".equals(key)
                    || "searchStrategy".equals(key) || "search_strategy".equals(key)) return;
            if ("chatOptions".equals(key) || "chat_options".equals(key)) {
                if (!(value instanceof Map<?, ?> nested)) throw unsupportedOptions(key);
                Map<String, Object> typed = new java.util.LinkedHashMap<>();
                nested.forEach((k,v) -> typed.put(String.valueOf(k),v));
                Map<String, Object> safeNested = coordinatorKwargs(typed);
                if (!safeNested.isEmpty()) scoped.put(key, safeNested);
                return;
            }
            if (!java.util.Set.of("temperature", "maxTokens", "max_tokens", "maxCompletionTokens", "max_completion_tokens",
                    "topP", "top_p", "topK", "top_k", "reasoningEffort", "reasoning_effort",
                    "enableThinking", "enable_thinking", "thinkingBudget", "thinking_budget", "seed", "stop",
                    "repetitionPenalty", "repetition_penalty", "responseFormat", "response_format",
                    "headers", "customHeaders", "custom_headers", "completionsPath", "completions_path",
                    "requestTimeoutSeconds", "request_timeout_seconds").contains(key)) {
                throw unsupportedOptions(key);
            }
            scoped.put(key, value);
        });
        return scoped;
    }
}
