package vip.mate.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.ContentKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State machine of the shared provisional-narration lifecycle, plus the
 * kind-driven persisted-timeline marking. The streaming scenarios mirror the
 * ones the structural detector's tests pin for the web timeline — the two
 * paths must agree on every case a producer can actually emit.
 */
class ProvisionalContentTrackerTest {

    // ==================== streaming state machine ====================

    @Test
    @DisplayName("pre-tool narration superseded by the next narration — never published")
    void preToolNarrationSupersededByNextNarration() {
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");

        assertNull(t.stageNarration("先查询会议室数据。", ContentKind.PRE_TOOL_NARRATION),
                "first narration has no predecessor to publish");
        t.onToolObservation();
        String publishable = t.stageNarration("第一间空闲，继续。", ContentKind.GROUNDED_NARRATION);
        assertNull(publishable, "provisional predecessor is superseded, not published");

        assertEquals("第一间空闲，继续。", t.settle(true),
                "the grounded successor itself settles publishable");
    }

    @Test
    @DisplayName("grounded narration publishes when the next narration arrives")
    void groundedNarrationPublishesOnNext() {
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");

        t.onToolObservation();
        t.stageNarration("时间拿到了，再查会议室：", ContentKind.GROUNDED_NARRATION);
        t.onToolObservation();
        String publishable = t.stageNarration("会议室也查完了。", ContentKind.GROUNDED_NARRATION);
        assertEquals("时间拿到了，再查会议室：", publishable);
    }

    @Test
    @DisplayName("pre-tool narration superseded by the final answer at settle")
    void preToolNarrationSupersededByFinalAnswer() {
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");

        t.stageNarration("环境监测结果：温度 29.0°C。", ContentKind.PRE_TOOL_NARRATION);
        t.onToolObservation();
        assertNull(t.settle(true), "fabricated rehearsal must not survive once a grounded answer exists");
    }

    @Test
    @DisplayName("pre-tool narration commits when the turn produced no answer at all")
    void preToolNarrationCommitsWithoutAnswer() {
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");

        t.stageNarration("我先调用工具查询状态：", ContentKind.PRE_TOOL_NARRATION);
        t.onToolObservation();
        assertEquals("我先调用工具查询状态：", t.settle(false),
                "with no replacement content the narration is everything the user gets");
    }

    @Test
    @DisplayName("pre-tool narration with no tool run after it survives settle — nothing replaced it")
    void preToolNarrationWithoutToolsAfterSurvives() {
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");

        t.stageNarration("我先调用工具：", ContentKind.PRE_TOOL_NARRATION);
        assertEquals("我先调用工具：", t.settle(true),
                "supersede requires an observation after staging — e.g. all tools denied leaves nothing to defer to");
    }

    @Test
    @DisplayName("grounded narration always settles publishable")
    void groundedNarrationSettles() {
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");
        t.onToolObservation();
        t.stageNarration("查完了，结果如下。", ContentKind.GROUNDED_NARRATION);
        assertEquals("查完了，结果如下。", t.settle(true));
    }

    @Test
    @DisplayName("settle clears state — second settle finds nothing")
    void settleClearsState() {
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");
        t.stageNarration("x", ContentKind.GROUNDED_NARRATION);
        t.settle(true);
        assertNull(t.settle(true));
    }

    @Test
    @DisplayName("null kind falls back to the observation-counter rule")
    void nullKindFallsBackToCounter() {
        // No observation before staging → provisional; a tool ran after → superseded.
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");
        t.stageNarration("我先查一下：", null);
        t.onToolObservation();
        assertNull(t.settle(true), "untagged pre-tool narration still dropped for a grounded answer");

        // Observation completed before staging → grounded, publishes.
        ProvisionalContentTracker t2 = new ProvisionalContentTracker("test");
        t2.onToolObservation();
        t2.stageNarration("拿到结果了。", null);
        assertEquals("拿到结果了。", t2.settle(true));
    }

