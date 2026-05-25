package vip.mate.tool.image.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.*;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Google Gemini native image provider — "Nano Banana".
 *
 * <p>Calls the Gemini {@code generateContent} endpoint with
 * {@code responseModalities:[TEXT,IMAGE]} and returns the inline base64 image
 * as a {@code data:} URI. Supports both text-to-image and image editing /
 * image-to-image: reference images from {@link ImageGenerationRequest#getInputImages()}
 * are sent as {@code inlineData} parts alongside the prompt.
 *
 * <p>Default model is Nano Banana Pro ({@code gemini-3-pro-image-preview}); the
 * original Nano Banana ({@code gemini-2.5-flash-image}) is also available.
 * Reuses the {@code gemini} LLM provider's API key — no separate credential.
 *
 * <p>API: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleImagenProvider implements ImageGenerationProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";
    /** Nano Banana Pro — Gemini 3 Pro image generation. */
    private static final String DEFAULT_MODEL = "gemini-3-pro-image-preview";
    /** LLM provider id whose API key this image provider reuses. */
    private static final String LLM_PROVIDER_ID = "gemini";

    @Override
    public String id() {
        return "google-imagen";
    }

    @Override
    public String label() {
        return "Google Gemini Image (Nano Banana)";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 250;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_EDIT);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(List.of("1024x1024", "1024x1536", "1536x1024", "1024x1792", "1792x1024"))
                .aspectRatios(List.of("1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9"))
                .maxCount(1)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("gemini-3-pro-image-preview", "gemini-2.5-flash-image"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return modelProviderService.isProviderConfigured(LLM_PROVIDER_ID);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = getApiKey();
            if (apiKey == null) {
                return ImageSubmitResult.failure(id(), "Gemini API Key 未配置");
            }

            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;

            ObjectNode body = objectMapper.createObjectNode();

            // contents — one user turn holding the prompt text plus any reference images.
            ArrayNode contents = body.putArray("contents");
            ObjectNode content = contents.addObject();
            content.put("role", "user");
            ArrayNode parts = content.putArray("parts");

            if (request.getPrompt() != null && !request.getPrompt().isBlank()) {
                parts.addObject().put("text", request.getPrompt());
            }
            // Reference images (image edit / image-to-image): inline as base64 parts.
            List<ImageReference> inputImages = request.getInputImages();
            boolean editing = inputImages != null && !inputImages.isEmpty();
            if (inputImages != null) {
                for (ImageReference ref : inputImages) {
                    if (ref == null || ref.data() == null || ref.data().length == 0) {
                        continue;
                    }
                    ObjectNode inlineData = parts.addObject().putObject("inlineData");
                    inlineData.put("mimeType", ref.mimeType() != null ? ref.mimeType() : "image/png");
                    inlineData.put("data", Base64.getEncoder().encodeToString(ref.data()));
                }
            }

            // generationConfig
            ObjectNode genConfig = body.putObject("generationConfig");
            ArrayNode modalities = genConfig.putArray("responseModalities");
            modalities.add("TEXT");
            modalities.add("IMAGE");

            ObjectNode imageConfig = objectMapper.createObjectNode();
            if (request.getAspectRatio() != null && !request.getAspectRatio().isBlank()) {
                imageConfig.put("aspectRatio", request.getAspectRatio());
            }
            // Nano Banana Pro resolution tier (1K / 2K / 4K) — opt-in via extraParams.
            Object imageSize = request.getExtraParams() != null
                    ? request.getExtraParams().get("imageSize") : null;
            if (imageSize instanceof String sizeTier && !sizeTier.isBlank()) {
                imageConfig.put("imageSize", sizeTier);
            }
            if (!imageConfig.isEmpty()) {
                genConfig.set("imageConfig", imageConfig);
            }

            String url = BASE_URL + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(120_000)
                    .execute();

            if (response.getStatus() != 200) {
                String errBody = response.body();
                log.warn("[Nano Banana] Failed: HTTP {} - {}", response.getStatus(), errBody);
                return ImageSubmitResult.failure(id(), "Gemini 图像生成失败: HTTP " + response.getStatus());
            }

            JsonNode result = objectMapper.readTree(response.body());
            List<String> imageUrls = extractImagesFromResponse(result);

            if (imageUrls.isEmpty()) {
                return ImageSubmitResult.failure(id(), "Gemini 未返回图片");
            }

            log.info("[Nano Banana] Generated {} image(s) (model={}, editing={})",
                    imageUrls.size(), model, editing);
            return ImageSubmitResult.syncSuccess(id(), imageUrls);

        } catch (Exception e) {
            log.error("[Nano Banana] Error: {}", e.getMessage(), e);
            return ImageSubmitResult.failure(id(), "Gemini 图像生成异常: " + e.getMessage());
        }
    }

    /**
     * Extract base64 images from a Gemini generateContent response, converting
     * each {@code inlineData} part to a {@code data:} URI.
     */
    private List<String> extractImagesFromResponse(JsonNode result) {
        List<String> images = new ArrayList<>();

        JsonNode candidates = result.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                JsonNode parts = candidate.path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        // Accept both inlineData (camelCase) and inline_data (snake_case).
                        JsonNode inlineData = part.has("inlineData") ? part.get("inlineData")
                                : part.path("inline_data");
                        if (inlineData.has("data")) {
                            String mimeType = inlineData.has("mimeType")
                                    ? inlineData.get("mimeType").asText("image/png")
                                    : inlineData.path("mime_type").asText("image/png");
                            String base64Data = inlineData.get("data").asText();
                            images.add("data:" + mimeType + ";base64," + base64Data);
                        }
                    }
                }
            }
        }
        return images;
    }

    private String getApiKey() {
        try {
            return modelProviderService.getProviderConfig(LLM_PROVIDER_ID).getApiKey();
        } catch (Exception e) {
            return null;
        }
    }
}
