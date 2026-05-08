package vip.mate.tool.image;

import lombok.Builder;
import lombok.Singular;

import java.util.Map;
import java.util.Set;

/**
 * Per-model descriptor that drives payload construction without {@code if/else}
 * chains inside provider classes. Adding a new model = adding a new spec entry.
 *
 * <p>Three things make this configuration-driven:
 * <ul>
 *   <li>{@code endpoint} chooses which provider URL to hit. A single provider
 *       (e.g. DashScope) can host both an async legacy endpoint and a unified
 *       multimodal endpoint — the spec routes per model.</li>
 *   <li>{@code transport} ({@link Transport#SYNC} / {@link Transport#ASYNC})
 *       lets the provider pick between immediate-return and submit+poll without
 *       hard-coding the choice.</li>
 *   <li>{@code supports} acts as a payload key whitelist. Build the full payload
 *       freely, then filter against {@code supports} so models never receive
 *       fields they reject.</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Builder
public record ImageModelSpec(
        String id,
        String displayName,
        String endpoint,
        Transport transport,
        SizeStyle sizeStyle,
        @Singular("sizeMapping") Map<String, String> sizeMap,
        @Singular("defaultParam") Map<String, Object> defaults,
        @Singular Set<String> supports,
        @Singular Set<ImageCapability> modes,
        int maxInputImages,
        int maxCount
) {

    public enum Transport {
        /** Provider returns image bytes / URL in the same HTTP response. */
        SYNC,
        /** Provider returns a task id; caller polls a status endpoint. */
        ASYNC
    }

    public boolean supportsEdit() {
        return modes != null && modes.contains(ImageCapability.IMAGE_EDIT);
    }

    public boolean supportsGenerate() {
        return modes != null && modes.contains(ImageCapability.TEXT_TO_IMAGE);
    }
}