    @Test
    @DisplayName("untagged narrations with no tool activity between them all publish — legacy relay preserved")
    void untaggedNoToolStreamKeepsRelay() {
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");
        t.stageNarration("我先查一下天气", null);
        assertEquals("我先查一下天气", t.stageNarration("再帮你汇总结果", null),
                "no observation between the two — the predecessor was not replaced by anything grounded");
        assertEquals("再帮你汇总结果", t.settle(true));
    }

    @Test
    @DisplayName("producer kind outranks the counter signal when both are present")
    void kindOutranksCounter() {
        // The counter says an observation preceded the narration, but the
        // producer knows the text was emitted before any observation of this
        // turn (the two can disagree when multiple narrations land between
        // two completions). Kind wins.
        ProvisionalContentTracker t = new ProvisionalContentTracker("test");
        t.onToolObservation();
        t.stageNarration("预演内容", ContentKind.PRE_TOOL_NARRATION);
        t.onToolObservation();
        assertNull(t.settle(true), "kind is authoritative — counter signal ignored when tagged");
    }

    // ==================== persisted-timeline marking ====================

    private static Map<String, Object> content(String id, String kind) {
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("id", id);
        seg.put("type", "content");
        seg.put("text", "t-" + id);
        if (kind != null) {
            seg.put("kind", kind);
        }
        return seg;
    }

    private static Map<String, Object> tool(String id) {
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("id", id);
        seg.put("type", "tool_call");
        return seg;
    }

    @Test
    @DisplayName("hasKindTags only reacts to tagged content segments")
    void hasKindTagsDetection() {
        assertFalse(ProvisionalContentTracker.hasKindTags(null));
        assertFalse(ProvisionalContentTracker.hasKindTags(List.of(content("c0", null), tool("t1"))));
        assertTrue(ProvisionalContentTracker.hasKindTags(
                List.of(content("c0", "pre_tool_narration"))));
    }

    @Test
    @DisplayName("pre_tool segment marked superseded by the first later content segment")
    void marksPreToolSegment() {
        List<Map<String, Object>> segments = new ArrayList<>(List.of(
                content("c0", "pre_tool_narration"),
                tool("t1"),
                content("c2", "final_answer")));

        ProvisionalContentTracker.markSuperseded(segments, "test");

        assertEquals(true, segments.get(0).get("superseded"));
        assertEquals("c2", segments.get(0).get("supersededBySegmentId"));
        assertEquals(ProvisionalContentTracker.REASON_PRE_TOOL_CONTENT_REPLACED,
                segments.get(0).get("supersededReason"));
        assertFalse(segments.get(2).containsKey("superseded"));
    }

    @Test
    @DisplayName("grounded and final segments never marked; trailing pre_tool without replacement stands")
    void groundedNeverMarkedAndTrailingPreToolStands() {
        List<Map<String, Object>> segments = new ArrayList<>(List.of(
                content("c0", "grounded_narration"),
                tool("t1"),
                content("c2", "pre_tool_narration")));

        ProvisionalContentTracker.markSuperseded(segments, "test");

        assertFalse(segments.get(0).containsKey("superseded"),
                "grounded narration is never replaced");
        assertFalse(segments.get(2).containsKey("superseded"),
                "no later content exists — the narration is everything the user gets");
    }

    @Test
    @DisplayName("chained tool segments between narration and answer don't block marking")
    void chainedToolsBetween() {
        List<Map<String, Object>> segments = new ArrayList<>(List.of(
                content("c0", "pre_tool_narration"),
                tool("t1"),
                tool("t2"),
                content("c3", "final_answer")));

        ProvisionalContentTracker.markSuperseded(segments, "test");

        assertEquals(true, segments.get(0).get("superseded"));
        assertEquals("c3", segments.get(0).get("supersededBySegmentId"));
    }
}
