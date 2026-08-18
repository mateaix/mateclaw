package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import vip.mate.config.JacksonConfig;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiToolSchemaNumberPreservationTest {

    private static final String MAX_SAFE_INTEGER = "9007199254740991";

    @Test
    void preservesLongSchemaBoundsAsJsonNumbersWithApplicationMapper() throws Exception {
        OpenAiApi.ChatCompletionRequest request = requestWithSchema("""
                {
                  "type": "object",
                  "properties": {
                    "requestId": {
                      "type": "integer",
                      "minimum": -9007199254740991,
                      "maximum": 9007199254740991,
                      "examples": [9007199254740991]
                    }
                  }
                }
                """);
        ObjectMapper mapper = applicationMapper();

        JsonNode polluted = mapper.readTree(mapper.writeValueAsString(request));
        assertTrue(polluted.at("/tools/0/function/parameters/properties/requestId/maximum").isTextual(),
                "the regression fixture must reproduce the global Long-to-string pollution");

        OpenAiApi.ChatCompletionRequest sanitized =
                OpenAiRequestRewriter.preserveToolSchemaNumbers(request);
        JsonNode wireJson = mapper.readTree(mapper.writeValueAsString(sanitized));

        JsonNode property = wireJson.at("/tools/0/function/parameters/properties/requestId");
        assertTrue(property.get("minimum").isIntegralNumber());
        assertTrue(property.get("maximum").isIntegralNumber());
        assertTrue(property.at("/examples/0").isIntegralNumber());
        assertEquals(MAX_SAFE_INTEGER, property.get("maximum").asText());
        assertEquals("-" + MAX_SAFE_INTEGER, property.get("minimum").asText());

        Map<String, Object> originalProperty = propertyMap(request);
        Map<String, Object> sanitizedProperty = propertyMap(sanitized);
        assertInstanceOf(Long.class, originalProperty.get("maximum"));
        assertInstanceOf(BigInteger.class, sanitizedProperty.get("maximum"));
        assertNotSame(request, sanitized);
        assertEquals("browser_network_requests", sanitized.tools().getFirst().getFunction().getName());
        assertEquals(Boolean.TRUE, sanitized.tools().getFirst().getFunction().getStrict());
    }

    @Test
    void returnsOriginalRequestWhenSchemaContainsNoLongs() {
        OpenAiApi.ChatCompletionRequest request = requestWithSchema("""
                {"type":"object","properties":{"limit":{"type":"integer","maximum":100}}}
                """);

        assertSame(request, OpenAiRequestRewriter.preserveToolSchemaNumbers(request));
    }

    @Test
    void returnsOriginalRequestWhenNoToolsArePresent() {
        OpenAiApi.ChatCompletionRequest request =
                new OpenAiApi.ChatCompletionRequest(List.of(), "deepseek-chat", List.of(), null);

        assertSame(request, OpenAiRequestRewriter.preserveToolSchemaNumbers(request));
    }

    private static OpenAiApi.ChatCompletionRequest requestWithSchema(String schema) {
        OpenAiApi.FunctionTool.Function function = new OpenAiApi.FunctionTool.Function(
                "Inspect browser network requests", "browser_network_requests", schema);
        function.setStrict(true);
        OpenAiApi.FunctionTool tool = new OpenAiApi.FunctionTool(function);
        return new OpenAiApi.ChatCompletionRequest(
                List.of(), "deepseek-chat", List.of(tool), "auto");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertyMap(OpenAiApi.ChatCompletionRequest request) {
        Map<String, Object> properties = (Map<String, Object>)
                request.tools().getFirst().getFunction().getParameters().get("properties");
        return (Map<String, Object>) properties.get("requestId");
    }

    private static ObjectMapper applicationMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        return builder.build();
    }
}
