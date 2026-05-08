package vip.mate.tool.image;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Unified image-generation request.
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class ImageGenerationRequest {

    /** Prompt describing the desired image. */
    private String prompt;

    /** Generation mode (inferred by the runtime when null). */
    private ImageCapability mode;

    /** Model id; provider supplies a default when null/blank. */
    private String model;

    /** Pixel size like {@code 1024x1024} / {@code 1024x1792}. */
    @Builder.Default
    private String size = "1024x1024";

    /** Aspect ratio: {@code 1:1} / {@code 16:9} / {@code 9:16}. */
    @Builder.Default
    private String aspectRatio = "1:1";

    /** Number of images to return. */
    @Builder.Default
    private Integer count = 1;

    /**
     * Reference images for edit / image-to-image flows. Loaded as in-memory
     * buffers so providers can either inline base64, upload via multipart, or
     * forward as a URL — without each provider re-implementing path/URL/data
     * resolution.
     */
    @Builder.Default
    private List<ImageReference> inputImages = List.of();

    /** Provider-specific extras forwarded as-is. */
    private Map<String, Object> extraParams;
}
