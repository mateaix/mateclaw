package vip.mate.tool.image.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MiniMax text-to-image generation with region-specific model endpoints.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MiniMaxImageProvider implements ImageGenerationProvider {

    private final ObjectMapper objectMapper;

    /** Global endpoint. */
    static final String BASE_URL_GLOBAL = "https://api.minimax.io";

    /** China endpoint (mainland-CN low-latency host; same JSON shape). */
    static final String BASE_URL_CN = "https://api.minimaxi.com";

    /** Region value selecting the CN endpoint. Anything else → global. */
    static final String REGION_CN = "cn";

    private static final String DEFAULT_MODEL = "image-01";
    private static final String H3 = "MiniMax-H3-Image";
    private static final String H3_HIGHSPEED = "MiniMax-H3-Image-Highspeed";

    /**
     * Resolve MiniMax base URL from system settings region. Shared semantics
     * with {@code MiniMaxVideoProvider.resolveBaseUrl} (single field controls
     * both image + video routing because the API key is the same).
     * Package-private for unit tests.
     */
    static String resolveBaseUrl(SystemSettingsDTO config) {
        if (config != null && REGION_CN.equalsIgnoreCase(config.getMinimaxRegion())) {
            return BASE_URL_CN;
        }
        return BASE_URL_GLOBAL;
    }

    @Override
    public String id() {
        return "minimax";
    }

    @Override
    public String label() {
        return "MiniMax Image";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 350;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(List.of("1024x1024"))
                .aspectRatios(List.of("1:1", "16:9", "4:3", "3:2", "2:3", "3:4", "9:16"))
                .maxCount(9)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of(DEFAULT_MODEL, H3, H3_HIGHSPEED))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        // 复用视频生成的 MiniMax API Key
        return StringUtils.hasText(config.getMinimaxApiKey());
    }

    @Override
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = config.getMinimaxApiKey();
            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", request.getPrompt());
            body.put("response_format", "url");
            body.put("n", request.getCount() != null ? request.getCount() : 1);

            if (request.getAspectRatio() != null) {
                body.put("aspect_ratio", request.getAspectRatio());
            }

            boolean h3 = H3.equals(model) || H3_HIGHSPEED.equals(model);
            if (h3) {
                // The image service stores synchronous results as PNG attachments.
                body.put("output_format", "png");
            }
            HttpRequest post = HttpRequest.post(generationEndpoint(config, h3))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(h3 ? 600_000 : 60_000);
            if (h3) {
                // Streaming the POST body prevents HttpURLConnection from replaying a paid generation.
                post.removeHeader("Content-Length").setChunkedStreamingMode(4096).setFollowRedirects(false);
            }
            JsonNode result;
            try (HttpResponse response = post.execute()) {
                result = objectMapper.readTree(response.body());
                if (h3) {
                    return parseH3Response(response.getStatus(), result);
                }
            }

            int statusCode = result.path("base_resp").path("status_code").asInt(-1);
            if (statusCode != 0) {
                String errMsg = result.path("base_resp").path("status_msg").asText("未知错误");
                log.warn("[MiniMax Image] Failed: {}", errMsg);
                return ImageSubmitResult.failure(id(), errMsg);
            }

            List<String> imageUrls = new ArrayList<>();

            // 尝试从 data.image_base64 提取
            JsonNode imageBase64Array = result.path("data").path("image_base64");
            if (imageBase64Array.isArray()) {
                for (JsonNode b64 : imageBase64Array) {
                    String base64Str = b64.asText();
                    if (StringUtils.hasText(base64Str)) {
                        imageUrls.add("data:image/png;base64," + base64Str);
                    }
                }
            }

            // 尝试从 data.image_urls 提取（response_format=url 时）
            JsonNode imageUrlsNode = result.path("data").path("image_urls");
            if (imageUrlsNode.isArray()) {
                for (JsonNode urlNode : imageUrlsNode) {
                    String url = urlNode.asText(null);
                    if (StringUtils.hasText(url)) {
                        imageUrls.add(url);
                    }
                }
            }

            if (imageUrls.isEmpty()) {
                return ImageSubmitResult.failure(id(), "MiniMax 未返回图片数据");
            }

            log.info("[MiniMax Image] Generated {} images (model={})", imageUrls.size(), model);
            return ImageSubmitResult.syncSuccess(id(), imageUrls);

        } catch (Exception e) {
            log.error("[MiniMax Image] Error: {}", e.getMessage(), e);
            return ImageSubmitResult.failure(id(), "MiniMax Image 异常: " + e.getMessage());
        }
    }

    String generationEndpoint(SystemSettingsDTO config, boolean h3) {
        if (!h3) {
            return resolveBaseUrl(config) + "/v1/image_generation";
        }
        String host = REGION_CN.equalsIgnoreCase(config.getMinimaxRegion()) ? "https://api.minimax.cn" : BASE_URL_GLOBAL;
        return host + "/v1/images/generations";
    }

    private ImageSubmitResult parseH3Response(int status, JsonNode result) {
        String error = result.path("error").path("message").asText(null);
        List<String> images = new ArrayList<>();
        for (JsonNode item : result.path("data")) {
            if (item.hasNonNull("error")) {
                if (error == null) {
                    error = item.path("error").path("message").asText(null);
                }
                continue;
            }
            String url = item.path("url").asText(null);
            String base64 = item.path("b64_json").asText(null);
            if (StringUtils.hasText(url)) {
                images.add(url);
            } else if (StringUtils.hasText(base64)) {
                images.add("data:image/png;base64," + base64);
            }
        }
        if (status >= 200 && status < 300 && !images.isEmpty()) {
            return ImageSubmitResult.syncSuccess(id(), images);
        }
        return ImageSubmitResult.failure(id(), error != null ? error : "No image returned (HTTP " + status + ")");
    }
}
