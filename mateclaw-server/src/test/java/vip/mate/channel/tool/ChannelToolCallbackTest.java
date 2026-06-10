package vip.mate.channel.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Pin the {@code renamed(actualName)} contract — {@link ChannelToolService}
 * relies on it to apply the {@code _c<channelId>} suffix without
 * forcing providers to know per-instance names.
 */
class ChannelToolCallbackTest {

    @Test
    @DisplayName("call delegates to the supplied handler")
    void callDelegates() {
        ChannelToolCallback cb = new ChannelToolCallback(
                "test_tool", "test description", "{\"type\":\"object\"}",
                in -> "echo:" + in);
        assertEquals("echo:hello", cb.call("hello"));
        assertEquals("echo:world", cb.call("world", null));
    }

    @Test
    @DisplayName("renamed returns a new callback carrying the same handler + description + schema")
    void renamedKeepsBehaviorWithNewName() {
        ChannelToolCallback original = new ChannelToolCallback(
                "feishu_doc_read", "read doc", "{}", in -> "READ:" + in);
        ToolCallback renamed = original.renamed("feishu_doc_read_c42");

        assertNotSame(original, renamed);
        assertEquals("feishu_doc_read_c42", renamed.getToolDefinition().name());
        assertEquals("read doc", renamed.getToolDefinition().description());
        assertEquals("READ:abc", renamed.call("abc"));
    }
}
