package vip.mate.stt.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.stt.AudioMimeTypes;
import vip.mate.stt.SttProvider;
import vip.mate.stt.SttRequest;
import vip.mate.stt.SttResponseDiagnostics;
import vip.mate.stt.SttResult;
import vip.mate.stt.WavPcmExtractor;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope STT Provider — synchronous HTTP recognition via Qwen3-ASR.
 *
 * <p>Recognizes a complete recorded clip with a single
 * {@code POST /compatible-mode/v1/chat/completions} call: the audio travels
 * as a base64 {@code input_audio} content part and the transcript comes back
 * as the assistant message content. One request, one response — no session
 * protocol, no timing constraints.
 *
 * <h2>Why HTTP recognition instead of the realtime WebSocket</h2>
 * An earlier version of this provider replayed the recorded clip through the
 * {@code paraformer-realtime-v2} WebSocket. That endpoint is built for live
 * microphone streams: its server-side VAD assumes audio arrives at wall-clock
 * pace, and a replayed clip that falls outside that contract is silently
 * discarded — the protocol completes cleanly ({@code task-started} →
 * {@code task-finished}) with <b>zero</b> {@code result-generated} events
 * (see issue #580). Pacing the replay with 100ms sleeps per chunk made it
 * work in some environments, but:
 * <ul>
 *   <li>the VAD sensitivity remained — users still hit 0-event failures;</li>
 *   <li>every transcription cost at least the clip's own duration in
 *       wall-clock time (10s of speech ≥ 10s of paced streaming), with a
 *       worker thread parked in {@code Thread.sleep} the whole way;</li>
 *   <li>only canonical 16-bit PCM WAV could be sent, so voice notes from IM
 *       channels (ogg/opus/amr/m4a) always failed over to Whisper.</li>
 * </ul>
 * The synchronous recognition endpoint is the purpose-built API for
 * "recorded clip in, text out": latency is a single round trip regardless of
 * clip length, and it accepts wav/mp3/ogg/opus/m4a/amr/webm and more, which
 * also makes IM-channel voice notes first-class here.
 *
 * <h2>Wire format</h2>
 * OpenAI-compatible chat completion with an audio content part:
 * <pre>{@code
 * {"model":"qwen3-asr-flash",
 *  "messages":[{"role":"user","content":[
 *      {"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,..."}}]}],
 *  "stream":false,
 *  "asr_options":{"language":"zh"}}          // omitted → auto language detection
 * }</pre>
 * Response: standard chat completion; transcript at
 * {@code choices[0].message.content}. Errors arrive as HTTP 4xx/5xx with an
 * {@code error.code} / {@code error.message} body.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeSttProvider implements SttProvider {

    /** OpenAI-compatible chat completions endpoint carrying ASR requests. */
    static final String ASR_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    /** Default recognition model — multilingual, auto language detection. */
    static final String DEFAULT_MODEL = "qwen3-asr-flash";

    /** Overall budget for the single HTTP round trip. */
    static final int HTTP_TIMEOUT_MS = 60_000;

    /** Qwen3-ASR-Flash OpenAI-compatible request limit. */
    static final int MAX_AUDIO_BYTES = 10 * 1024 * 1024;

    /** Reject electrical noise that would otherwise be hallucinated as a filler such as “嗯”. */
    static final int MIN_SPEECH_RMS = 16;

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    @Override public String id() { return "dashscope"; }
    @Override public String label() { return "DashScope (Qwen3 ASR)"; }
    @Override public boolean requiresCredential() { return true; }
    @Override public int autoDetectOrder() { return 150; }

    /**
     * Per-language priority. DashScope's ASR family is the strongest
     * mainstream Chinese STT, so push it ahead of Whisper on zh — see
     * {@link SttProvider} javadoc for the routing rationale.
     */
    @Override
    public int autoDetectOrder(String language) {
        if (language == null) return autoDetectOrder();
        String lang = language.toLowerCase();
        if (lang.startsWith("zh")) return 60;
        return autoDetectOrder();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return modelProviderService.isProviderConfigured("dashscope");
        } catch (Exception e) {
            log.warn("[DashScope STT] availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public SttResult transcribe(SttRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = modelProviderService.getProviderConfig("dashscope").getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return SttResult.failure("DashScope API Key 未配置");
            }
            byte[] audio = request.getAudioData();
            if (audio == null || audio.length == 0) {
                return SttResult.failure("音频为空");
            }
            if (audio.length > MAX_AUDIO_BYTES) {
                return SttResult.failure("音频超过 Qwen3-ASR 10 MB 限制");
            }

            // Silence gate — only for WAV, where we can read PCM directly.
            // "Mic captured nothing" is by far the most common voice-input
            // failure; catching it here yields a precise error instead of an
            // empty transcript from the model. Non-WAV inputs (IM voice
            // notes) skip the gate and go straight to the API.
            double localDurationSeconds = -1;
            if (WavPcmExtractor.isCanonicalWav(audio)) {
                byte[] pcm = WavPcmExtractor.extract(audio);
                int[] peakRms = computePcmPeakRms(pcm);
                int sampleRate = WavPcmExtractor.sampleRate(audio);
                if (sampleRate <= 0) {
                    return SttResult.failure("WAV 采样率无效: " + sampleRate);
                }
                localDurationSeconds = (double) pcm.length / (sampleRate * 2L);
                if (peakRms[1] < MIN_SPEECH_RMS) {
                    log.warn("[DashScope STT] PCM is silent/near-silent (peak={}, rms={}, bytes={}) — check mic permission / frontend recording",
                            peakRms[0], peakRms[1], pcm.length);
                    return SttResult.failure(
                            "音频为静音或音量过低（PCM peak=" + peakRms[0]
                                    + ", rms=" + peakRms[1] + "）— 请检查麦克风权限和输入音量");
                }
                log.debug("[DashScope STT] PCM stats — bytes={} peak={} rms={} duration={}s",
                        pcm.length, peakRms[0], peakRms[1], localDurationSeconds);
            }

            String model = (request.getModel() != null && !request.getModel().isBlank())
                    ? request.getModel() : DEFAULT_MODEL;
            String mimeType = AudioMimeTypes.resolveContentType(
                    request.getFileName(), request.getContentType());
            String body = buildRequestBody(model, audio, mimeType, request.getLanguage());

            HttpResponse response = HttpRequest.post(ASR_ENDPOINT)
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .timeout(HTTP_TIMEOUT_MS)
                    .execute();

            String responseBody = response.body();
            if (response.getStatus() != 200) {
                String error = parseErrorMessage(responseBody);
                if (error.isEmpty()) {
                    // Non-JSON error body (HTML gateway page etc.) — surface
                    // an excerpt so the failure stays diagnosable.
                    error = SttResponseDiagnostics.snippet(responseBody);
                }
                log.warn("[DashScope STT] HTTP {} — {}", response.getStatus(), error);
                return SttResult.failure("DashScope STT 失败: HTTP " + response.getStatus()
                        + (error.isEmpty() ? "" : " — " + error));
            }

            if (!SttResponseDiagnostics.looksLikeJson(responseBody)) {
                // A 200 with a non-JSON body never comes from DashScope itself
                // (the endpoint is hardcoded HTTPS) — it means a proxy or
                // gateway on the way answered instead. Report that precisely
                // rather than letting Jackson throw an opaque parse error.
                String contentType = response.header("Content-Type");
                log.warn("[DashScope STT] HTTP 200 with non-JSON body (Content-Type: {}) — {}",
                        contentType, SttResponseDiagnostics.snippet(responseBody));
                return SttResult.failure("DashScope 返回了非 JSON 响应（HTTP 200，Content-Type: "
                        + contentType + "）—— 通常是本机代理或网关拦截了请求，请检查代理/防火墙设置。响应片段: "
                        + SttResponseDiagnostics.snippet(responseBody));
            }

            String text = parseTranscript(responseBody);
            if (text.isBlank()) {
                return SttResult.failure("DashScope 未返回识别文本，请检查录音内容和输入音量");
            }
            int recognizedSeconds = parseRecognizedSeconds(responseBody);
            if (isSuspiciouslyTruncated(localDurationSeconds, recognizedSeconds)) {
                log.warn("[DashScope STT] decoded duration mismatch — local={}s remote={}s, mimeType={}, bytes={}",
                        localDurationSeconds, recognizedSeconds, mimeType, audio.length);
                return SttResult.failure("DashScope 仅解码了约 " + recognizedSeconds
                        + " 秒音频，但本地录音约 " + Math.round(localDurationSeconds)
                        + " 秒；请检查录音编码或网关是否截断了音频");
            }
            log.info("[DashScope STT] Transcribed {} chars (model={}, mimeType={}, audioBytes={}, localDuration={}s, recognizedDuration={}s)",
                    text.length(), model, mimeType, audio.length, localDurationSeconds, recognizedSeconds);
            return SttResult.success(text);
        } catch (Exception e) {
            log.error("[DashScope STT] Error: {}", e.getMessage(), e);
            return SttResult.failure("DashScope STT 异常: " + e.getMessage());
        }
    }

    /* ====================================================================== */
    /* Wire-format helpers (package-private for unit testing).                 */
    /* ====================================================================== */

    /**
     * Build the recognition request. The audio rides in a
     * MIME-qualified data URI. Qwen3-ASR uses the media type to decode the
     * file; unlike Qwen audio/translation models it does not define a
     * separate {@code input_audio.format} request field.
     */
    String buildRequestBody(String model, byte[] audio, String mimeType, String language) throws Exception {
        Map<String, Object> inputAudio = new LinkedHashMap<>();
        inputAudio.put("data", "data:" + mimeType + ";base64,"
                + Base64.getEncoder().encodeToString(audio));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of(
                        "type", "input_audio",
                        "input_audio", inputAudio)))));
        payload.put("stream", false);

        // Language hint when supplied ("zh", "en", "ja", ...). Strip the
        // locale suffix (zh-CN → zh); omit entirely to let the model
        // auto-detect among its supported languages.
        String hint = stripLocale(language);
        if (hint != null) {
            payload.put("asr_options", Map.of("language", hint));
        }
        return objectMapper.writeValueAsString(payload);
    }

    /** {@code zh-CN → zh}; null/blank → null (auto-detect). */
    static String stripLocale(String language) {
        if (language == null || language.isBlank()) return null;
        String hint = language.toLowerCase();
        int dash = hint.indexOf('-');
        return dash > 0 ? hint.substring(0, dash) : hint;
    }

    /**
     * Extract the transcript from a chat-completion response. Content is
     * normally a plain string; tolerate the content-part array form
     * ({@code [{"text": "..."}]}) that multimodal-capable endpoints may emit.
     */
    String parseTranscript(String json) throws Exception {
        JsonNode content = objectMapper.readTree(json)
                .path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                sb.append(part.path("text").asText(""));
            }
            return sb.toString();
        }
        return "";
    }

    /** Duration decoded by Qwen3-ASR, reported in the response usage object. */
    int parseRecognizedSeconds(String json) {
        if (json == null || json.isBlank()) return -1;
        try {
            return objectMapper.readTree(json).path("usage").path("seconds").asInt(-1);
        } catch (Exception ignored) {
            return -1;
        }
    }

    /**
     * A large local/remote duration mismatch means the API decoded only the
     * beginning of the clip. Do not accept a plausible one-character filler
     * as success in that state; fail so provider fallback and diagnostics run.
     */
    static boolean isSuspiciouslyTruncated(double localSeconds, int recognizedSeconds) {
        return localSeconds >= 3.0 && recognizedSeconds >= 0
                && recognizedSeconds + 1.0 < localSeconds * 0.6;
    }

    /**
     * Pull a human-readable message out of an error body. DashScope's
     * compatible mode wraps errors as {@code {"error":{"code","message"}}};
     * the native surface uses top-level {@code code}/{@code message}.
     * Returns "" when the body isn't parseable JSON.
     */
    String parseErrorMessage(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.has("error") ? root.path("error") : root;
            String code = error.path("code").asText("");
            String message = error.path("message").asText("");
            if (code.isEmpty()) return message;
            return message.isEmpty() ? code : code + " — " + message;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Compute peak (max absolute value) and RMS for 16-bit signed
     * little-endian PCM bytes. Returns {peak, rms} as ints for log-friendly
     * formatting. Both metrics are in raw int16 units (-32768..32767).
     *
     * <p>Reference values for 16-bit PCM at typical recording levels:
     * <ul>
     *   <li>Silence / muted mic: peak ≤ 5, rms ≤ 2</li>
     *   <li>Quiet speech: peak ≈ 1000-5000, rms ≈ 200-1000</li>
     *   <li>Normal speech: peak ≈ 5000-20000, rms ≈ 1000-5000</li>
     *   <li>Loud / close-mic: peak ≈ 20000-32000, rms ≈ 5000-15000</li>
     * </ul>
     */
    static int[] computePcmPeakRms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) {
            return new int[]{0, 0};
        }
        int peak = 0;
        long sumSq = 0;
        int sampleCount = pcm.length / 2;
        for (int i = 0; i < sampleCount; i++) {
            // Little-endian 16-bit signed: low byte first.
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];                  // signed
            int sample = (hi << 8) | lo;
            int abs = Math.abs(sample);
            if (abs > peak) peak = abs;
            sumSq += (long) sample * sample;
        }
        int rms = (int) Math.sqrt((double) sumSq / sampleCount);
        return new int[]{peak, rms};
    }
}
