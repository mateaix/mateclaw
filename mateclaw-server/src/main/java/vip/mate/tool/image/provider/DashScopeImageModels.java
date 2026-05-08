package vip.mate.tool.image.provider;

import vip.mate.tool.image.ImageCapability;
import vip.mate.tool.image.ImageModelSpec;
import vip.mate.tool.image.SizeStyle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Catalog of DashScope-served image generation / editing models, organised so
 * that adding a new model is a one-line spec entry.
 *
 * <p>Two transport families are present:
 * <ul>
 *   <li><b>Async legacy</b> ({@link #LEGACY_ASYNC_ENDPOINT}) — wanx 2.0/2.1 and
 *       wan 2.2/2.5 turbo/plus models that exclusively do text-to-image. The
 *       caller submits and polls {@code /api/v1/tasks/{id}}.</li>
 *   <li><b>Sync multimodal</b> ({@link #MULTIMODAL_ENDPOINT}) — wan 2.6/2.7,
 *       qwen-image, qwen-image-edit, z-image. Uses the OpenAI-style
 *       {@code messages.content[]} array and returns the generated image URL
 *       in the same response.</li>
 * </ul>
 *
 * @author MateClaw Team
 */
final class DashScopeImageModels {

    static final String LEGACY_ASYNC_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation";
    static final String MULTIMODAL_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    static final String TASKS_ENDPOINT_PREFIX =
            "https://dashscope.aliyuncs.com/api/v1/tasks/";

    /**
     * Default model when the request does not name one.
     *
     * <p>Kept on the legacy turbo so existing accounts that have not enrolled in
     * the newer wan/qwen-image families do not see breakage. Callers that want
     * edit support must name a model explicitly (e.g. {@code wan2.7-image} or
     * {@code qwen-image-edit}) — the registry's edit-capability resolution then
     * routes correctly.
     */
    static final String DEFAULT_MODEL = "wanx2.1-t2i-turbo";

    /**
     * Default model when an edit-capable spec is required but the request did
     * not name one. Used by the provider when the request carries
     * {@code inputImages} but the named model lacks {@link ImageCapability#IMAGE_EDIT}.
     */
    static final String DEFAULT_EDIT_MODEL = "wan2.7-image";

    private static final Map<String, String> ASPECT_LITERAL_SIZES = Map.of(
            "1:1", "1024x1024",
            "16:9", "1280x720",
            "9:16", "720x1280",
            "landscape", "1280x720",
            "square", "1024x1024",
            "portrait", "720x1280"
    );

    private static final Map<String, String> ASPECT_LITERAL_SIZES_2K = Map.of(
            "1:1", "2048x2048",
            "16:9", "2560x1440",
            "9:16", "1440x2560",
            "landscape", "2560x1440",
            "square", "2048x2048",
            "portrait", "1440x2560"
    );

    private DashScopeImageModels() {}

    private static final Map<String, ImageModelSpec> CATALOG = buildCatalog();

    static Map<String, ImageModelSpec> all() {
        return CATALOG;
    }

    static ImageModelSpec get(String id) {
        if (id == null || id.isBlank()) {
            return CATALOG.get(DEFAULT_MODEL);
        }
        return CATALOG.getOrDefault(id, CATALOG.get(DEFAULT_MODEL));
    }

    private static Map<String, ImageModelSpec> buildCatalog() {
        Map<String, ImageModelSpec> m = new LinkedHashMap<>();

        // ========== Legacy async text-to-image (image-generation/generation) ==========
        // No edit support; keeps backward compatibility for users on existing model ids.
        addAsyncT2I(m, "wanx2.1-t2i-turbo");
        addAsyncT2I(m, "wanx2.1-t2i-plus");
        addAsyncT2I(m, "wanx2.0-t2i-turbo");
        addAsyncT2I(m, "wan2.2-t2i-flash");
        addAsyncT2I(m, "wan2.2-t2i-plus");
        addAsyncT2I(m, "wan2.5-t2i-preview");

        // ========== Sync multimodal text-to-image only (multimodal-generation) ==========
        m.put("z-image-turbo", ImageModelSpec.builder()
                .id("z-image-turbo")
                .displayName("Z-Image Turbo (fastest)")
                .endpoint(MULTIMODAL_ENDPOINT)
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                .sizeMap(ASPECT_LITERAL_SIZES)
                .modes(Set.of(ImageCapability.TEXT_TO_IMAGE))
                .supports(Set.of("size", "seed", "prompt_extend"))
                .maxCount(1)
                .maxInputImages(0)
                .build());

        // ========== Sync multimodal text-to-image + edit (qwen-image series) ==========
        addQwenImage(m, "qwen-image-2.0");
        addQwenImage(m, "qwen-image-2.0-pro");
        addQwenImageEdit(m, "qwen-image-edit");
        addQwenImageEdit(m, "qwen-image-edit-plus");
        addQwenImageEdit(m, "qwen-image-edit-max");

        // ========== Sync multimodal text-to-image + edit (wan2.6 / 2.7 image) ==========
        m.put("wan2.6-t2i", ImageModelSpec.builder()
                .id("wan2.6-t2i")
                .displayName("Wan 2.6 (sync T2I)")
                .endpoint(MULTIMODAL_ENDPOINT)
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                .sizeMap(ASPECT_LITERAL_SIZES)
                .modes(Set.of(ImageCapability.TEXT_TO_IMAGE))
                .supports(Set.of("size", "n", "seed", "negative_prompt", "prompt_extend", "watermark"))
                .maxCount(4)
                .maxInputImages(0)
                .build());

        m.put("wan2.7-image", ImageModelSpec.builder()
                .id("wan2.7-image")
                .displayName("Wan 2.7 Image (T2I + edit, up to 2K)")
                .endpoint(MULTIMODAL_ENDPOINT)
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                .sizeMap(ASPECT_LITERAL_SIZES)
                .modes(Set.of(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_EDIT))
                .supports(Set.of("size", "n", "seed", "negative_prompt", "prompt_extend", "watermark"))
                .maxCount(4)
                .maxInputImages(3)
                .build());

        m.put("wan2.7-image-pro", ImageModelSpec.builder()
                .id("wan2.7-image-pro")
                .displayName("Wan 2.7 Image Pro (T2I + edit, up to 4K)")
                .endpoint(MULTIMODAL_ENDPOINT)
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                .sizeMap(ASPECT_LITERAL_SIZES_2K)
                .modes(Set.of(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_EDIT))
                .supports(Set.of("size", "n", "seed", "negative_prompt", "prompt_extend", "watermark"))
                .maxCount(4)
                .maxInputImages(3)
                .build());

        return Map.copyOf(m);
    }

    // ------------------- helper builders -------------------

    private static void addAsyncT2I(Map<String, ImageModelSpec> m, String id) {
        m.put(id, ImageModelSpec.builder()
                .id(id)
                .displayName(id + " (async legacy T2I)")
                .endpoint(LEGACY_ASYNC_ENDPOINT)
                .transport(ImageModelSpec.Transport.ASYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                // Legacy endpoint uses '*' as the size separator; sizeMap stores
                // the native form so PayloadBuilder can pass it through.
                .sizeMapping("1:1", "1024*1024")
                .sizeMapping("16:9", "1280*720")
                .sizeMapping("9:16", "720*1280")
                .sizeMapping("landscape", "1280*720")
                .sizeMapping("square", "1024*1024")
                .sizeMapping("portrait", "720*1280")
                .sizeMapping("1024x1024", "1024*1024")
                .sizeMapping("1280x720", "1280*720")
                .sizeMapping("720x1280", "720*1280")
                .modes(Set.of(ImageCapability.TEXT_TO_IMAGE))
                .supports(Set.of("size", "n"))
                .maxCount(4)
                .maxInputImages(0)
                .build());
    }

    private static void addQwenImage(Map<String, ImageModelSpec> m, String id) {
        m.put(id, ImageModelSpec.builder()
                .id(id)
                .displayName(id + " (T2I)")
                .endpoint(MULTIMODAL_ENDPOINT)
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                .sizeMap(ASPECT_LITERAL_SIZES_2K)
                .modes(Set.of(ImageCapability.TEXT_TO_IMAGE))
                .supports(Set.of("size", "n", "seed", "negative_prompt", "prompt_extend", "watermark"))
                .maxCount(4)
                .maxInputImages(0)
                .build());
    }

    private static void addQwenImageEdit(Map<String, ImageModelSpec> m, String id) {
        m.put(id, ImageModelSpec.builder()
                .id(id)
                .displayName(id + " (image edit)")
                .endpoint(MULTIMODAL_ENDPOINT)
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                .sizeMap(ASPECT_LITERAL_SIZES_2K)
                .modes(Set.of(ImageCapability.IMAGE_EDIT))
                .supports(Set.of("size", "n", "seed", "negative_prompt", "prompt_extend", "watermark"))
                .maxCount(4)
                .maxInputImages(3)
                .build());
    }
}
