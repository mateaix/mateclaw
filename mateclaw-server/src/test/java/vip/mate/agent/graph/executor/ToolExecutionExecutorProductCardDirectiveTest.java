package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cross-platform shopping recommendation tool is globally callable, so a
 * model can invoke it without ever loading the skill's instructions. The
 * executor appends a card-rendering directive to that tool's result so products
 * render as chat cards rather than a markdown table — but only when the result
 * actually carries product records (timeouts / empty results must fall through
 * to the model's own fallback).
 */
class ToolExecutionExecutorProductCardDirectiveTest {

    private static final String SHOPPING_TOOL = "mcp_1000000903_ckjia_shopping_recom_w2ekrl";

    @Test
    @DisplayName("appends the directive when the shopping tool returns recommendations")
    void appendsForShoppingResults() {
        String result = "[{\"text\":\"{\\\"recommendations\\\":[{\\\"name\\\":\\\"X\\\",\\\"imageUrl\\\":\\\"https://i\\\"}]}\"}]";
        assertTrue(ToolExecutionExecutor.shouldAppendProductCardDirective(SHOPPING_TOOL, result));

        String decorated = ToolExecutionExecutor.withProductCardDirective(SHOPPING_TOOL, result);
        assertTrue(decorated.startsWith(result), "original payload must be preserved verbatim");
        assertTrue(decorated.contains("product-cards"), "directive names the fence language");
        assertTrue(decorated.contains("imageUrl"), "directive lists the card fields");
    }

    @Test
    @DisplayName("does not append on a timeout / error result from the shopping tool")
    void skipsForTimeout() {
        String timeout = "Tool execution failed: java.util.concurrent.TimeoutException: Did not observe any item";
        assertFalse(ToolExecutionExecutor.shouldAppendProductCardDirective(SHOPPING_TOOL, timeout));
        assertEquals(timeout, ToolExecutionExecutor.withProductCardDirective(SHOPPING_TOOL, timeout));
    }

    @Test
    @DisplayName("does not append for unrelated tools even when the body looks product-ish")
    void skipsForOtherTools() {
        String body = "{\"recommendations\":[{\"imageUrl\":\"https://i\"}]}";
        assertFalse(ToolExecutionExecutor.shouldAppendProductCardDirective("web_search", body));
        assertEquals(body, ToolExecutionExecutor.withProductCardDirective("web_search", body));
    }

    @Test
    @DisplayName("null-safe")
    void nullSafe() {
        assertFalse(ToolExecutionExecutor.shouldAppendProductCardDirective(null, "x"));
        assertFalse(ToolExecutionExecutor.shouldAppendProductCardDirective(SHOPPING_TOOL, null));
    }
}
