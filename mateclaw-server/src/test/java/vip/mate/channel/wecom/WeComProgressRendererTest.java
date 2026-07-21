package vip.mate.channel.wecom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeComProgressRendererTest {

    @Test
    @DisplayName("initial snapshot shows a thinking status with elapsed time")
    void initialSnapshot() {
        WeComProgressRenderer r = new WeComProgressRenderer(System.currentTimeMillis(), false);
        String s = r.snapshot();
        assertTrue(s.contains("思考中"), s);
        assertTrue(s.contains("已 "), s);
    }

    @Test
    @DisplayName("tool start flips status to the running tool and requests an immediate flush")
    void toolStartUpdatesStatus() {
        WeComProgressRenderer r = new WeComProgressRenderer(System.currentTimeMillis(), false);
        boolean flush = r.onEvent("tool_call_started",
                Map.of("toolCallId", "c1", "toolName", "get_weather"));
        assertTrue(flush);
        String s = r.snapshot();
        assertTrue(s.contains("正在调用 get_weather"), s);
    }

    @Test
    @DisplayName("tool completion renders a checked line with duration")
    void toolCompletionRendersLine() {
        WeComProgressRenderer r = new WeComProgressRenderer(System.currentTimeMillis(), false);
        r.onEvent("tool_call_started", Map.of("toolCallId", "c1", "toolName", "get_weather"));
        r.onEvent("tool_call_completed",
                Map.of("toolCallId", "c1", "toolName", "get_weather", "success", true));
        String s = r.snapshot();
        assertTrue(s.contains("✅ get_weather 完成"), s);
        assertFalse(s.contains("正在调用"), s);
    }

    @Test
    @DisplayName("failed tool renders a cross line")
    void toolFailureRendersCross() {
        WeComProgressRenderer r = new WeComProgressRenderer(System.currentTimeMillis(), false);
        r.onEvent("tool_call_started", Map.of("toolCallId", "c1", "toolName", "search"));
        r.onEvent("tool_call_completed",
                Map.of("toolCallId", "c1", "toolName", "search", "success", false));
        String s = r.snapshot();
        assertTrue(s.contains("❌ search 失败"), s);
    }

    @Test
    @DisplayName("older completed tools collapse into a counter beyond the display cap")
    void toolLinesCollapse() {
        WeComProgressRenderer r = new WeComProgressRenderer(System.currentTimeMillis(), false);
        for (int i = 1; i <= 5; i++) {
            String id = "c" + i;
            r.onEvent("tool_call_started", Map.of("toolCallId", id, "toolName", "tool" + i));
            r.onEvent("tool_call_completed", Map.of("toolCallId", id, "toolName", "tool" + i));
        }
        String s = r.snapshot();
        assertTrue(s.contains("…等 2 项已完成"), s);
        assertTrue(s.contains("tool5"), s);
        assertFalse(s.contains("tool1"), s);
    }

    @Test
    @DisplayName("thinking text appears as a quote block only when display is enabled")
    void thinkingDisplayGate() {
        WeComProgressRenderer shown = new WeComProgressRenderer(System.currentTimeMillis(), true);
        shown.onThinkingDelta("先查当前时间");
        assertTrue(shown.snapshot().contains("> 💭 先查当前时间"), shown.snapshot());

        WeComProgressRenderer hidden = new WeComProgressRenderer(System.currentTimeMillis(), false);
        hidden.onThinkingDelta("先查当前时间");
        String s = hidden.snapshot();
        assertFalse(s.contains("先查当前时间"), s);
        assertTrue(s.contains("💭 思考中"), s);
    }

    @Test
    @DisplayName("content deltas switch status to replying and show the answer tail")
    void contentSwitchesToReplying() {
        WeComProgressRenderer r = new WeComProgressRenderer(System.currentTimeMillis(), true);
        r.onThinkingDelta("想一想");
        r.onContentDelta("今天天气晴。");
        String s = r.snapshot();
        assertTrue(s.contains("正在回复"), s);
        assertTrue(s.contains("今天天气晴。"), s);
        // Thinking quote is dropped once real content flows.
        assertFalse(s.contains("> 💭"), s);
    }

    @Test
    @DisplayName("approval request switches status to waiting")
    void approvalSwitchesStatus() {
        WeComProgressRenderer r = new WeComProgressRenderer(System.currentTimeMillis(), false);
        boolean flush = r.onEvent("tool_approval_requested", Map.of("toolName", "rm"));
        assertTrue(flush);
        assertTrue(r.snapshot().contains("等待工具审批"), r.snapshot());
    }

    @Test
    @DisplayName("answer tail stays bounded for very long streamed content")
    void answerTailBounded() {
        WeComProgressRenderer r = new WeComProgressRenderer(System.currentTimeMillis(), false);
        r.onContentDelta("x".repeat(5000));
        assertTrue(r.snapshot().length() < 2048,
                "snapshot must stay under the WeCom message limit");
    }

    @Test
    @DisplayName("unknown events are ignored without requesting a flush")
    void unknownEventsIgnored() {
        WeComProgressRenderer r = new WeComProgressRenderer(System.currentTimeMillis(), false);
        assertFalse(r.onEvent("_usage_final", Map.of()));
        assertFalse(r.onEvent(null, null));
    }
}
