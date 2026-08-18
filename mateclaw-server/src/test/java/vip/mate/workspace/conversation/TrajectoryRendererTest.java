package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the linear transcript used for debugging and acceptance: every span in
 * emission order, tagged by kind, including what the chat UI hides.
 */
class TrajectoryRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TrajectoryRenderer RENDERER = new TrajectoryRenderer(MAPPER);

    private static MessageEntity message(String role, String metadata) {
        MessageEntity m = new MessageEntity();
        m.setRole(role);
        m.setMetadata(metadata);
        return m;
    }

    @Test
    @DisplayName("segments render in seq order, each tagged by kind")
    void rendersTimelineInSeqOrder() {
        String metadata = """
                {"segments":[
                  {"seq":1,"type":"tool_call","toolName":"clock","toolArgs":"{}",
                   "toolResult":"2026-08-06","toolSuccess":true},
                  {"seq":0,"type":"thinking","thinkingText":"先确认日期。"},
                  {"seq":2,"type":"content","text":"今天是 2026-08-06。"}
                ]}""";
        String out = RENDERER.render("conv-1",
                List.of(message("user", null), message("assistant", metadata)),
                List.of("今天几号？", "今天是 2026-08-06。"));

        int think = out.indexOf("<think>");
        int call = out.indexOf("<tool_call name=\"clock\">");
        int response = out.indexOf("<tool_response success=\"true\">");
        int content = out.indexOf("<content>");
        assertTrue(think >= 0 && call > think && response > call && content > response,
                "stored order is 1,0,2 — the transcript must follow seq, not array position:\n" + out);
        assertTrue(out.contains("先确认日期。"), out);
        assertTrue(out.contains("2026-08-06"), out);
        assertTrue(out.contains("## [0] user"), out);
        assertTrue(out.contains("## [1] assistant"), out);
    }

    @Test
    @DisplayName("superseded drafts are kept — the UI hides them, a replay needs them")
    void keepsSupersededDraft() {
        String metadata = """
                {"segments":[
                  {"seq":0,"type":"content","text":"我猜是周三。","superseded":true},
                  {"seq":1,"type":"content","text":"查过了，是周四。"}
                ]}""";
        String out = RENDERER.render("conv-2", List.of(message("assistant", metadata)), List.of(""));

        assertTrue(out.contains("<content superseded=\"true\">"), out);
        assertTrue(out.contains("我猜是周三。"), out);
        assertTrue(out.contains("查过了，是周四。"), out);
    }

    @Test
    @DisplayName("a row without a timeline falls back to rendered content, and says so")
    void fallsBackForLegacyRows() {
        String out = RENDERER.render("conv-3",
                List.of(message("assistant", null)), List.of("旧消息正文"));

        assertTrue(out.contains("no segment timeline"), out);
        assertTrue(out.contains("旧消息正文"), out);
    }

    @Test
    @DisplayName("segments without seq keep their stored order rather than being dropped")
    void toleratesMissingSeq() {
        String metadata = """
                {"segments":[
                  {"type":"thinking","thinkingText":"甲"},
                  {"type":"content","text":"乙"}
                ]}""";
        String out = RENDERER.render("conv-4", List.of(message("assistant", metadata)), List.of("乙"));

        assertTrue(out.indexOf("甲") < out.indexOf("乙"), out);
    }

    @Test
    @DisplayName("metadata wrapped as a JSON string literal still yields its timeline")
    void unwrapsDoubleEncodedMetadata() throws Exception {
        // How an H2 JSON column hands the document back. Read naively this
        // parses to a TextNode and the turn looks like it has no timeline.
        String inner = """
                {"segments":[{"seq":0,"type":"thinking","thinkingText":"内层推理"}]}""";
        String doubleEncoded = MAPPER.writeValueAsString(inner);

        String out = RENDERER.render("conv-7",
                List.of(message("assistant", doubleEncoded)), List.of("答案"));

        assertTrue(out.contains("<think>"), out);
        assertTrue(out.contains("内层推理"), out);
        assertFalse(out.contains("no segment timeline"), out);
    }

    @Test
    @DisplayName("unparseable metadata degrades to the rendered content instead of throwing")
    void survivesBrokenMetadata() {
        String out = RENDERER.render("conv-5",
                List.of(message("assistant", "{not json")), List.of("兜底正文"));

        assertTrue(out.contains("兜底正文"), out);
        assertFalse(out.contains("<think>"), out);
    }

    @Test
    @DisplayName("header states the conversation and message count")
    void writesHeader() {
        String out = RENDERER.render("conv-6", List.of(message("user", null)), List.of("嗨"));
        assertEquals("# trajectory conv-6", out.lines().findFirst().orElse(""));
        assertTrue(out.contains("# messages=1"), out);
    }
}
