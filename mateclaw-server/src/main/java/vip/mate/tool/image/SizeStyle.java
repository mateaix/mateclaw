package vip.mate.tool.image;

/**
 * Describes how a particular image-generation model expects its size to be
 * expressed. Three families cover all current providers:
 *
 * <ul>
 *   <li>{@link #LITERAL_DIMENSION} — explicit width/height string ({@code 1024x1024},
 *       {@code 1536*1024}). Used by DashScope, OpenAI DALL-E, MiniMax.</li>
 *   <li>{@link #ASPECT_RATIO} — preset enum like {@code 16:9} or {@code 1:1}.
 *       Used by Gemini / nano-banana style APIs.</li>
 *   <li>{@link #PRESET_NAME} — provider-specific preset label
 *       ({@code square_hd}, {@code landscape_16_9}). Used by fal.ai's flux,
 *       z-image, qwen-image families.</li>
 * </ul>
 *
 * Each {@link ImageModelSpec} declares one style and provides the sizeMap that
 * translates the unified {@code aspectRatio} input ({@code landscape} /
 * {@code square} / {@code portrait} or a literal ratio) to the model-native form.
 *
 * @author MateClaw Team
 */
public enum SizeStyle {
    LITERAL_DIMENSION,
    ASPECT_RATIO,
    PRESET_NAME
}
