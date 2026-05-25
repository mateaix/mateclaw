package vip.mate.llm.model;

import java.util.Arrays;

public enum ModelProtocol {

    OPENAI_COMPATIBLE("openai-compatible", "OpenAIChatModel"),
    OPENAI_CHATGPT("openai-chatgpt", "ChatGPTChatModel"),
    ANTHROPIC_MESSAGES("anthropic-messages", "AnthropicChatModel"),
    /**
     * RFC-062: same Anthropic Messages API but authenticated with the user's
     * Claude Code OAuth token (Pro/Max subscription) instead of an API key.
     * Routed by {@code ClaudeCodeChatModelBuilder}.
     */
    ANTHROPIC_CLAUDE_CODE("anthropic-claude-code", "ClaudeCodeChatModel"),
    GEMINI_NATIVE("gemini-native", "GeminiChatModel"),
    DASHSCOPE_NATIVE("dashscope-native", "DashScopeChatModel");

    private final String id;
    private final String chatModelClass;

    ModelProtocol(String id, String chatModelClass) {
        this.id = id;
        this.chatModelClass = chatModelClass;
    }

    public String getId() {
        return id;
    }

    public String getChatModelClass() {
        return chatModelClass;
    }

    public static ModelProtocol fromChatModel(String chatModel) {
        if (chatModel == null || chatModel.isBlank()) {
            return OPENAI_COMPATIBLE;
        }
        return Arrays.stream(values())
                .filter(protocol -> protocol.chatModelClass.equalsIgnoreCase(chatModel.trim()))
                .findFirst()
                .orElse(OPENAI_COMPATIBLE);
    }

    public static ModelProtocol fromId(String protocolId) {
        if (protocolId == null || protocolId.isBlank()) {
            return OPENAI_COMPATIBLE;
        }
        return Arrays.stream(values())
                .filter(protocol -> protocol.id.equalsIgnoreCase(protocolId.trim()))
                .findFirst()
                .orElse(OPENAI_COMPATIBLE);
    }

    public static String resolveChatModel(String protocolId, String chatModel) {
        if (protocolId != null && !protocolId.isBlank()) {
            return fromId(protocolId).getChatModelClass();
        }
        if (chatModel != null && !chatModel.isBlank()) {
            return fromChatModel(chatModel).getChatModelClass();
        }
        return OPENAI_COMPATIBLE.getChatModelClass();
    }
}
