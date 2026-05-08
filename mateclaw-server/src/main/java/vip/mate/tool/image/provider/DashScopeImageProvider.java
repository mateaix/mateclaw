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
import vip.mate.task.AsyncTaskService.TaskPollResult;
import vip.mate.tool.image.ImageCapability;
import vip.mate.tool.image.ImageGenerationProvider;
import vip.mate.tool.image.ImageGenerationRequest;
import vip.mate.tool.image.ImageModelSpec;
import vip.mate.tool.image.ImageProviderCapabilities;
import vip.mate.tool.image.ImageReference;
import vip.mate.tool.image.ImageSubmitResult;
import vip.mate.tool.image.PayloadBuilder;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * DashScope image provider — routes per-model between two transports:
 *
 * <ul>
 *   <li><b>Async legacy</b> ({@code services/aigc/image-generation/generation})
 *       for the wanx 2.0/2.1, wan 2.2/2.5 turbo/plus families. Submit returns a
 *       task id; the caller polls {@code /api/v1/tasks/{id}} until
 *       SUCCEEDED.</li>
 *   <li><b>Sync multimodal</b> ({@code services/aigc/multimodal-generation/generation})
 *       for wan 2.6/2.7 image, qwen-image, qwen-image-edit, z-image. The
 *       generated image URL is returned in the same response. This endpoint
 *       also accepts inline reference images, enabling the image edit /
 *       image-to-image flow.</li>
 * </ul>
 *
 * The model catalog ({@link DashScopeImageModels}) drives endpoint selection,
 * payload shape, and the {@code supports} whitelist — adding a new model is a
 * one-line spec entry.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeImageProvider implements ImageGenerationProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    @Override
    public String id() {
        return "dashscope";
    }

    @Override
    public String label() {
        return "DashScope (Tongyi Wanxiang / Qwen-Image)";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 100;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_EDIT);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        List<String> modelIds = new ArrayList<>(DashScopeImageModels.all().keySet());
        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(List.of(
                        "1024x1024", "1280x720", "720x1280",
                        "2048x2048", "2560x1440", "1440x2560"))
                .aspectRatios(List.of("1:1", "16:9", "9:16"))
                .maxCount(4)
                .defaultModel(DashScopeImageModels.DEFAULT_MODEL)
                .models(modelIds)
                .generate(ImageProviderCapabilities.Generate.builder()
                        .maxCount(4).supportsSize(true).supportsAspectRatio(true).build())
                .edit(ImageProviderCapabilities.Edit.builder()
                        .enabled(true).maxCount(4).maxInputImages(3)
                        .supportsSize(true).supportsAspectRatio(true).build())
                .geometry(ImageProviderCapabilities.Geometry.builder()
                        .sizes(List.of(
                                "1024x1024", "1280x720", "720x1280",
                                "2048x2048", "2560x1440", "1440x2560"))
                        .aspectRatios(List.of("1:1", "16:9", "9:16"))
                        .build())
                .build();
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
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        String apiKey = getDashScopeApiKey();
        if (apiKey == null) {
            return ImageSubmitResult.failure(id(), "DashScope API Key not configured");
        }

        ImageModelSpec spec = resolveSpec(request);
        try {
            return spec.transport() == ImageModelSpec.Transport.SYNC
                    ? submitSyncMultimodal(request, spec, apiKey)
                    : submitAsyncLegacy(request, spec, apiKey);
        } catch (Exception e) {
            log.error("[DashScope Image] Submit error (model={}): {}", spec.id(), e.getMessage(), e);
            return ImageSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        String apiKey = getDashScopeApiKey();
        if (apiKey == null) {
            return TaskPollResult.failed("DashScope API Key not configured");
        }
        try {
            HttpResponse response = HttpRequest.get(DashScopeImageModels.TASKS_ENDPOINT_PREFIX + providerTaskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(15_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());
            JsonNode output = result.path("output");
            String taskStatus = output.path("task_status").asText();

            return switch (taskStatus) {
                case "SUCCEEDED" -> {
                    String imageUrl = extractLegacyImageUrl(output);
                    yield TaskPollResult.imageSucceeded(imageUrl, output.toString());
                }
                case "FAILED" -> {
                    String errMsg = output.has("message") ? output.get("message").asText() : "task failed";
                    yield TaskPollResult.failed(errMsg);
                }
                case "RUNNING" -> TaskPollResult.running(null);
                default -> TaskPollResult.pending(null);
            };
        } catch (Exception e) {
            log.error("[DashScope Image] Poll error for task {}: {}", providerTaskId, e.getMessage());
            return null;
        }
    }

    // ==================== spec resolution ====================

    /**
     * Pick the model spec for this request. When the request asks for image
     * editing but names a model that doesn't support edits (or names nothing),
     * fall back to {@link DashScopeImageModels#DEFAULT_EDIT_MODEL} so the call
     * doesn't silently degrade to a text-only generation.
     */
    private ImageModelSpec resolveSpec(ImageGenerationRequest request) {
        boolean wantsEdit = request.getInputImages() != null && !request.getInputImages().isEmpty();
        String requested = request.getModel();
        ImageModelSpec spec = DashScopeImageModels.get(requested);
        if (wantsEdit && !spec.supportsEdit()) {
            ImageModelSpec edit = DashScopeImageModels.get(DashScopeImageModels.DEFAULT_EDIT_MODEL);
            log.info("[DashScope Image] Model {} lacks edit support; routing to {}", spec.id(), edit.id());
            return edit;
        }
        return spec;
    }

    // ==================== sync multimodal-generation ====================

    private ImageSubmitResult submitSyncMultimodal(ImageGenerationRequest request,
                                                    ImageModelSpec spec,
                                                    String apiKey) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", spec.id());

        // input.messages[].content[] — image blocks first when editing,
        // followed by the text prompt block.
        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");

        if (request.getInputImages() != null) {
            for (ImageReference ref : request.getInputImages()) {
                ObjectNode imgPart = content.addObject();
                imgPart.put("image", toDataUrl(ref));
            }
        }
        ObjectNode textPart = content.addObject();
        textPart.put("text", request.getPrompt() == null ? "" : request.getPrompt());

        // parameters block — built and filtered against the model's supports set.
        ObjectNode parameters = PayloadBuilder.from(spec)
                .withSize(request.getSize(), request.getAspectRatio())
                .withCount(request.getCount())
                .toJsonNode(objectMapper);
        body.set("parameters", parameters);

        HttpResponse response = HttpRequest.post(spec.endpoint())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(180_000)
                .execute();

        JsonNode result = objectMapper.readTree(response.body());
        if (response.getStatus() != 200) {
            String errMsg = result.has("message") ? result.get("message").asText() : "HTTP " + response.getStatus();
            log.warn("[DashScope Image] Sync submit failed (model={}): {}", spec.id(), errMsg);
            return ImageSubmitResult.failure(id(), errMsg);
        }

        List<String> imageUrls = extractMultimodalImageUrls(result);
        if (imageUrls.isEmpty()) {
            return ImageSubmitResult.failure(id(), "Multimodal response carried no image URL");
        }
        log.info("[DashScope Image] Sync generated {} image(s) (model={})", imageUrls.size(), spec.id());
        return ImageSubmitResult.syncSuccess(id(), imageUrls);
    }

    private List<String> extractMultimodalImageUrls(JsonNode result) {
        List<String> urls = new ArrayList<>();
        JsonNode choices = result.path("output").path("choices");
        if (!choices.isArray()) {
            return urls;
        }
        for (JsonNode choice : choices) {
            JsonNode parts = choice.path("message").path("content");
            if (!parts.isArray()) continue;
            for (JsonNode part : parts) {
                String url = part.path("image").asText(null);
                if (url != null && !url.isBlank()) {
                    urls.add(url);
                }
            }
        }
        return urls;
    }

    // ==================== async legacy image-generation ====================

    private ImageSubmitResult submitAsyncLegacy(ImageGenerationRequest request,
                                                 ImageModelSpec spec,
                                                 String apiKey) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", spec.id());

        ObjectNode input = body.putObject("input");
        input.put("prompt", request.getPrompt() == null ? "" : request.getPrompt());

        ObjectNode parameters = PayloadBuilder.from(spec)
                .withSize(request.getSize(), request.getAspectRatio())
                .withCount(request.getCount())
                .toJsonNode(objectMapper);
        body.set("parameters", parameters);

        HttpResponse response = HttpRequest.post(spec.endpoint())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .body(body.toString())
                .timeout(30_000)
                .execute();

        JsonNode result = objectMapper.readTree(response.body());
        if (response.getStatus() == 200 && result.has("output")) {
            String taskId = result.path("output").path("task_id").asText();
            log.info("[DashScope Image] Async submitted task {} (model={})", taskId, spec.id());
            return ImageSubmitResult.asyncSuccess(taskId, id());
        }
        String errMsg = result.has("message") ? result.get("message").asText()
                : "HTTP " + response.getStatus();
        log.warn("[DashScope Image] Async submit failed (model={}): {}", spec.id(), errMsg);
        return ImageSubmitResult.failure(id(), errMsg);
    }

    private String extractLegacyImageUrl(JsonNode output) {
        JsonNode results = output.path("results");
        if (results.isArray() && !results.isEmpty()) {
            JsonNode first = results.get(0);
            String url = first.path("url").asText(null);
            if (url == null || url.isBlank()) {
                url = first.path("image").asText(null);
            }
            return url;
        }
        return null;
    }

    // ==================== shared helpers ====================

    private String toDataUrl(ImageReference ref) {
        String mime = ref.mimeType() == null || ref.mimeType().isBlank() ? "image/png" : ref.mimeType();
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(ref.data());
    }

    private String getDashScopeApiKey() {
        try {
            var providerEntity = modelProviderService.getProviderConfig("dashscope");
            return providerEntity.getApiKey();
        } catch (Exception e) {
            return null;
        }
    }
}
