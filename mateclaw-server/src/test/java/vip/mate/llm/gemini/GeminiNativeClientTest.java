package vip.mate.llm.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.llm.gemini.GeminiNativeClient.GeminiCall;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GeminiNativeClient#buildRequestBody} — the Spring AI
 * {@code Message} list → Gemini {@code generateContent} request translation.
 */
class GeminiNativeClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiNativeClient client = new GeminiNativeClient(mapper);

    private GeminiCall call(List<Message> messages, List<ToolDefinition> tools) {
        return new GeminiCall("https://generativelanguage.googleapis.com", "test-key",
                "gemini-3-pro-preview", messages, 0.7, 4096, tools);
    }

    @Test
    @DisplayName("system message is hoisted into systemInstruction")
    void systemMessageBecomesSystemInstruction() {
        ObjectNode body = client.buildRequestBody(call(
                List.of(new SystemMessage("You are helpful"), new UserMessage("Hi")), null));

        assertEquals("You are helpful",
                body.path("systemInstruction").path("parts").path(0).path("text").asText());
        // The system turn must NOT also appear in contents.
        assertEquals(1, body.path("contents").size());
        assertEquals("user", body.path("contents").path(0).path("role").asText());
    }

    @Test
    @DisplayName("user message maps to a user-role text part")
    void userMessageMapsToUserContent() {
        ObjectNode body = client.buildRequestBody(call(
                List.of(new UserMessage("What is the weather?")), null));

        JsonNode content = body.path("contents").path(0);
        assertEquals("user", content.path("role").asText());
        assertEquals("What is the weather?", content.path("parts").path(0).path("text").asText());
    }

    @Test
    @DisplayName("assistant tool call maps to a model-role functionCall part")
    void assistantToolCallMapsToFunctionCall() {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "get_weather", "{\"city\":\"NYC\"}")))
                .build();
        ObjectNode body = client.buildRequestBody(call(
                List.of(new UserMessage("weather?"), assistant), null));

        JsonNode modelContent = body.path("contents").path(1);
        assertEquals("model", modelContent.path("role").asText());
        JsonNode functionCall = modelContent.path("parts").path(0).path("functionCall");
        assertEquals("get_weather", functionCall.path("name").asText());
        assertEquals("NYC", functionCall.path("args").path("city").asText());
    }

    @Test
    @DisplayName("tool response maps to a user-role functionResponse with an object payload")
    void toolResponseMapsToFunctionResponse() {
        ToolResponseMessage toolMsg = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("call_1", "get_weather", "{\"temp\":20}")))
                .build();
        ObjectNode body = client.buildRequestBody(call(
                List.of(new UserMessage("weather?"), toolMsg), null));

        JsonNode functionResponse = body.path("contents").path(1)
                .path("parts").path(0).path("functionResponse");
        assertEquals("get_weather", functionResponse.path("name").asText());
        assertEquals(20, functionResponse.path("response").path("temp").asInt());
    }

    @Test
    @DisplayName("non-object tool response is wrapped under a result key")
    void nonObjectToolResponseIsWrapped() {
        ToolResponseMessage toolMsg = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("call_1", "echo", "plain text result")))
                .build();
        ObjectNode body = client.buildRequestBody(call(
                List.of(new UserMessage("echo"), toolMsg), null));

        JsonNode response = body.path("contents").path(1)
                .path("parts").path(0).path("functionResponse").path("response");
        assertTrue(response.isObject());
        assertEquals("plain text result", response.path("result").asText());
    }

    @Test
    @DisplayName("tool definitions become sanitized functionDeclarations")
    void toolsBecomeFunctionDeclarations() {
        ToolDefinition tool = ToolDefinition.builder()
                .name("get_weather")
                .description("Get the weather for a city")
                .inputSchema("{\"$schema\":\"x\",\"type\":\"object\","
                        + "\"properties\":{\"city\":{\"type\":\"string\"}}}")
                .build();
        ObjectNode body = client.buildRequestBody(call(
                List.of(new UserMessage("weather?")), List.of(tool)));

        JsonNode decl = body.path("tools").path(0).path("functionDeclarations").path(0);
        assertEquals("get_weather", decl.path("name").asText());
        assertEquals("Get the weather for a city", decl.path("description").asText());
        assertEquals("string", decl.path("parameters").path("properties").path("city").path("type").asText());
        assertFalse(decl.path("parameters").has("$schema"), "schema must be sanitized");
    }

    @Test
    @DisplayName("generationConfig carries temperature and maxOutputTokens")
    void generationConfigCarriesSamplingParams() {
        ObjectNode body = client.buildRequestBody(call(
                List.of(new UserMessage("hi")), null));

        assertEquals(0.7, body.path("generationConfig").path("temperature").asDouble(), 1e-9);
        assertEquals(4096, body.path("generationConfig").path("maxOutputTokens").asInt());
    }
}
