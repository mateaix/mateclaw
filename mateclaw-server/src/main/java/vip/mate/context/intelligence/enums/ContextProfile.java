package vip.mate.context.intelligence.enums;

/**
 * Model context profile enum.
 * <p>
 * Used for gating: {@code profile.isDynamic() && profile.hasTextContext()} determines whether
 * a model participates in context intelligence (dynamic window probing + budget planning).
 * <p>
 * The v2 initial release does not implement modelType routing (chat vs reasoning allocation ratio
 * differences); it is only used to determine whether context intelligence is enabled.
 *
 * @author MateClaw Team
 */
public enum ContextProfile {

    /** Dynamic chat model, participates in context intelligence */
    DYNAMIC_CHAT(true, true),

    /** Dynamic reasoning model, participates in context intelligence */
    DYNAMIC_REASONING(true, true),

    /** Fixed-window chat model, does not participate in dynamic probing */
    FIXED_CHAT(false, true),

    /** Fixed-window reasoning model, does not participate in dynamic probing */
    FIXED_REASONING(false, true),

    /** Embedding model, no text context */
    FIXED_EMBEDDING(false, false),

    /** Rerank model, no text context */
    FIXED_RERANK(false, false),

    /** Audio model */
    AUDIO(false, false),

    /** Image generation model */
    IMAGE_GEN(false, false),

    /** Image understanding model */
    IMAGE_VISION(false, false),

    /** Video model */
    VIDEO(false, false),

    /** Not applicable */
    NOT_APPLICABLE(false, false);

    private final boolean dynamic;
    private final boolean textContext;

    ContextProfile(boolean dynamic, boolean textContext) {
        this.dynamic = dynamic;
        this.textContext = textContext;
    }

    /** Whether this is a dynamic-window model (participates in WindowProbe probing) */
    public boolean isDynamic() {
        return dynamic;
    }

    /** Whether the model has text context (participates in TokenBudgetPlanner budget planning) */
    public boolean hasTextContext() {
        return textContext;
    }

    /**
     * Infer the profile from a modelType string.
     * <p>
     * Common values: {@code chat} / {@code reasoning} / {@code code} / {@code embedding} /
     * {@code rerank} / {@code audio} / {@code image_gen} / {@code image_vision} / {@code video}
     *
     * @param modelType model type string (from DB model_type or state RUNTIME_MODEL_TYPE)
     * @return the corresponding ContextProfile, defaults to DYNAMIC_CHAT
     */
    public static ContextProfile fromModelType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return DYNAMIC_CHAT;
        }
        String t = modelType.trim().toLowerCase();
        return switch (t) {
            case "chat" -> DYNAMIC_CHAT;
            case "reasoning" -> DYNAMIC_REASONING;
            case "code" -> DYNAMIC_CHAT;
            case "embedding" -> FIXED_EMBEDDING;
            case "rerank" -> FIXED_RERANK;
            case "audio" -> AUDIO;
            case "image_gen", "image-gen", "imagegen" -> IMAGE_GEN;
            case "image_vision", "image-vision", "imagevision" -> IMAGE_VISION;
            case "video" -> VIDEO;
            default -> DYNAMIC_CHAT;
        };
    }
}
