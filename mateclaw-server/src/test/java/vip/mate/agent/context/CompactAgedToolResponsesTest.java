package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.agent.graph.executor.ToolResultStorage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ConversationWindowManager#compactAgedToolResponses} — the
 * age-based pass that drops bodies of tool responses older than the K most
 * recent into a placeholder while preserving the toolCallId / tool name so
 * the model still sees "I called X earlier" in history. Complements (does
 * not replace) the existing size/dedup-based prune pass.
 */
class CompactAgedToolResponsesTest {

    private static final ConversationWindowManager MANAGER = new ConversationWindowManager(
            null, null, null);

    private static ToolResponseMessage toolResp(String id, String name, String body) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, body)))
                .build();
    }

    private static String spillBody(String tool, String path) {
        return ToolResultStorage.SPILL_MARKER_PREFIX + " tool=" + tool + " full_chars=12345 path="
                + path
                + "\n[Preview — first 800 of 12345 chars. The preview is INCOMPLETE: use read_file]\n"
                + "<preview content>\n…[truncated]";
    }

    @Test
    @DisplayName("keepRecentN=0 or negative is a no-op (returns same list reference).")
    void zeroKeepIsNoop() {
        List<Message> in = List.of(toolResp("t1", "search", "a body".repeat(50)));
        assertSame(in, MANAGER.compactAgedToolResponses(in, 0));
        assertSame(in, MANAGER.compactAgedToolResponses(in, -1));
    }

    @Test
    @DisplayName("Latest K tool responses are kept verbatim; older ones get the placeholder.")
    void keepsLatestKVerbatim() {
        String body = "abcdef".repeat(50);  // 300 chars — guaranteed larger than placeholder
        List<Message> in = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            in.add(toolResp("call-" + i, "search", "result-" + i + " " + body));
        }
        List<Message> out = MANAGER.compactAgedToolResponses(in, 2);
        // out[3] and out[4] are the two newest — verbatim.
        assertTrue(((ToolResponseMessage) out.get(3)).getResponses().get(0).responseData().contains("result-3"));
        assertTrue(((ToolResponseMessage) out.get(4)).getResponses().get(0).responseData().contains("result-4"));
        // out[0..2] are older — compacted.
        for (int i = 0; i <= 2; i++) {
            String compactedBody = ((ToolResponseMessage) out.get(i)).getResponses().get(0).responseData();
            assertTrue(compactedBody.startsWith("[Old tool output cleared"),
                    "expected placeholder at index " + i + ", got: " + compactedBody);
        }
    }

    @Test
    @DisplayName("Tool name and toolCallId survive the rewrite so the assistant/tool pair stays valid.")
    void preservesIdAndName() {
        String big = "a".repeat(500);
        List<Message> in = List.of(
                toolResp("call-old", "search", big),
                toolResp("call-mid", "search", big),
                toolResp("call-new", "search", big));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        ToolResponseMessage.ToolResponse old = ((ToolResponseMessage) out.get(0)).getResponses().get(0);
        assertEquals("call-old", old.id());
        assertEquals("search", old.name());
        assertNotEquals(big, old.responseData());
        // Latest stays verbatim.
        assertEquals(big, ((ToolResponseMessage) out.get(2)).getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("Spill-marker bodies keep their on-disk path inside the placeholder for read_file recovery.")
    void spillPathPreserved() {
        String body = spillBody("browser_use", "/tmp/mateclaw/tool-results/conv-1/tool_abc.txt");
        List<Message> in = List.of(
                toolResp("call-old", "browser_use", body),
                toolResp("call-new", "search", "a".repeat(500)));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        String compacted = ((ToolResponseMessage) out.get(0)).getResponses().get(0).responseData();
        assertTrue(compacted.contains("/tmp/mateclaw/tool-results/conv-1/tool_abc.txt"),
                "spill path should be preserved in placeholder: " + compacted);
        assertTrue(compacted.contains("read_file"), compacted);
    }

    @Test
    @DisplayName("Exempt tools (delegateToAgent / delegateParallel) skip compaction entirely.")
    void exemptToolsSkipped() {
        String big = "x".repeat(500);
        List<Message> in = List.of(
                toolResp("d-old", "delegateToAgent", big),
                toolResp("s-old", "search", big),
                toolResp("s-new", "search", big));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        assertEquals(big, ((ToolResponseMessage) out.get(0)).getResponses().get(0).responseData());  // exempt
        assertTrue(((ToolResponseMessage) out.get(1)).getResponses().get(0).responseData()
                .startsWith("[Old tool output cleared"));  // non-exempt aged → compacted
        assertEquals(big, ((ToolResponseMessage) out.get(2)).getResponses().get(0).responseData());  // newest kept
    }

    @Test
    @DisplayName("A body shorter than the placeholder itself is kept verbatim — no negative savings.")
    void tinyBodyNotInflated() {
        List<Message> in = List.of(
                toolResp("old", "ping", "ok"),
                toolResp("new", "ping", "ok"));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        assertEquals("ok", ((ToolResponseMessage) out.get(0)).getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("Non-tool messages (user / assistant) are passed through untouched.")
    void nonToolMessagesUntouched() {
        Message user = new UserMessage("hello");
        Message assistant = new AssistantMessage("hi");
        List<Message> in = List.of(user, assistant,
                toolResp("old", "search", "x".repeat(500)),
                toolResp("new", "search", "y".repeat(500)));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        assertSame(user, out.get(0));
        assertSame(assistant, out.get(1));
    }

    @Test
    @DisplayName("buildAgedPlaceholder spill-path extraction handles trailing newline boundary.")
    void buildPlaceholderSpillPath() {
        String body = spillBody("browser_use", "/a/b/c.txt");
        String out = ConversationWindowManager.buildAgedPlaceholder("browser_use", body);
        assertNotNull(out);
        assertTrue(out.contains("/a/b/c.txt"), out);
    }

    @Test
    @DisplayName("buildAgedPlaceholder falls back to plain text when no spill path is present.")
    void buildPlaceholderPlainBody() {
        String out = ConversationWindowManager.buildAgedPlaceholder("search", "regular result body");
        assertTrue(out.contains("'search'"), out);
        assertTrue(out.contains("can be called again"), out);
    }
}
