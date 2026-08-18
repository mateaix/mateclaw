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
 * DashScope TTS Provider — 使用 CosyVoice（OpenAI 兼容接口）
 * <p>
 * 同步模式，直接返回音频流。
 * 复用已有的 DashScope LLM provider 的 API Key。
 * API 文档: https://help.aliyun.com/zh/model-studio/developer-reference/cosyvoice-openai-compatible
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeTtsProvider implements TtsProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "cosyvoice-v2";
    private static final String DEFAULT_VOICE = "longxiaochun";

    @Override
    public String id() {
        return "dashscope";
    }

    @Override
    public String label() {
        return "DashScope (CosyVoice)";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 150;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return modelProviderService.isProviderConfigured("dashscope");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> availableVoices() {
        return List.of(
                "longxiaochun", "longxiaoxia", "longlaotie", "longshu",
                "longhua", "longshuo", "longjielidou", "longmiao",
                "longyue", "longfei", "longtong", "longxiang"
        );
    }

    @Override
    public String defaultVoice() {
        return DEFAULT_VOICE;
    }

    @Override
    public TtsResult synthesize(TtsRequest request, SystemSettingsDTO config) {
        String apiKey = getDashScopeApiKey();
        if (apiKey == null) {
            return TtsResult.failure("DashScope API Key 未配置");
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

            String endpoint = BASE_URL + "/audio/speech";
            HttpResponse response = HttpRequest.post(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(60_000)
                    .execute();

            if (response.getStatus() == 200) {
                byte[] audioData = response.bodyBytes();
                log.info("[DashScope TTS] Synthesized {} bytes (model={}, voice={})", audioData.length, model, voice);
                return TtsResult.success(audioData, "audio/mpeg", "mp3");
            } else {
                String errBody = response.body();
                log.warn("[DashScope TTS] Failed: HTTP {} - {}", response.getStatus(), errBody);
                return TtsResult.failure(TtsResponseDiagnostics.failureMessage(
                        "DashScope TTS", endpoint, response.getStatus(), errBody));
            }
        } catch (Exception e) {
            log.error("[DashScope TTS] Error: {}", e.getMessage(), e);
            return TtsResult.failure("DashScope TTS 异常: " + e.getMessage());
        }
    }

    private String getDashScopeApiKey() {
        try {
            return modelProviderService.getProviderConfig("dashscope").getApiKey();
        } catch (Exception e) {
            return null;
        }
    }
}
