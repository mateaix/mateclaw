package vip.mate.llm.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the protocol → discovery-capability table and the {@link ModelProtocol#resolve}
 * fallback chain. The capability flag drives the default {@code supportModelDiscovery}
 * of user-created custom providers (see {@code ModelProviderService.createCustomProvider}),
 * so a wrong entry here silently hides — or wrongly shows — the "discover models" button.
 */
class ModelProtocolTest {

    @Test
    @DisplayName("baseUrl+apiKey protocols support discovery; OAuth protocols do not")
    void discoveryCapabilityTable() {
        assertTrue(ModelProtocol.OPENAI_COMPATIBLE.supportsSelfConfiguredDiscovery());
        assertTrue(ModelProtocol.ANTHROPIC_MESSAGES.supportsSelfConfiguredDiscovery());
        assertTrue(ModelProtocol.GEMINI_NATIVE.supportsSelfConfiguredDiscovery());
        assertTrue(ModelProtocol.DASHSCOPE_NATIVE.supportsSelfConfiguredDiscovery());

        // OAuth-based: discovery hangs off a separately established OAuth session,
        // not the provider row's baseUrl/apiKey — so a self-configured custom
        // provider must not default the button on.
        assertFalse(ModelProtocol.OPENAI_CHATGPT.supportsSelfConfiguredDiscovery());
        assertFalse(ModelProtocol.ANTHROPIC_CLAUDE_CODE.supportsSelfConfiguredDiscovery());
    }

    @Test
    @DisplayName("resolve() prefers explicit protocol id over chat-model class")
    void resolvePrefersProtocolId() {
        assertEquals(ModelProtocol.DASHSCOPE_NATIVE,
                ModelProtocol.resolve("dashscope-native", "OpenAIChatModel"));
    }

    @Test
    @DisplayName("resolve() falls back to chat-model class when protocol id is blank")
    void resolveFallsBackToChatModel() {
        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES,
                ModelProtocol.resolve(null, "AnthropicChatModel"));
        assertEquals(ModelProtocol.GEMINI_NATIVE,
                ModelProtocol.resolve("  ", "GeminiChatModel"));
    }

    @Test
    @DisplayName("resolve() defaults to OpenAI-compatible when nothing is supplied or recognized")
    void resolveDefaultsToOpenAiCompatible() {
        assertEquals(ModelProtocol.OPENAI_COMPATIBLE, ModelProtocol.resolve(null, null));
        assertEquals(ModelProtocol.OPENAI_COMPATIBLE, ModelProtocol.resolve("no-such-proto", null));
    }

    @Test
    @DisplayName("resolveChatModel() stays consistent with resolve().getChatModelClass()")
    void resolveChatModelConsistency() {
        assertEquals(ModelProtocol.resolve("dashscope-native", null).getChatModelClass(),
                ModelProtocol.resolveChatModel("dashscope-native", null));
        assertEquals(ModelProtocol.OPENAI_COMPATIBLE.getChatModelClass(),
                ModelProtocol.resolveChatModel(null, null));
    }
}
