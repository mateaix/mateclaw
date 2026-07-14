package vip.mate.llm.model;

import java.util.Arrays;

public enum ModelProtocol {

    OPENAI_COMPATIBLE("openai-compatible", "OpenAIChatModel", true),
    // OAuth-based: discovery relies on a separately established OAuth session
    // (stored, auto-refreshed access token) rather than the provider row's
    // baseUrl/apiKey, so a self-configured custom provider cannot drive it.
    OPENAI_CHATGPT("openai-chatgpt", "ChatGPTChatModel", false),
    ANTHROPIC_MESSAGES("anthropic-messages", "AnthropicChatModel", true),
    /**
     * RFC-062: same Anthropic Messages API but authenticated with the user's
     * Claude Code OAuth token (Pro/Max subscription) instead of an API key.
     * Routed by {@code ClaudeCodeChatModelBuilder}. Has no discovery endpoint
     * (fixed, Flyway-seeded catalog), so discovery is unsupported.
     */
    ANTHROPIC_CLAUDE_CODE("anthropic-claude-code", "ClaudeCodeChatModel", false),
    GEMINI_NATIVE("gemini-native", "GeminiChatModel", true),
    DASHSCOPE_NATIVE("dashscope-native", "DashScopeChatModel", true);

    private final String id;
    private final String chatModelClass;
    private final boolean supportsSelfConfiguredDiscovery;

    ModelProtocol(String id, String chatModelClass, boolean supportsSelfConfiguredDiscovery) {
        this.id = id;
        this.chatModelClass = chatModelClass;
        this.supportsSelfConfiguredDiscovery = supportsSelfConfiguredDiscovery;
    }

    public String getId() {
        return id;
    }

    public String getChatModelClass() {
        return chatModelClass;
    }

    /**
     * Whether a self-configured provider (baseUrl + apiKey) of this protocol can
     * drive model discovery. Used to decide the default {@code supportModelDiscovery}
     * flag for user-created custom providers. OAuth-based protocols return false:
     * their discovery hangs off a separately established OAuth session, not the
     * provider row's baseUrl/apiKey.
     *
     * <p><b>Note:</b> this is narrower than "can this protocol ever discover" —
     * the built-in ChatGPT-OAuth provider <em>does</em> discover (via its OAuth
     * session) yet this returns false for {@code OPENAI_CHATGPT}. Do not reuse
     * this to gate the discover button in general; it answers only the custom-
     * provider default.
     */
    public boolean supportsSelfConfiguredDiscovery() {
        return supportsSelfConfiguredDiscovery;
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

    /**
     * Resolve the effective protocol from an explicit protocol id, falling back
     * to inference from the chat-model class, and finally to
     * {@link #OPENAI_COMPATIBLE}. Single source of truth so callers can derive
     * both the chat-model class and capability flags (e.g. {@link #supportsSelfConfiguredDiscovery()})
     * from one consistent resolution.
     */
    public static ModelProtocol resolve(String protocolId, String chatModel) {
        if (protocolId != null && !protocolId.isBlank()) {
            return fromId(protocolId);
        }
        if (chatModel != null && !chatModel.isBlank()) {
            return fromChatModel(chatModel);
        }
        return OPENAI_COMPATIBLE;
    }

    public static String resolveChatModel(String protocolId, String chatModel) {
        return resolve(protocolId, chatModel).getChatModelClass();
    }
}
