package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #120 regression: persisted assistant rows that issued tool calls must
 * replay as a structured {@code AssistantMessage(toolCalls)} +
 * {@link ToolResponseMessage} pair, not as bare text. Without the pair the next
 * turn sees chain-of-thought narration ("Let me try the browser…") with no
 * matching observations and re-attempts the same tool, looping until the
 * iteration cap.
 *
 * <p>These tests drive {@link BaseAgent#extractCompletedToolCalls(MessageEntity)}
 * directly — {@code expandToSpringMessages} is instance-bound (its
 * {@code sanitizeForLlm} call uses {@code conversationService.renderMessageContent})
 * and exercising it requires a full BaseAgent fixture covered by integration
 * tests. The static extractor is the load-bearing parser; if it round-trips
 * metadata correctly the structural replay is correct.
 */
class BaseAgentToolCallReplayTest {

    @Test
    @DisplayName("completed toolCalls round-trip from metadata, preserving id+name+args+result")
    void completedToolCalls_extractedInOrder() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setContent("Looked it up via the web.");
        msg.setMetadata("{\"toolCalls\":["
                + "{\"toolCallId\":\"call_001\",\"name\":\"search\","
                + "\"arguments\":\"{\\\"q\\\":\\\"spring ai\\\"}\","
                + "\"status\":\"completed\",\"result\":\"No relevant hits.\",\"success\":true},"
                + "{\"toolCallId\":\"call_002\",\"name\":\"browser_use\","
                + "\"arguments\":\"{\\\"url\\\":\\\"https://x\\\"}\","
                + "\"status\":\"completed\",\"result\":\"404\",\"success\":false}"
                + "]}");

        List<BaseAgent.PersistedToolCall> calls = BaseAgent.extractCompletedToolCalls(msg);

        assertEquals(2, calls.size());
        assertEquals("call_001", calls.get(0).toolCallId());
        assertEquals("search", calls.get(0).name());
        assertEquals("{\"q\":\"spring ai\"}", calls.get(0).arguments());
        assertEquals("No relevant hits.", calls.get(0).result());
        assertEquals("call_002", calls.get(1).toolCallId());
        assertEquals("browser_use", calls.get(1).name());
        assertEquals("404", calls.get(1).result());
    }

    @Test
    @DisplayName("running / awaiting_approval entries are skipped — replaying them produces orphan tool_call_ids")
    void incompleteToolCalls_dropped() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setMetadata("{\"toolCalls\":["
                + "{\"toolCallId\":\"call_a\",\"name\":\"a\",\"status\":\"completed\",\"result\":\"ok\"},"
                + "{\"toolCallId\":\"call_b\",\"name\":\"b\",\"status\":\"running\"},"
                + "{\"toolCallId\":\"call_c\",\"name\":\"c\",\"status\":\"awaiting_approval\"}"
                + "]}");

        List<BaseAgent.PersistedToolCall> calls = BaseAgent.extractCompletedToolCalls(msg);

        assertEquals(1, calls.size());
        assertEquals("call_a", calls.get(0).toolCallId());
    }

    @Test
    @DisplayName("completed entry without a result is also skipped — there's nothing to feed back as observation")
    void completedWithoutResult_dropped() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setMetadata("{\"toolCalls\":["
                + "{\"toolCallId\":\"x\",\"name\":\"t\",\"status\":\"completed\"}"
                + "]}");

        assertTrue(BaseAgent.extractCompletedToolCalls(msg).isEmpty());
    }

    @Test
    @DisplayName("H2 double-wrap (JSON-encoded string of JSON) unwraps transparently")
    void h2DoubleWrap_unwrapped() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        // What H2's JSON column read sometimes hands back through MyBatis
        msg.setMetadata("\"{\\\"toolCalls\\\":["
                + "{\\\"toolCallId\\\":\\\"id-1\\\",\\\"name\\\":\\\"t\\\","
                + "\\\"status\\\":\\\"completed\\\",\\\"result\\\":\\\"ok\\\"}"
                + "]}\"");

        List<BaseAgent.PersistedToolCall> calls = BaseAgent.extractCompletedToolCalls(msg);
        assertEquals(1, calls.size());
        assertEquals("id-1", calls.get(0).toolCallId());
        assertEquals("ok", calls.get(0).result());
    }

    @Test
    @DisplayName("legacy rows without toolCallId still extract — caller synthesizes a stable id at replay time")
    void legacyMissingToolCallId_extractsBlankId() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setMetadata("{\"toolCalls\":["
                + "{\"name\":\"search\",\"status\":\"completed\",\"result\":\"hit\"}"
                + "]}");

        List<BaseAgent.PersistedToolCall> calls = BaseAgent.extractCompletedToolCalls(msg);
        assertEquals(1, calls.size());
        assertEquals("", calls.get(0).toolCallId());
        assertEquals("search", calls.get(0).name());
    }

    @Test
    @DisplayName("metadata without a toolCalls field returns empty (cheap exit, no JSON parse)")
    void noToolCallsField_emptyShortCircuit() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setMetadata("{\"segments\":[{\"type\":\"text\"}]}");

        assertTrue(BaseAgent.extractCompletedToolCalls(msg).isEmpty());
    }

    @Test
    @DisplayName("null / blank / null entity safely returns empty")
    void nullSafe() {
        assertTrue(BaseAgent.extractCompletedToolCalls(null).isEmpty());

        MessageEntity blank = new MessageEntity();
        assertTrue(BaseAgent.extractCompletedToolCalls(blank).isEmpty());

        blank.setMetadata("");
        assertTrue(BaseAgent.extractCompletedToolCalls(blank).isEmpty());
    }

    @Test
    @DisplayName("malformed JSON does not throw — it just yields an empty list")
    void malformedJson_emptyAndNoThrow() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        // Looks like it has toolCalls (short-circuit lets us through) but the
        // JSON itself is junk after that.
        msg.setMetadata("{\"toolCalls\": [not actually json}");

        assertTrue(BaseAgent.extractCompletedToolCalls(msg).isEmpty());
    }

    /**
     * Smoke-test that the structured Spring AI primitives BaseAgent emits
     * actually carry the data we expect. We assemble the same pair the
     * production replay path does and read it back.
     */
    @Test
    @DisplayName("AssistantMessage + ToolResponseMessage pair carries matching tool_call ids")
    void assistantPlusToolResponse_pairCarriesIds() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "call_42", "function", "search", "{\"q\":\"hello\"}");
        AssistantMessage assistant = AssistantMessage.builder()
                .content("Looking it up.")
                .toolCalls(List.of(tc))
                .build();
        ToolResponseMessage responses = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call_42", "search", "no hits")))
                .build();

        assertNotNull(assistant.getToolCalls());
        assertEquals(1, assistant.getToolCalls().size());
        assertEquals("call_42", assistant.getToolCalls().get(0).id());
        assertEquals("search", assistant.getToolCalls().get(0).name());

        assertEquals(1, responses.getResponses().size());
        assertEquals("call_42", responses.getResponses().get(0).id());
        assertEquals("no hits", responses.getResponses().get(0).responseData());
    }

    // ========== shouldFullyFilter — mirrors sanitizeForLlm stages 0/1/1.5 ==========

    @Test
    @DisplayName("shouldFullyFilter: cron-header system row dropped before any tool replay")
    void shouldFullyFilter_cronHeader() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("system");
        msg.setContent("📋 每日新闻 · 定时触发 · 2026-04-30T10:55");
        msg.setMetadata("{\"toolCalls\":[{\"toolCallId\":\"x\",\"name\":\"t\","
                + "\"status\":\"completed\",\"result\":\"y\"}]}");

        assertTrue(BaseAgent.shouldFullyFilter(msg));
    }

    @Test
    @DisplayName("shouldFullyFilter: approval-placeholder assistant dropped even with tool metadata")
    void shouldFullyFilter_approvalPlaceholder() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        // Real marker from ApprovalPlaceholderUtil — see callers in BaseAgent /
        // ConversationService. The point under test: even if metadata.toolCalls
        // looks complete, an approval placeholder must not resurrect a tool
        // exchange (the underlying tool never executed, was awaiting approval).
        msg.setContent("[APPROVAL_PENDING] 工具调用 search 等待您的批准");
        msg.setMetadata("{\"toolCalls\":[{\"name\":\"t\",\"status\":\"completed\",\"result\":\"y\"}]}");

        assertTrue(BaseAgent.isApprovalPlaceholder(msg.getContent()),
                "sanity: content must match ApprovalPlaceholderUtil");
        assertTrue(BaseAgent.shouldFullyFilter(msg));
    }

    @Test
    @DisplayName("shouldFullyFilter: error-status assistant dropped — replaying produces 400 loops")
    void shouldFullyFilter_errorStatusAssistant() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setStatus("error");
        msg.setContent("anything");
        msg.setMetadata("{\"toolCalls\":[{\"name\":\"t\",\"status\":\"completed\",\"result\":\"y\"}]}");

        assertTrue(BaseAgent.shouldFullyFilter(msg));
    }

    @Test
    @DisplayName("shouldFullyFilter: '[错误] ' content-prefix assistant dropped (legacy persistence)")
    void shouldFullyFilter_errorPrefixContent() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setContent("[错误] Bad request - reasoning_content");

        assertTrue(BaseAgent.shouldFullyFilter(msg));
    }

    @Test
    @DisplayName("shouldFullyFilter: ordinary assistant / user / null-entity rows pass through")
    void shouldFullyFilter_normalRowsPass() {
        MessageEntity ordinary = new MessageEntity();
        ordinary.setRole("assistant");
        ordinary.setContent("normal answer");
        assertFalse(BaseAgent.shouldFullyFilter(ordinary));

        MessageEntity user = new MessageEntity();
        user.setRole("user");
        user.setContent("hi");
        assertFalse(BaseAgent.shouldFullyFilter(user));

        assertTrue(BaseAgent.shouldFullyFilter(null), "null entity is filtered");
    }

    // ========== buildToolExchange — the load-bearing pair builder ==========

    @Test
    @DisplayName("buildToolExchange: empty content + completed tool call still emits the structured pair (P1 fix)")
    void buildToolExchange_emptyContentStillEmits() {
        MessageEntity entity = new MessageEntity();
        entity.setId(1234567890L);
        entity.setRole("assistant");
        entity.setContent("");

        List<BaseAgent.PersistedToolCall> calls = List.of(
                new BaseAgent.PersistedToolCall("call_x", "search", "{\"q\":\"a\"}", "no hits"));

        List<Message> out = BaseAgent.buildToolExchange(entity, "", calls);

        assertEquals(2, out.size(), "pure tool-call turn must replay as [assistant, toolResponses]");
        assertTrue(out.get(0) instanceof AssistantMessage);
        assertTrue(out.get(1) instanceof ToolResponseMessage);

        AssistantMessage am = (AssistantMessage) out.get(0);
        assertEquals("", am.getText() == null ? "" : am.getText(),
                "content stays blank — the tool exchange carries the semantics");
        assertEquals(1, am.getToolCalls().size());
        assertEquals("call_x", am.getToolCalls().get(0).id());

        ToolResponseMessage trm = (ToolResponseMessage) out.get(1);
        assertEquals(1, trm.getResponses().size());
        assertEquals("call_x", trm.getResponses().get(0).id(),
                "tool_call.id must equal tool_response.id");
    }

    @Test
    @DisplayName("buildToolExchange: non-empty content is preserved verbatim alongside the tool calls")
    void buildToolExchange_contentPreserved() {
        MessageEntity entity = new MessageEntity();
        entity.setId(42L);
        entity.setRole("assistant");

        List<BaseAgent.PersistedToolCall> calls = List.of(
                new BaseAgent.PersistedToolCall("id-1", "fetch", "{}", "200 OK"));

        List<Message> out = BaseAgent.buildToolExchange(entity, "I'll look it up.", calls);

        AssistantMessage am = (AssistantMessage) out.get(0);
        assertEquals("I'll look it up.", am.getText());
    }

    @Test
    @DisplayName("buildToolExchange: legacy row with missing toolCallId gets a stable 'legacy-<id>-<idx>' synthesis")
    void buildToolExchange_legacyIdSynthesis() {
        MessageEntity entity = new MessageEntity();
        entity.setId(99L);
        entity.setRole("assistant");

        List<BaseAgent.PersistedToolCall> calls = List.of(
                new BaseAgent.PersistedToolCall("", "search", "{}", "hit"),
                new BaseAgent.PersistedToolCall(null, "fetch", "{}", "ok"));

        List<Message> out = BaseAgent.buildToolExchange(entity, "", calls);

        AssistantMessage am = (AssistantMessage) out.get(0);
        ToolResponseMessage trm = (ToolResponseMessage) out.get(1);
        assertEquals("legacy-99-0", am.getToolCalls().get(0).id());
        assertEquals("legacy-99-1", am.getToolCalls().get(1).id());
        assertEquals("legacy-99-0", trm.getResponses().get(0).id());
        assertEquals("legacy-99-1", trm.getResponses().get(1).id(),
                "synthetic ids must match across the pair so provider tool_call_id validation passes");
    }

    @Test
    @DisplayName("buildToolExchange: multiple tool calls preserve order and pair 1:1")
    void buildToolExchange_multipleCallsOrdered() {
        MessageEntity entity = new MessageEntity();
        entity.setId(7L);
        entity.setRole("assistant");

        List<BaseAgent.PersistedToolCall> calls = List.of(
                new BaseAgent.PersistedToolCall("a", "t1", "{}", "r1"),
                new BaseAgent.PersistedToolCall("b", "t2", "{}", "r2"),
                new BaseAgent.PersistedToolCall("c", "t3", "{}", "r3"));

        List<Message> out = BaseAgent.buildToolExchange(entity, "", calls);
        AssistantMessage am = (AssistantMessage) out.get(0);
        ToolResponseMessage trm = (ToolResponseMessage) out.get(1);

        for (int i = 0; i < calls.size(); i++) {
            assertEquals(calls.get(i).toolCallId(), am.getToolCalls().get(i).id());
            assertEquals(calls.get(i).toolCallId(), trm.getResponses().get(i).id());
            assertEquals(calls.get(i).result(), trm.getResponses().get(i).responseData());
        }
    }

    @Test
    @DisplayName("emitted pair survives a 1:1 round-trip in a list ([assistant, toolResponses])")
    void replayList_pairsAreContiguous() {
        // What the BaseAgent.expandToSpringMessages path produces is a list of
        // exactly these two Message subtypes in this order; the consumer
        // (Spring AI chat client) iterates them as a single tool exchange.
        AssistantMessage assistant = AssistantMessage.builder()
                .content("ran X")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id-1", "function", "X", "{}")))
                .build();
        ToolResponseMessage responses = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id-1", "X", "ok")))
                .build();
        List<Message> replay = List.of(assistant, responses);

        assertEquals(2, replay.size());
        assertTrue(replay.get(0) instanceof AssistantMessage);
        assertTrue(replay.get(1) instanceof ToolResponseMessage);
        AssistantMessage a = (AssistantMessage) replay.get(0);
        ToolResponseMessage t = (ToolResponseMessage) replay.get(1);
        assertEquals(a.getToolCalls().get(0).id(), t.getResponses().get(0).id(),
                "tool_call.id must match its tool_response.id — providers reject mismatched pairs");
    }

    // ====================================================================
    // End-to-end orchestration: expandToSpringMessages with a TestAgent
    // fixture so the conversationService.renderMessageContent interaction
    // is exercised, not just the static helpers it composes (P3).
    // ====================================================================

    @Test
    @DisplayName("E2E: pure tool-call turn (blank rendered content) still replays as structured pair")
    void e2e_pureToolCallTurn() {
        TestAgent agent = newTestAgent();
        MessageEntity entity = new MessageEntity();
        entity.setId(101L);
        entity.setRole("assistant");
        entity.setContent("");  // pure tool call — no preamble text
        entity.setMetadata("{\"toolCalls\":["
                + "{\"toolCallId\":\"call_zz\",\"name\":\"search\","
                + "\"status\":\"completed\",\"result\":\"hit\"}"
                + "]}");
        when(agent.conversationService.renderMessageContent(entity)).thenReturn("");

        List<Message> out = agent.callExpand(entity);

        assertEquals(2, out.size(),
                "P1 regression: blank rendered content must NOT short-circuit the tool exchange");
        assertTrue(out.get(0) instanceof AssistantMessage);
        assertTrue(out.get(1) instanceof ToolResponseMessage);
        assertEquals(1, ((AssistantMessage) out.get(0)).getToolCalls().size());
        assertEquals("call_zz", ((AssistantMessage) out.get(0)).getToolCalls().get(0).id());
    }

    @Test
    @DisplayName("E2E: narration-only assistant (no toolCalls) yields one AssistantMessage via toSpringMessage")
    void e2e_narrativeOnly() {
        TestAgent agent = newTestAgent();
        MessageEntity entity = new MessageEntity();
        entity.setId(102L);
        entity.setRole("assistant");
        entity.setContent("All set.");
        entity.setMetadata("{}");
        when(agent.conversationService.renderMessageContent(entity)).thenReturn("All set.");

        List<Message> out = agent.callExpand(entity);

        assertEquals(1, out.size());
        assertTrue(out.get(0) instanceof AssistantMessage);
        assertEquals("All set.", ((AssistantMessage) out.get(0)).getText());
        assertTrue(((AssistantMessage) out.get(0)).getToolCalls() == null
                || ((AssistantMessage) out.get(0)).getToolCalls().isEmpty(),
                "no metadata.toolCalls present → no resurrected tool calls");
    }

    @Test
    @DisplayName("E2E: narration-only assistant with blank rendered content is dropped (legacy behavior preserved)")
    void e2e_blankNarrativeDropped() {
        TestAgent agent = newTestAgent();
        MessageEntity entity = new MessageEntity();
        entity.setId(103L);
        entity.setRole("assistant");
        entity.setContent("");
        entity.setMetadata("{}");
        when(agent.conversationService.renderMessageContent(entity)).thenReturn("");

        List<Message> out = agent.callExpand(entity);

        assertTrue(out.isEmpty(),
                "blank content + no toolCalls → drop, same as pre-#120 behavior");
    }

    @Test
    @DisplayName("E2E: approval-placeholder assistant dropped even when metadata.toolCalls looks complete")
    void e2e_approvalPlaceholderWithToolCalls() {
        TestAgent agent = newTestAgent();
        MessageEntity entity = new MessageEntity();
        entity.setId(104L);
        entity.setRole("assistant");
        entity.setContent("[APPROVAL_PENDING] 工具调用等待您的批准");
        entity.setMetadata("{\"toolCalls\":["
                + "{\"toolCallId\":\"x\",\"name\":\"t\","
                + "\"status\":\"completed\",\"result\":\"y\"}"
                + "]}");
        // renderMessageContent should NEVER be consulted for approval placeholders —
        // shouldFullyFilter cuts before we look at content. Stubbing it would mask a
        // regression where the filter ordering flipped.

        List<Message> out = agent.callExpand(entity);

        assertTrue(out.isEmpty(),
                "approval placeholder must not resurrect a tool exchange — the tool never actually ran");
    }

    @Test
    @DisplayName("E2E: error-status assistant dropped — replaying produces provider 400 loops")
    void e2e_errorAssistantWithToolCalls() {
        TestAgent agent = newTestAgent();
        MessageEntity entity = new MessageEntity();
        entity.setId(105L);
        entity.setRole("assistant");
        entity.setStatus("error");
        entity.setContent("anything");
        entity.setMetadata("{\"toolCalls\":["
                + "{\"toolCallId\":\"x\",\"name\":\"t\","
                + "\"status\":\"completed\",\"result\":\"y\"}"
                + "]}");

        List<Message> out = agent.callExpand(entity);

        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("E2E: cron-header system row dropped before any tool replay")
    void e2e_cronHeaderDropped() {
        TestAgent agent = newTestAgent();
        MessageEntity entity = new MessageEntity();
        entity.setId(106L);
        entity.setRole("system");
        entity.setContent("📋 每日新闻 · 定时触发 · 2026-04-30T10:55");
        entity.setMetadata("{\"toolCalls\":["
                + "{\"toolCallId\":\"x\",\"name\":\"t\","
                + "\"status\":\"completed\",\"result\":\"y\"}"
                + "]}");

        List<Message> out = agent.callExpand(entity);

        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("E2E: RFC-052 direct-tool row replays as placeholder only — no tool exchange resurrected")
    void e2e_directToolPlaceholderOnly() {
        TestAgent agent = newTestAgent();
        MessageEntity entity = new MessageEntity();
        entity.setId(107L);
        entity.setRole("assistant");
        entity.setContent("EMPLOYEE-SECRET-DATA");
        entity.setMetadata("{\"directToolNames\":[\"query_employee_salary\"],"
                + "\"toolCalls\":["
                + "{\"toolCallId\":\"x\",\"name\":\"query_employee_salary\","
                + "\"status\":\"completed\",\"result\":\"SECRET-PAYLOAD\"}"
                + "]}");
        when(agent.conversationService.renderMessageContent(entity)).thenReturn("EMPLOYEE-SECRET-DATA");

        List<Message> out = agent.callExpand(entity);

        assertEquals(1, out.size(),
                "RFC-052 direct-tool row must replay as ONE placeholder message, not as a tool exchange");
        assertTrue(out.get(0) instanceof AssistantMessage);
        String text = ((AssistantMessage) out.get(0)).getText();
        assertFalse(text.contains("EMPLOYEE-SECRET-DATA"),
                "direct-tool content must be scrubbed from history");
        assertFalse(text.contains("SECRET-PAYLOAD"),
                "tool result must not leak via resurrected tool exchange either");
        assertTrue(text.contains("query_employee_salary"),
                "placeholder names the originating tool so the model can re-call it on follow-up");
    }

    @Test
    @DisplayName("E2E: non-assistant rows are delegated to toSpringMessage unchanged")
    void e2e_userMessagePassThrough() {
        TestAgent agent = newTestAgent();
        MessageEntity entity = new MessageEntity();
        entity.setId(108L);
        entity.setRole("user");
        entity.setContent("hi");
        when(agent.conversationService.renderMessageContent(entity)).thenReturn("hi");
        when(agent.conversationService.parseMessageParts(any())).thenReturn(List.of());

        List<Message> out = agent.callExpand(entity);

        assertEquals(1, out.size());
        assertTrue(out.get(0) instanceof UserMessage);
        assertEquals("hi", ((UserMessage) out.get(0)).getText());
    }

    // ---------- Test scaffold ----------

    private static TestAgent newTestAgent() {
        ConversationService conv = mock(ConversationService.class);
        TestAgent agent = new TestAgent(conv);
        agent.agentName = "test-agent";
        agent.modelName = "test-model";
        return agent;
    }

    /**
     * Minimal concrete BaseAgent fixture so the package-private
     * {@code expandToSpringMessages} entry point can be exercised end-to-end
     * (renderMessageContent → sanitizeForLlm fork → buildToolExchange).
     */
    static class TestAgent extends BaseAgent {
        TestAgent(ConversationService conv) {
            super(null, conv);
        }

        List<Message> callExpand(MessageEntity entity) {
            return expandToSpringMessages(entity);
        }

        @Override public String chat(String userMessage, String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override public reactor.core.publisher.Flux<String> chatStream(String userMessage, String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override public String execute(String goal, String conversationId) {
            throw new UnsupportedOperationException();
        }
    }
}
