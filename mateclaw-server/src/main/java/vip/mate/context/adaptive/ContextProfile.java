package vip.mate.context.adaptive;

public enum ContextProfile {
    DYNAMIC_CHAT(true, false),
    DYNAMIC_REASONING(true, true),
    FIXED_CHAT(false, false),
    FIXED_EMBEDDING(false, false),
    FIXED_RERANK(false, false),
    AUDIO(false, false),
    IMAGE_GEN(false, false),
    IMAGE_VISION(false, false),
    VIDEO(false, false),
    NOT_APPLICABLE(false, false);

    private final boolean dynamic;
    private final boolean reasoning;
    ContextProfile(boolean dynamic, boolean reasoning) { this.dynamic = dynamic; this.reasoning = reasoning; }
    public boolean isDynamic() { return dynamic; }
    public boolean isReasoning() { return reasoning; }
    public boolean hasTextContext() { return this == DYNAMIC_CHAT || this == DYNAMIC_REASONING || this == FIXED_CHAT || this == FIXED_EMBEDDING || this == FIXED_RERANK; }

    /** Map DB mate_model_config.model_type to ContextProfile. Unknown/null → DYNAMIC_CHAT for safety. */
    public static ContextProfile fromModelType(String modelType) {
        if (modelType == null || modelType.isBlank()) return DYNAMIC_CHAT;
        return switch (modelType.trim().toLowerCase()) {
            case "chat" -> DYNAMIC_CHAT;
            case "reasoning", "thinking" -> DYNAMIC_REASONING;
            case "embedding" -> FIXED_EMBEDDING;
            case "rerank", "reranker" -> FIXED_RERANK;
            case "audio", "tts", "stt" -> AUDIO;
            case "image" -> IMAGE_GEN;
            case "image-vision", "vision" -> IMAGE_VISION;
            case "video" -> VIDEO;
            default -> DYNAMIC_CHAT;
        };
    }
}
