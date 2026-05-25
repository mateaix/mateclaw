package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.agent.graph.state.SourceEvidenceLedger;
import vip.mate.tool.document.GeneratedFileCache;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * FinalAnswerNode 单元测试
 */
class FinalAnswerNodeTest {

    private FinalAnswerNode node;

    @BeforeEach
    void setUp() {
        node = new FinalAnswerNode();
    }

    @Test
    @DisplayName("正常 finalAnswer 直接使用")
    void shouldUseExistingFinalAnswer() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "这是最终回答"
        ));
        Map<String, Object> result = node.apply(state);
        assertEquals("这是最终回答", result.get(FINAL_ANSWER));
        assertEquals("normal", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("finalAnswerDraft 优先于 finalAnswer")
    void draftTakesPrecedence() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "旧回答",
                FINAL_ANSWER_DRAFT, "新草稿"
        ));
        Map<String, Object> result = node.apply(state);
        assertEquals("新草稿", result.get(FINAL_ANSWER));
    }

    @Test
    @DisplayName("ERROR_FALLBACK finalAnswer 保留错误文案和 finishReason")
    void shouldPreserveErrorFallbackAnswer() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "[错误] 认证失败: Invalid API Key",
                FINISH_REASON, "error_fallback"
        ));
        Map<String, Object> result = node.apply(state);
        assertEquals("[错误] 认证失败: Invalid API Key", result.get(FINAL_ANSWER));
        assertEquals("error_fallback", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("STOPPED 且无内容时保留 STOPPED 语义（不降级为 ERROR_FALLBACK）")
    void shouldPreserveStoppedWhenNoContent() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "",
                FINISH_REASON, "stopped"
        ));
        Map<String, Object> result = node.apply(state);
        // 关键断言：finishReason 保持 STOPPED，不被改成 ERROR_FALLBACK
        assertEquals("stopped", result.get(FINISH_REASON),
                "Empty finalAnswer with STOPPED should not become ERROR_FALLBACK");
        // finalAnswer 保持空（用户停止且无内容是合法的）
        assertEquals("", result.get(FINAL_ANSWER));
    }

    @Test
    @DisplayName("STOPPED 且无 finalAnswer 键时也保留 STOPPED 语义")
    void shouldPreserveStoppedWhenNoFinalAnswerKey() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINISH_REASON, "stopped"
        ));
        Map<String, Object> result = node.apply(state);
        assertEquals("stopped", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("无任何内容且非 STOPPED 时降级为 ERROR_FALLBACK")
    void shouldFallbackWhenNoContentAndNotStopped() throws Exception {
        OverAllState state = new OverAllState(Map.of());
        Map<String, Object> result = node.apply(state);
        // Fallback 文案在 Jobs-voice 改写后统一为英文（commit 01c3888）。
        assertEquals("Failed to generate a response, please retry.", result.get(FINAL_ANSWER));
        assertEquals("error_fallback", result.get(FINISH_REASON));
    }

    // ========== RFC-052 returnDirect ==========

    @Test
    @DisplayName("RFC-052: single direct tool output becomes the final answer verbatim")
    void directSingle_verbatim() throws Exception {
        DirectToolOutput out = new DirectToolOutput(
                "call_1", "query_employee_salary",
                "Alice's salary is 12345.", System.currentTimeMillis());
        OverAllState state = new OverAllState(Map.of(
                RETURN_DIRECT_TRIGGERED, true,
                DIRECT_TOOL_OUTPUTS, List.of(out)
        ));

        Map<String, Object> result = node.apply(state);

        assertEquals("Alice's salary is 12345.", result.get(FINAL_ANSWER),
                "Direct tool result must reach the user verbatim, no LLM rewriting");
        assertEquals("return_direct", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("RFC-052: multiple direct outputs are joined with tool-name headings")
    void directMultiple_joinedWithHeadings() throws Exception {
        DirectToolOutput a = new DirectToolOutput(
                "call_1", "tool_a", "result_a", System.currentTimeMillis());
        DirectToolOutput b = new DirectToolOutput(
                "call_2", "tool_b", "result_b", System.currentTimeMillis());
        OverAllState state = new OverAllState(Map.of(
                RETURN_DIRECT_TRIGGERED, true,
                DIRECT_TOOL_OUTPUTS, List.of(a, b)
        ));

        Map<String, Object> result = node.apply(state);

        String answer = (String) result.get(FINAL_ANSWER);
        assertNotNull(answer);
        assertTrue(answer.startsWith("### tool_a\nresult_a"),
                "first heading + body should appear at the top");
        assertTrue(answer.contains("### tool_b\nresult_b"),
                "second heading + body should follow");
        assertEquals("return_direct", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("RFC-052: trigger flag without outputs falls through to default assembly")
    void directTriggerEmpty_fallsThrough() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                RETURN_DIRECT_TRIGGERED, true,
                FINAL_ANSWER, "fallback content"
        ));

        Map<String, Object> result = node.apply(state);

        // Without DIRECT_TOOL_OUTPUTS we should NOT short-circuit to RETURN_DIRECT —
        // the existing FINAL_ANSWER path handles it as NORMAL.
        assertEquals("fallback content", result.get(FINAL_ANSWER));
        assertEquals("normal", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("RFC-052: direct path takes precedence over draft / existing answer / approval")
    void directBranch_highestPriority() throws Exception {
        DirectToolOutput out = new DirectToolOutput(
                "call_1", "tool_x", "direct text", System.currentTimeMillis());
        OverAllState state = new OverAllState(Map.of(
                RETURN_DIRECT_TRIGGERED, true,
                DIRECT_TOOL_OUTPUTS, List.of(out),
                FINAL_ANSWER, "must be ignored",
                FINAL_ANSWER_DRAFT, "must also be ignored"
        ));

        Map<String, Object> result = node.apply(state);

        assertEquals("direct text", result.get(FINAL_ANSWER));
        assertEquals("return_direct", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("源码证据不足时降级 finishReason 并提示未验证引用")
    void unsupportedSourceReferencesBecomeEvidenceInsufficient() throws Exception {
        SourceEvidenceLedger ledger = SourceEvidenceLedger.empty()
                .withSourcePath("src/main/java/vip/mate/skill/SkillController.java");
        OverAllState state = new OverAllState(Map.of(
                SOURCE_EVIDENCE_LEDGER, ledger,
                FINAL_ANSWER, "SkillController.java 是入口，SkillServiceImpl.java 负责业务逻辑。"
        ));

        Map<String, Object> result = node.apply(state);

        assertEquals("evidence_insufficient", result.get(FINISH_REASON));
        assertTrue(((String) result.get(FINAL_ANSWER)).contains("SkillServiceImpl.java"));
        assertTrue(((String) result.get(FINAL_ANSWER)).contains("证据不足"));
    }

    // ========== finish_reason GraphEvent (P1: must ride PENDING_EVENTS, not SSE bypass) ==========

    /**
     * Pull the {@code finish_reason} GraphEvent attached to a node output.
     * Returns null when no such event was emitted.
     */
    @SuppressWarnings("unchecked")
    private static GraphEventPublisher.GraphEvent pickFinishReasonEvent(Map<String, Object> output) {
        Object raw = output.get(PENDING_EVENTS);
        if (!(raw instanceof List<?> list)) return null;
        for (Object item : list) {
            if (item instanceof GraphEventPublisher.GraphEvent ev
                    && GraphEventPublisher.EVENT_FINISH_REASON.equals(ev.type())) {
                return ev;
            }
        }
        return null;
    }

    @Test
    @DisplayName("normal path emits finish_reason GraphEvent on PENDING_EVENTS so the accumulator can persist it")
    void normalPath_emitsFinishReasonEvent() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "正常回答"
        ));

        Map<String, Object> result = node.apply(state);

        GraphEventPublisher.GraphEvent ev = pickFinishReasonEvent(result);
        assertNotNull(ev, "FinalAnswerNode must attach a finish_reason GraphEvent (NORMAL path)");
        assertEquals("normal", ev.data().get("reason"));
    }

    @Test
    @DisplayName("incomplete path also emits finish_reason GraphEvent (regression for the SSE-bypass bug)")
    void incompletePath_emitsFinishReasonEvent() throws Exception {
        // Simulates ReasoningNode handing INCOMPLETE through to FinalAnswerNode
        // (e.g. repetition-truncated partial). The earlier fix wired this via
        // streamTracker.broadcastObject which was an SSE-only bypass — the
        // accumulator never saw it. Now it MUST ride PENDING_EVENTS.
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "已经流式输出的部分内容…",
                FINISH_REASON, "incomplete"
        ));

        Map<String, Object> result = node.apply(state);

        GraphEventPublisher.GraphEvent ev = pickFinishReasonEvent(result);
        assertNotNull(ev, "INCOMPLETE finish_reason must reach the channel via PENDING_EVENTS");
        assertEquals("incomplete", ev.data().get("reason"));
    }

    @Test
    @DisplayName("RFC-052 RETURN_DIRECT path emits finish_reason GraphEvent")
    void returnDirectPath_emitsFinishReasonEvent() throws Exception {
        DirectToolOutput out = new DirectToolOutput(
                "call_1", "tool_x", "direct text", System.currentTimeMillis());
        OverAllState state = new OverAllState(Map.of(
                RETURN_DIRECT_TRIGGERED, true,
                DIRECT_TOOL_OUTPUTS, List.of(out)
        ));

        Map<String, Object> result = node.apply(state);

        GraphEventPublisher.GraphEvent ev = pickFinishReasonEvent(result);
        assertNotNull(ev, "RETURN_DIRECT path must attach a finish_reason GraphEvent");
        assertEquals("return_direct", ev.data().get("reason"));
    }

    @Test
    @DisplayName("AWAITING_APPROVAL path emits finish_reason GraphEvent (NORMAL while paused)")
    void awaitingApprovalPath_emitsFinishReasonEvent() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                AWAITING_APPROVAL, true,
                STREAMED_CONTENT, "我现在要做 X 操作。"
        ));

        Map<String, Object> result = node.apply(state);

        GraphEventPublisher.GraphEvent ev = pickFinishReasonEvent(result);
        assertNotNull(ev, "AWAITING_APPROVAL path must attach a finish_reason GraphEvent");
        assertEquals("normal", ev.data().get("reason"),
                "Approval pause is treated as a normal pause; the resolved decision will emit a fresh event on replay");
    }

    @Test
    @DisplayName("evidence_insufficient path emits finish_reason GraphEvent with the downgraded reason")
    void evidenceInsufficientPath_emitsFinishReasonEvent() throws Exception {
        SourceEvidenceLedger ledger = SourceEvidenceLedger.empty()
                .withSourcePath("src/main/java/vip/mate/skill/SkillController.java");
        OverAllState state = new OverAllState(Map.of(
                SOURCE_EVIDENCE_LEDGER, ledger,
                FINAL_ANSWER, "SkillController.java 是入口，SkillServiceImpl.java 负责业务逻辑。"
        ));

        Map<String, Object> result = node.apply(state);

        GraphEventPublisher.GraphEvent ev = pickFinishReasonEvent(result);
        assertNotNull(ev);
        assertEquals("evidence_insufficient", ev.data().get("reason"),
                "Downgraded finishReason must surface in the GraphEvent, not the original NORMAL");
    }

    // ========== fake-URL guard ==========
    //
    // Without the guard, a hallucinated /api/v1/files/generated/{uuid} URL
    // surfaces verbatim to every channel — IM clients render a clickable
    // link that 404s, and users save the 404 HTML body as a .docx which
    // they then report as "corrupted file". Putting the guard at the
    // FinalAnswerNode terminal means EVERY channel (Web SSE, Slack,
    // DingTalk, WeCom, Telegram, …) sees the same scrubbed text.

    @Test
    @DisplayName("fake-URL guard: hallucinated generated-file URL → user-visible warning")
    void fakeUrl_replacedWithWarning() throws Exception {
        FinalAnswerNode guarded = new FinalAnswerNode(new GeneratedFileCache());
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "您的文档已生成: /api/v1/files/generated/a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        ));

        Map<String, Object> result = guarded.apply(state);

        String answer = (String) result.get(FINAL_ANSWER);
        assertFalse(answer.contains("/api/v1/files/generated/"),
                "fake URL must not survive in the persisted answer; got: " + answer);
        assertTrue(answer.contains(GeneratedFileCache.MISSING_REFERENCE_NOTICE),
                "user-visible warning must appear in place of the fake URL; got: " + answer);
    }

    @Test
    @DisplayName("fake-URL guard: real cached URL passes through so channel adapters can rewrite it")
    void realUrl_leftIntact() throws Exception {
        GeneratedFileCache cache = new GeneratedFileCache();
        String id = cache.put("real-bytes".getBytes(), "report.pdf", "application/pdf");
        FinalAnswerNode guarded = new FinalAnswerNode(cache);

        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "下载: /api/v1/files/generated/" + id
        ));

        Map<String, Object> result = guarded.apply(state);

        // Cached URLs survive verbatim — downstream WeCom / Slack / etc.
        // adapters can still rewrite them into native attachments.
        assertTrue(((String) result.get(FINAL_ANSWER))
                .contains("/api/v1/files/generated/" + id),
                "live cached URL must pass through for downstream native-attachment rewrite");
    }

    @Test
    @DisplayName("fake-URL guard: also fires on RETURN_DIRECT path (tool output may also hallucinate)")
    void fakeUrl_scrubbedOnDirectPath() throws Exception {
        FinalAnswerNode guarded = new FinalAnswerNode(new GeneratedFileCache());
        DirectToolOutput out = new DirectToolOutput(
                "call_1", "tool_x",
                "see /api/v1/files/generated/never-rendered-uuid",
                System.currentTimeMillis());
        OverAllState state = new OverAllState(Map.of(
                RETURN_DIRECT_TRIGGERED, true,
                DIRECT_TOOL_OUTPUTS, List.of(out)
        ));

        Map<String, Object> result = guarded.apply(state);

        String answer = (String) result.get(FINAL_ANSWER);
        assertFalse(answer.contains("never-rendered-uuid"),
                "RETURN_DIRECT path must also scrub; got: " + answer);
    }

    @Test
    @DisplayName("fake-URL guard: no-cache constructor (legacy callers, narrow tests) is a no-op")
    void noCache_passThrough() throws Exception {
        // FinalAnswerNode without an injected cache must not throw — the
        // narrow unit tests that construct the node with the no-arg ctor
        // still need to work. The trade-off: tests that don't exercise
        // file outputs simply skip the scrub. Production wiring always
        // passes a real cache from AgentGraphBuilder.
        FinalAnswerNode unguarded = new FinalAnswerNode();
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "/api/v1/files/generated/anything"
        ));
        Map<String, Object> result = unguarded.apply(state);
        assertEquals("/api/v1/files/generated/anything", result.get(FINAL_ANSWER));
    }

    // ========== feedback_event recovery affordance ==========
    //
    // After NodeStreamingChatHelper has exhausted its TLS/IO retry budget
    // and the turn ends in ERROR_FALLBACK, the user is left staring at
    // red "[错误] …" text with no recovery affordance. FinalAnswerNode
    // attaches a feedback_event GraphEvent so the frontend can render
    // retry/regenerate/report buttons next to the failed bubble — and
    // the event is persisted into message metadata so a page reload
    // doesn't make the affordance vanish.

    /** Pull the feedback_event GraphEvent attached to a node output. */
    @SuppressWarnings("unchecked")
    private static GraphEventPublisher.GraphEvent pickFeedbackEvent(Map<String, Object> output) {
        Object raw = output.get(PENDING_EVENTS);
        if (!(raw instanceof List<?> list)) return null;
        for (Object item : list) {
            if (item instanceof GraphEventPublisher.GraphEvent ev
                    && GraphEventPublisher.EVENT_FEEDBACK.equals(ev.type())) {
                return ev;
            }
        }
        return null;
    }

    @Test
    @DisplayName("ERROR_FALLBACK turn emits feedback_event with retry/regenerate/report actions")
    void errorFallback_emitsFeedbackEvent() throws Exception {
        // Mirrors the production path: ReasoningNode hands a fatal-error
        // finalAnswer + ERROR_FALLBACK finishReason to FinalAnswerNode.
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "[错误] LLM 调用失败: bad_record_mac",
                FINISH_REASON, "error_fallback"
        ));

        Map<String, Object> result = node.apply(state);

        GraphEventPublisher.GraphEvent ev = pickFeedbackEvent(result);
        assertNotNull(ev, "ERROR_FALLBACK turn must attach a feedback_event for the UI");
        assertEquals("ERROR_FALLBACK", ev.data().get("errorType"));
        assertEquals("[错误] LLM 调用失败: bad_record_mac", ev.data().get("errorMessage"));
        Object actions = ev.data().get("actions");
        assertTrue(actions instanceof List<?>);
        assertEquals(List.of("retry", "regenerate", "report"), actions);
    }

    @Test
    @DisplayName("ERROR_FALLBACK still emits the standard finish_reason event alongside feedback_event")
    void errorFallback_alsoEmitsFinishReason() throws Exception {
        // The two events ride the same PENDING_EVENTS list. Existing
        // consumers (memory gate, channel accumulator, message metadata
        // persistence) read finish_reason; the new feedback_event is
        // additive — losing finish_reason here would silently break
        // those consumers.
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "[错误] 认证失败: Invalid API Key",
                FINISH_REASON, "error_fallback"
        ));

        Map<String, Object> result = node.apply(state);

        GraphEventPublisher.GraphEvent fr = pickFinishReasonEvent(result);
        assertNotNull(fr, "finish_reason event must remain on PENDING_EVENTS");
        assertEquals("error_fallback", fr.data().get("reason"));

        GraphEventPublisher.GraphEvent fb = pickFeedbackEvent(result);
        assertNotNull(fb, "feedback_event must coexist with finish_reason on the same output");
    }

    @Test
    @DisplayName("NORMAL turn does NOT emit feedback_event (no recovery affordance needed)")
    void normalTurn_noFeedbackEvent() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "正常回答"
        ));

        Map<String, Object> result = node.apply(state);

        assertNull(pickFeedbackEvent(result),
                "Successful turns must not attach feedback_event — would render misleading retry buttons");
    }

    @Test
    @DisplayName("INCOMPLETE turn does NOT emit feedback_event (handled by its own card)")
    void incompleteTurn_noFeedbackEvent() throws Exception {
        // INCOMPLETE has its own dedicated UI card ("regenerate" button
        // wired via finishReason=incomplete). Adding feedback_event there
        // would duplicate the affordance and confuse users.
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "已经流式输出的部分内容…",
                FINISH_REASON, "incomplete"
        ));

        Map<String, Object> result = node.apply(state);

        assertNull(pickFeedbackEvent(result),
                "INCOMPLETE has its own card; must not also surface feedback_event");
    }

    @Test
    @DisplayName("STOPPED (user-initiated abort) does NOT emit feedback_event")
    void stoppedTurn_noFeedbackEvent() throws Exception {
        // User clicked stop. They don't need a "retry" prompt — the
        // partial output is the explicit signal they asked for.
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "我刚在生成…",
                FINISH_REASON, "stopped"
        ));

        Map<String, Object> result = node.apply(state);

        assertNull(pickFeedbackEvent(result));
    }
}
