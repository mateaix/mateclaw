package vip.mate.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link ModelDiscoveryService#buildTestPromptRequestBody}.
 *
 * <p>Prior to this fix, the Model Management "Test Model" / "Test Connection" button
 * (backed by {@code sendOpenAiTestPrompt}) built its outbound request body from a
 * hard-coded {@code Map.of(model, messages, max_tokens, temperature)} and never
 * consulted {@code generateKwargs} at all (beyond {@code completionsPath} and
 * {@code customHeaders}, applied separately). So an admin-configured passthrough
 * key like vLLM's {@code chat_template_kwargs} (to disable Qwen thinking mode) was
 * silently dropped on the test path even after the runtime chat path
 * ({@code OpenAiCompatibleChatModelBuilder#buildOpenAiOptions}) started forwarding it
 * — "I configured disable-thinking but the UI test still shows thinking enabled".
 */
class ModelDiscoveryServiceTestPromptTest {

    @Test
    @DisplayName("Unrecognized top-level key (chat_template_kwargs) is forwarded into the test request body")
    void unknownKey_chatTemplateKwargs_forwardedToRequestBody() {
        Map<String, Object> chatTemplateKwargs = Map.of("enable_thinking", false);
        Map<String, Object> kwargs = Map.of("chat_template_kwargs", chatTemplateKwargs);

        Map<String, Object> requestBody = ModelDiscoveryService.buildTestPromptRequestBody("qwen3-32b", kwargs);

        assertEquals(chatTemplateKwargs, requestBody.get("chat_template_kwargs"),
                "the nested map must be forwarded verbatim, not flattened or re-wrapped");
        assertEquals("qwen3-32b", requestBody.get("model"));
        assertEquals(10, requestBody.get("max_tokens"));
        assertEquals(0, requestBody.get("temperature"));
    }

    @Test
    @DisplayName("Reserved key (temperature) in generateKwargs does not override the fixed smoke-test values")
    void reservedKey_doesNotOverrideFixedProbeFields() {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("temperature", 0.9);
        kwargs.put("maxTokens", 4096);
        kwargs.put("chat_template_kwargs", Map.of("enable_thinking", false));

        Map<String, Object> requestBody = ModelDiscoveryService.buildTestPromptRequestBody("qwen3-32b", kwargs);

        assertEquals(0, requestBody.get("temperature"),
                "the probe's fixed temperature=0 must win over a reserved generateKwargs key");
        assertEquals(10, requestBody.get("max_tokens"),
                "the probe's fixed max_tokens=10 must win over a reserved generateKwargs key");
        assertFalse(requestBody.containsKey("maxTokens"),
                "reserved keys (even in their original casing) must not leak into the body verbatim");
        assertTrue(requestBody.containsKey("chat_template_kwargs"),
                "the unrecognized key must still be forwarded alongside the fixed probe fields");
    }

    @Test
    @DisplayName("customHeaders is reserved (consumed as real HTTP headers) and must not leak into the JSON body")
    void customHeaders_doesNotLeakIntoRequestBody() {
        Map<String, Object> kwargs = Map.of("customHeaders", Map.of("X-Foo", "bar"));

        Map<String, Object> requestBody = ModelDiscoveryService.buildTestPromptRequestBody("qwen3-32b", kwargs);

        assertFalse(requestBody.containsKey("customHeaders"),
                "customHeaders is applied via applyCustomHeaders() as real HTTP headers, not as a body field");
    }

    @Test
    @DisplayName("modelsPath is reserved (consumed by model discovery) and must not leak into the JSON body")
    void modelsPath_doesNotLeakIntoRequestBody() {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("modelsPath", "/openai/v1/models");
        kwargs.put("chat_template_kwargs", Map.of("enable_thinking", false));

        Map<String, Object> requestBody = ModelDiscoveryService.buildTestPromptRequestBody("qwen3-32b", kwargs);

        assertFalse(requestBody.containsKey("modelsPath"),
                "modelsPath configures the list-models endpoint and is not a chat completion body field");
        assertTrue(requestBody.containsKey("chat_template_kwargs"),
                "unrecognized passthrough keys must still be forwarded");
    }

    @Test
    @DisplayName("Empty or null generateKwargs: request body contains only the fixed probe fields")
    void emptyOrNullGenerateKwargs_onlyFixedFields() {
        Map<String, Object> requestBody = ModelDiscoveryService.buildTestPromptRequestBody("gpt-4-turbo", Map.of());

        assertEquals(Set.of("model", "messages", "max_tokens", "temperature"), requestBody.keySet());
        assertEquals("gpt-4-turbo", requestBody.get("model"));
        assertEquals(10, requestBody.get("max_tokens"));
        assertEquals(0, requestBody.get("temperature"));

        Map<String, Object> requestBodyFromNull = ModelDiscoveryService.buildTestPromptRequestBody("gpt-4-turbo", null);
        assertEquals(requestBody, requestBodyFromNull);
    }
}
