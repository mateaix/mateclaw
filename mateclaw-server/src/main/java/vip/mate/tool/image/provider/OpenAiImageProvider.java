package vip.mate.tool.image.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OpenAI 图片生成 Provider
 * — 支持 DALL-E 3 / DALL-E 2 / gpt-image-1 / gpt-image-2 (low/medium/high)
 * <p>
 * 同步模式：返回图片 URL（DALL-E 系列）或 base64 data URL（gpt-image-2 系列）。
 * 复用已有的 OpenAI LLM provider 的 API Key。
 *
 * <p>gpt-image-2 三档质量做成 3 个虚拟 model ID，让 picker
 * 能直接选 fast/balanced/high。三档底层都打到 API model {@code "gpt-image-2"}，
 * 区别仅在 {@code quality} 参数。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiImageProvider implements ImageGenerationProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_MODEL = "dall-e-3";

    /**
     * gpt-image-2 真实 API model 名。三档虚拟 ID（gpt-image-2-low/medium/high）
     * 在 submit 时全部打到这个 API model + 不同 quality 参数。
     */
    private static final String GPT_IMAGE_2_API_MODEL = "gpt-image-2";

    /** gpt-image-2 系列虚拟 ID 列表 — 用于 capabilities 与分支判定。 */
    private static final List<String> GPT_IMAGE_2_TIERS =
            List.of("gpt-image-2-low", "gpt-image-2-medium", "gpt-image-2-high");

    /** gpt-image-2 支持的尺寸（与 DALL-E 不同！1536x1024 / 1024x1024 / 1024x1536）。 */
    private static final List<String> GPT_IMAGE_2_SIZES =
            List.of("1024x1024", "1536x1024", "1024x1536");

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public String label() {
        return "OpenAI (DALL-E)";
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
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        // 合并 DALL-E 与 gpt-image-2 两套尺寸（去重）。运行时按选定 model
        // 做尺寸校验，picker 只展示并集即可。
        List<String> allSizes = new ArrayList<>();
        allSizes.add("1024x1024");
        allSizes.add("1024x1792");  // dall-e
        allSizes.add("1792x1024");  // dall-e
        allSizes.add("1024x1536");  // gpt-image-2
        allSizes.add("1536x1024");  // gpt-image-2

        List<String> models = new ArrayList<>();
        models.add("dall-e-3");
        models.add("dall-e-2");
        models.add("gpt-image-1");
        models.addAll(GPT_IMAGE_2_TIERS);  // gpt-image-2-low/medium/high

        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(allSizes)
                .aspectRatios(List.of("1:1", "9:16", "16:9"))
                .maxCount(1) // DALL-E 3 / gpt-image-2 都只支持 n=1
                .defaultModel(DEFAULT_MODEL)
                .models(models)
                .build();
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
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        String apiKey = getOpenAiApiKey();
        String baseUrl = getOpenAiBaseUrl();
        if (apiKey == null) {
            return ImageSubmitResult.failure(id(), "OpenAI API Key 未配置");
        }

        try {
            String requestedModel = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;
            // gpt-image-2 系列：三档虚拟 ID 全部打到 API model "gpt-image-2"
            // + 对应 quality 参数；DALL-E 系列保留原行为。
            boolean isGptImage2 = GPT_IMAGE_2_TIERS.contains(requestedModel);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", isGptImage2 ? GPT_IMAGE_2_API_MODEL : requestedModel);
            body.put("prompt", request.getPrompt());
            body.put("size", normalizeSize(request.getSize(), request.getAspectRatio(), isGptImage2));
            body.put("n", 1);

            if (isGptImage2) {
                // gpt-image-2 强制 b64_json，且 REJECT 任何 response_format 字段
                // （API 会以 unknown parameter 报错）。仅传 quality。
                body.put("quality", qualityForTier(requestedModel));
            } else {
                // DALL-E 系列保留 URL 模式。
                body.put("response_format", "url");
            }

            String url = (baseUrl != null ? baseUrl : "https://api.openai.com") + "/v1/images/generations";

            HttpResponse response = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    // gpt-image-2 high 档官方文档约 ~2min；这里给到 180s 留余地
                    .timeout(isGptImage2 ? 180_000 : 60_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            if (response.getStatus() == 200 && result.has("data")) {
                List<String> imageUrls = new ArrayList<>();
                for (JsonNode item : result.get("data")) {
                    if (isGptImage2) {
                        // gpt-image-2 永远返回 b64_json。包成 data URL，交给前端
                        // 直接 <img src="data:image/png;base64,..."> 渲染，沿用
                        // GoogleImagenProvider / MiniMaxImageProvider 的现成模式。
                        String b64 = item.has("b64_json") ? item.get("b64_json").asText() : null;
                        if (b64 != null && !b64.isBlank()) {
                            imageUrls.add("data:image/png;base64," + b64);
                        }
                    } else {
                        String imageUrl = item.has("url") ? item.get("url").asText() : null;
                        if (imageUrl != null) {
                            imageUrls.add(imageUrl);
                        }
                    }
                }
                if (imageUrls.isEmpty()) {
                    return ImageSubmitResult.failure(id(),
                            isGptImage2 ? "API 返回成功但未包含 b64_json 图片数据"
                                        : "API 返回成功但未包含图片 URL");
                }
                log.info("[OpenAI Image] Generated {} image(s) (model={})",
                        imageUrls.size(), requestedModel);
                return ImageSubmitResult.syncSuccess(id(), imageUrls);
            } else {
                String errMsg = result.has("error")
                        ? result.path("error").path("message").asText("Unknown error")
                        : "HTTP " + response.getStatus();
                log.warn("[OpenAI Image] Submit failed: {}", errMsg);
                return ImageSubmitResult.failure(id(), errMsg);
            }
        } catch (Exception e) {
            log.error("[OpenAI Image] Submit error: {}", e.getMessage(), e);
            return ImageSubmitResult.failure(id(), e.getMessage());
        }
    }

    /** Map gpt-image-2-{low|medium|high} → quality string sent to API.
     * Package-private + static for unit testability. */
    static String qualityForTier(String tierModelId) {
        return switch (tierModelId) {
            case "gpt-image-2-low" -> "low";
            case "gpt-image-2-high" -> "high";
            default -> "medium";  // gpt-image-2-medium + 任何未来 tier 都默认 medium
        };
    }

    /** Returns true if the given model id is a gpt-image-2 virtual tier.
     * Package-private + static for unit testability.
     * <p>Null-safe: {@code List.of(...).contains(null)} throws NPE, which we
     * pre-empt with an explicit null check. */
    static boolean isGptImage2Tier(String modelId) {
        return modelId != null && GPT_IMAGE_2_TIERS.contains(modelId);
    }

    private String getOpenAiApiKey() {
        try {
            var providerEntity = modelProviderService.getProviderConfig("openai");
            return providerEntity.getApiKey();
        } catch (Exception e) {
            return null;
        }
    }

    private String getOpenAiBaseUrl() {
        try {
            var providerEntity = modelProviderService.getProviderConfig("openai");
            return providerEntity.getBaseUrl();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按 model 选合适的尺寸集合：
     * <ul>
     *   <li>DALL-E：1024x1024 / 1024x1792 / 1792x1024</li>
     *   <li>gpt-image-2：1024x1024 / 1024x1536 / 1536x1024（不一样！）</li>
     * </ul>
     */
    /** Package-private + static-ish for unit testability. Kept instance-method to
     * stay close to the call site — no instance state is touched. */
    String normalizeSize(String size, String aspectRatio, boolean isGptImage2) {
        List<String> supported = isGptImage2
                ? GPT_IMAGE_2_SIZES
                : List.of("1024x1024", "1024x1792", "1792x1024");

        // 优先使用 size（如果在该 model 支持范围内）
        if (size != null && !size.isBlank() && supported.contains(size)) {
            return size;
        }

        // 根据 aspectRatio 推断（gpt-image-2 与 DALL-E 的竖图/横图尺寸不一样）
        if (aspectRatio != null) {
            if (isGptImage2) {
                return switch (aspectRatio) {
                    case "9:16", "2:3", "3:4" -> "1024x1536";
                    case "16:9", "3:2", "4:3" -> "1536x1024";
                    default -> "1024x1024";
                };
            }
            return switch (aspectRatio) {
                case "9:16" -> "1024x1792";
                case "16:9" -> "1792x1024";
                default -> "1024x1024";
            };
        }
        return "1024x1024";
    }

    // 保留旧签名给可能存在的其它 caller（向后兼容）。新增 boolean 默认 false (DALL-E)。
    @SuppressWarnings("unused")
    private String normalizeSize(String size, String aspectRatio) {
        return normalizeSize(size, aspectRatio, false);
    }
}
