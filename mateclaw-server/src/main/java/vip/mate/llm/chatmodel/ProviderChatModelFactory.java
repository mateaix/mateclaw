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
    private final LlmStatisticsCollector collector;

    public ProviderChatModelFactory(List<ChatModelBuilder> allBuilders,
                                    ModelProviderService modelProviderService,
                                    LlmStatisticsCollector collector) {
        this.modelProviderService = modelProviderService;
        this.collector = collector;
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
        ModelProviderEntity provider = modelProviderService.getProviderConfig(model.getProvider());
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        ChatModelBuilder builder = builders.get(protocol);
        if (builder == null) {
            throw new MateClawException("err.agent.protocol_limited",
                    "No ChatModelBuilder registered for protocol: " + protocol.getId());
        }
        ChatModel raw = builder.build(model, provider, retry);
        return new LlmStatisticsDecorator(raw, provider.getProviderId(), model.getModelName(), collector);
    }
}
