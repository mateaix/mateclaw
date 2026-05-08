package vip.mate.tool.video.provider;

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
import vip.mate.tool.video.VideoCapability;
import vip.mate.tool.video.VideoGenerationProvider;
import vip.mate.tool.video.VideoGenerationRequest;
import vip.mate.tool.video.VideoProviderCapabilities;
import vip.mate.tool.video.VideoSubmitResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DashScope video provider — supports two payload families on the same async
 * task model, selected per model id:
 *
 * <ul>
 *   <li><b>Legacy</b> ({@code services/aigc/video-generation/generation}) for
 *       wanx 2.1 and wan 2.5 turbo lines. Body uses {@code input.img_url} for
 *       image-to-video and {@code parameters.size} for sizing.</li>
 *   <li><b>Unified video-synthesis</b>
 *       ({@code services/aigc/video-generation/video-synthesis}) for wan 2.7
 *       and the happyhorse t2v line. Body uses {@code input.media[]} for the
 *       first frame plus {@code parameters.resolution} + {@code parameters.ratio}
 *       for sizing.</li>
 * </ul>
 *
 * Routing is data-driven: each model is registered with its endpoint, body
 * shape, and capability set; submit/build code consults the spec rather than
 * branching on model id strings.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeVideoProvider implements VideoGenerationProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    private static final String LEGACY_ENDPOINT = BASE_URL + "/services/aigc/video-generation/generation";
    private static final String UNIFIED_ENDPOINT = BASE_URL + "/services/aigc/video-generation/video-synthesis";
    private static final String TASKS_ENDPOINT_PREFIX = BASE_URL + "/tasks/";

    private static final String DEFAULT_T2V_MODEL = "wan2.5-t2v-turbo";
    private static final String DEFAULT_I2V_MODEL = "wan2.5-i2v-turbo";

    private enum BodyShape {
        /** input.img_url + parameters.size("1280*720") + parameters.duration. */
        LEGACY,
        /** input.media[].first_frame + parameters.resolution + parameters.ratio + parameters.duration. */
        UNIFIED
    }

    private record ModelSpec(
            String id,
            String endpoint,
            BodyShape bodyShape,
            Set<VideoCapability> modes
    ) {}

    private static final Map<String, ModelSpec> MODELS = buildCatalog();

    private static Map<String, ModelSpec> buildCatalog() {
        Map<String, ModelSpec> m = new LinkedHashMap<>();
        // Legacy line — text-to-video
        m.put("wan2.5-t2v-turbo", new ModelSpec("wan2.5-t2v-turbo",
                LEGACY_ENDPOINT, BodyShape.LEGACY, Set.of(VideoCapability.GENERATE)));
        m.put("wanx2.1-t2v-turbo", new ModelSpec("wanx2.1-t2v-turbo",
                LEGACY_ENDPOINT, BodyShape.LEGACY, Set.of(VideoCapability.GENERATE)));
        // Legacy line — image-to-video
        m.put("wan2.5-i2v-turbo", new ModelSpec("wan2.5-i2v-turbo",
                LEGACY_ENDPOINT, BodyShape.LEGACY, Set.of(VideoCapability.IMAGE_TO_VIDEO)));
        m.put("wanx2.1-i2v-turbo", new ModelSpec("wanx2.1-i2v-turbo",
                LEGACY_ENDPOINT, BodyShape.LEGACY, Set.of(VideoCapability.IMAGE_TO_VIDEO)));
        // Unified video-synthesis line — wan 2.7
        m.put("wan2.7-t2v-2026-04-25", new ModelSpec("wan2.7-t2v-2026-04-25",
                UNIFIED_ENDPOINT, BodyShape.UNIFIED, Set.of(VideoCapability.GENERATE)));
        m.put("wan2.7-i2v-2026-04-25", new ModelSpec("wan2.7-i2v-2026-04-25",
                UNIFIED_ENDPOINT, BodyShape.UNIFIED, Set.of(VideoCapability.IMAGE_TO_VIDEO)));
        // Unified video-synthesis line — happyhorse text-to-video
        m.put("happyhorse-1.0-t2v", new ModelSpec("happyhorse-1.0-t2v",
                UNIFIED_ENDPOINT, BodyShape.UNIFIED, Set.of(VideoCapability.GENERATE)));
        return Map.copyOf(m);
    }

    @Override
    public String id() {
        return "dashscope";
    }

    @Override
    public String label() {
        return "DashScope (Tongyi Wanxiang / HappyHorse)";
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
    public Set<VideoCapability> capabilities() {
        return Set.of(VideoCapability.GENERATE, VideoCapability.IMAGE_TO_VIDEO);
    }

    @Override
    public VideoProviderCapabilities detailedCapabilities() {
        return VideoProviderCapabilities.builder()
                .modes(capabilities())
                .aspectRatios(List.of("16:9", "9:16", "1:1"))
                .supportedDurations(List.of(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15))
                .maxDurationSeconds(15)
                .defaultModel(DEFAULT_T2V_MODEL)
                .models(List.copyOf(MODELS.keySet()))
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
    public VideoSubmitResult submit(VideoGenerationRequest request, SystemSettingsDTO config) {
        String apiKey = getDashScopeApiKey();
        if (apiKey == null) {
            return VideoSubmitResult.failure(id(), "DashScope API Key not configured");
        }
        ModelSpec spec = resolveSpec(request);
        try {
            ObjectNode body = buildRequestBody(request, spec);
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
                log.info("[DashScope Video] Submitted task {} (model={})", taskId, spec.id());
                return VideoSubmitResult.success(taskId, id());
            }
            String errMsg = result.has("message") ? result.get("message").asText()
                    : "HTTP " + response.getStatus();
            log.warn("[DashScope Video] Submit failed (model={}): {}", spec.id(), errMsg);
            return VideoSubmitResult.failure(id(), errMsg);
        } catch (Exception e) {
            log.error("[DashScope Video] Submit error (model={}): {}", spec.id(), e.getMessage(), e);
            return VideoSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        String apiKey = getDashScopeApiKey();
        if (apiKey == null) {
            return TaskPollResult.failed("DashScope API Key not configured");
        }
        try {
            HttpResponse response = HttpRequest.get(TASKS_ENDPOINT_PREFIX + providerTaskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(15_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());
            JsonNode output = result.path("output");
            String taskStatus = output.path("task_status").asText();
            return switch (taskStatus) {
                case "SUCCEEDED" -> {
                    String videoUrl = extractVideoUrl(output);
                    yield TaskPollResult.succeeded(videoUrl, null, output.toString());
                }
                case "FAILED" -> {
                    String errMsg = output.has("message") ? output.get("message").asText() : "task failed";
                    yield TaskPollResult.failed(errMsg);
                }
                case "RUNNING" -> TaskPollResult.running(null);
                default -> TaskPollResult.pending(null);
            };
        } catch (Exception e) {
            log.error("[DashScope Video] Poll error for task {}: {}", providerTaskId, e.getMessage());
            return null;
        }
    }

    // ==================== spec resolution ====================

    private ModelSpec resolveSpec(VideoGenerationRequest request) {
        String requested = request.getModel();
        if (requested != null && !requested.isBlank() && MODELS.containsKey(requested)) {
            return MODELS.get(requested);
        }
        // Fall back to a default by mode.
        String defaultId = request.getMode() == VideoCapability.IMAGE_TO_VIDEO
                ? DEFAULT_I2V_MODEL : DEFAULT_T2V_MODEL;
        return MODELS.get(defaultId);
    }

    // ==================== body building ====================

    private ObjectNode buildRequestBody(VideoGenerationRequest request, ModelSpec spec) {
        return switch (spec.bodyShape()) {
            case LEGACY -> buildLegacyBody(request, spec);
            case UNIFIED -> buildUnifiedBody(request, spec);
        };
    }

    private ObjectNode buildLegacyBody(VideoGenerationRequest request, ModelSpec spec) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", spec.id());

        ObjectNode input = body.putObject("input");
        input.put("prompt", request.getPrompt() == null ? "" : request.getPrompt());
        if (spec.modes().contains(VideoCapability.IMAGE_TO_VIDEO)
                && request.getImageUrl() != null && !request.getImageUrl().isBlank()) {
            input.put("img_url", request.getImageUrl());
        }

        ObjectNode parameters = body.putObject("parameters");
        String size = aspectRatioToLegacySize(request.getAspectRatio());
        if (size != null) {
            parameters.put("size", size);
        }
        if (request.getDurationSeconds() != null) {
            parameters.put("duration", String.valueOf(request.getDurationSeconds()));
        }
        return body;
    }

    private ObjectNode buildUnifiedBody(VideoGenerationRequest request, ModelSpec spec) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", spec.id());

        ObjectNode input = body.putObject("input");
        input.put("prompt", request.getPrompt() == null ? "" : request.getPrompt());
        if (spec.modes().contains(VideoCapability.IMAGE_TO_VIDEO)
                && request.getImageUrl() != null && !request.getImageUrl().isBlank()) {
            ArrayNode media = input.putArray("media");
            ObjectNode firstFrame = media.addObject();
            firstFrame.put("type", "first_frame");
            firstFrame.put("url", request.getImageUrl());
        }

        ObjectNode parameters = body.putObject("parameters");
        String resolution = aspectRatioToUnifiedResolution(request.getAspectRatio());
        parameters.put("resolution", resolution);
        if (request.getAspectRatio() != null && !request.getAspectRatio().isBlank()) {
            parameters.put("ratio", request.getAspectRatio());
        }
        if (request.getDurationSeconds() != null) {
            // Unified endpoint expects the duration as an integer.
            parameters.put("duration", request.getDurationSeconds());
        }
        return body;
    }

    private String aspectRatioToLegacySize(String aspectRatio) {
        if (aspectRatio == null) return null;
        return switch (aspectRatio) {
            case "16:9" -> "1280*720";
            case "9:16" -> "720*1280";
            case "1:1" -> "720*720";
            default -> null;
        };
    }

    private String aspectRatioToUnifiedResolution(String aspectRatio) {
        // Default to 720P; the unified endpoint also accepts 1080P. Callers that
        // want to override should pass it via extraParams in a future iteration.
        return "720P";
    }

    private String extractVideoUrl(JsonNode output) {
        if (output.has("video_url")) {
            return output.get("video_url").asText();
        }
        JsonNode results = output.path("results");
        if (results.isArray() && !results.isEmpty()) {
            return results.get(0).path("url").asText(null);
        }
        return null;
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
