package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserUseToolContractTest {

    @Test
    @DisplayName("CDP params are exposed to the model as a structured object")
    void cdpParamsUseStructuredMapSchema() throws NoSuchMethodException {
        Method method = BrowserUseTool.class.getMethod("browser_use",
                String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, Integer.class, String.class, String.class,
                Map.class, String.class, Boolean.class, Integer.class, ToolContext.class);

        assertEquals(Map.class, method.getParameterTypes()[10]);
    }

    @Test
    @DisplayName("Spring AI publishes CDP params as a JSON object schema")
    void generatedToolSchemaUsesObjectParams() {
        BrowserUseTool tool = new BrowserUseTool(null, null, null, null, null);

        String schema = ToolCallbacks.from(tool)[0].getToolDefinition().inputSchema();

        assertTrue(schema.matches("(?s).*\\\"params\\\"\\s*:\\s*\\{.*?\\\"type\\\"\\s*:\\s*\\\"object\\\".*"), schema);
    }
}
