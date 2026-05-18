package vip.mate.llm.chatmodel;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import vip.mate.exception.MateClawException;
import vip.mate.llm.gemini.GeminiChatModel;
import vip.mate.llm.gemini.GeminiNativeClient;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

/**
 * Strategy implementation for {@link ModelProtocol#GEMINI_NATIVE}.
 *
 * <p>Builds a {@link GeminiChatModel} over the native Gemini
 * {@code generateContent} API. The {@link RetryTemplate} is ignored — the
 * native client owns its own HTTP transport, mirroring the ChatGPT Responses
 * builder.</p>
 */
@Component
@RequiredArgsConstructor
public class GeminiChatModelBuilder implements ChatModelBuilder {

    private final GeminiNativeClient geminiNativeClient;
    private final ModelProviderService modelProviderService;

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.GEMINI_NATIVE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        if (provider == null || !modelProviderService.isProviderConfigured(provider.getProviderId())) {
            throw new MateClawException("err.agent.gemini_not_configured",
                    "Gemini Provider 未完成配置，请在模型设置中填写有效的 API Key");
        }
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.gemini_key_invalid",
                    "Gemini API Key 未配置或无效: " + provider.getProviderId());
        }
        Double temperature = model.getTemperature();
        Integer maxTokens = model.getMaxTokens();
        return new GeminiChatModel(geminiNativeClient, provider.getBaseUrl(), apiKey.trim(),
                model.getModelName(), temperature, maxTokens);
    }
}
