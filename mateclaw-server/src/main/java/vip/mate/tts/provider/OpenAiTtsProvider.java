package vip.mate.tts.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tts.TtsProvider;
import vip.mate.tts.TtsRequest;
import vip.mate.tts.TtsResponseDiagnostics;
import vip.mate.tts.TtsResult;

import java.util.List;

/**
 * OpenAI TTS Provider — 支持 tts-1 / tts-1-hd / gpt-4o-mini-tts
 * <p>
 * 同步模式，直接返回音频流。
 * 复用已有的 OpenAI LLM provider 的 API Key。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiTtsProvider implements TtsProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_MODEL = "tts-1";
    private static final String DEFAULT_VOICE = "alloy";

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public String label() {
        return "OpenAI TTS";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 200;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return modelProviderService.isProviderConfigured("openai");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> availableVoices() {
        return List.of("alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer");
    }

    @Override
    public String defaultVoice() {
        return DEFAULT_VOICE;
    }

    @Override
    public TtsResult synthesize(TtsRequest request, SystemSettingsDTO config) {
        String apiKey = getApiKey();
        String baseUrl = getBaseUrl();
        if (apiKey == null) {
            return TtsResult.failure("OpenAI API Key 未配置");
        }

        try {
            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;
            String voice = request.getVoice() != null && !request.getVoice().isBlank()
                    ? request.getVoice() : DEFAULT_VOICE;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("input", request.getText());
            body.put("voice", voice);
            body.put("response_format", "mp3");
            if (request.getSpeed() != null && request.getSpeed() != 1.0) {
                body.put("speed", request.getSpeed());
            }

            String url = (baseUrl != null ? baseUrl : "https://api.openai.com") + "/v1/audio/speech";

            HttpResponse response = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(60_000)
                    .execute();

            if (response.getStatus() == 200) {
                byte[] audioData = response.bodyBytes();
                log.info("[OpenAI TTS] Synthesized {} bytes (model={}, voice={})", audioData.length, model, voice);
                return TtsResult.success(audioData, "audio/mpeg", "mp3");
            } else {
                String errBody = response.body();
                log.warn("[OpenAI TTS] Failed: HTTP {} - {}", response.getStatus(), errBody);
                return TtsResult.failure(TtsResponseDiagnostics.failureMessage(
                        "OpenAI TTS", url, response.getStatus(), errBody));
            }
        } catch (Exception e) {
            log.error("[OpenAI TTS] Error: {}", e.getMessage(), e);
            return TtsResult.failure("OpenAI TTS 异常: " + e.getMessage());
        }
    }

    private String getApiKey() {
        try {
            return modelProviderService.getProviderConfig("openai").getApiKey();
        } catch (Exception e) {
            return null;
        }
    }

    private String getBaseUrl() {
        try {
            return modelProviderService.getProviderConfig("openai").getBaseUrl();
        } catch (Exception e) {
            return null;
        }
    }
}
