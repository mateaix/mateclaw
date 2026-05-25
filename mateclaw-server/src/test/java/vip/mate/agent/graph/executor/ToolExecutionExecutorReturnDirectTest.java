package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-052 PR-1/PR-2 end-to-end test for {@link ToolExecutionExecutor}.
 *
 * <p>The contract under test:
 * <ol>
 *   <li>A {@code returnDirect=true} tool's full result is captured in
 *       {@link ToolExecutionExecutor.ToolExecutionResult#directOutputs()}.</li>
 *   <li>The corresponding {@link ToolResponseMessage.ToolResponse} carries the
 *       fixed placeholder, not the sensitive content.</li>
 *   <li>An {@code EVENT_TOOL_DIRECT_RESULT} event is emitted with the full text
 *       and {@code renderAs=assistant_message}.</li>
 *   <li>Non-direct tools in the same batch keep their existing behavior.</li>
 * </ol>
 */
class ToolExecutionExecutorReturnDirectTest {

    private static final String SECRET = "EMPLOYEE-SALARY: Alice=12345, Bob=67890";

    private ToolExecutionExecutor newExecutor(ToolCallback... callbacks) {
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(callbacks));
        ToolGuard alwaysAllow = (n, a) -> ToolGuardResult.allow();
        // streamTracker=null is supported throughout executor; null approval
        // service is fine when guard never returns NEEDS_APPROVAL.
        return new ToolExecutionExecutor(toolSet, alwaysAllow, null, null);
    }

    @Test
    @DisplayName("RFC-052: returnDirect tool result reaches user verbatim and stays out of LLM context")
    void directTool_fullResultCapturedAndPlaceholderInResponse() {
        ToolCallback direct = stubCallback("query_employee_salary", true, args -> SECRET);
        ToolExecutionExecutor executor = newExecutor(direct);

        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call_1", "function", "query_employee_salary", "{}");
        ToolExecutionExecutor.ToolExecutionResult result =
                executor.execute(List.of(call), "conv_1", "agent_1", false, "user_1", null);

        // (1) directOutputs aggregates the full text
        assertEquals(1, result.directOutputs().size());
        DirectToolOutput out = result.directOutputs().get(0);
        assertEquals("query_employee_salary", out.toolName());
        assertEquals(SECRET, out.fullResult(), "Full result must be preserved verbatim");
        assertTrue(result.hasDirectOutputs());

        // (2) ToolResponseMessage carries the placeholder (LLM-safe)
        assertEquals(1, result.responses().size());
        ToolResponseMessage.ToolResponse resp = result.responses().get(0);
        assertEquals(ToolExecutionExecutor.DIRECT_TOOL_PLACEHOLDER, resp.responseData(),
                "ToolResponseMessage must carry the placeholder, not the sensitive payload");
        assertFalse(resp.responseData().contains("EMPLOYEE-SALARY"),
                "Sensitive substring must not appear in tool response");

        // (3) tool_direct_result event was emitted with renderAs=assistant_message + full text
        var directEvents = result.events().stream()
                .filter(e -> GraphEventPublisher.EVENT_TOOL_DIRECT_RESULT.equals(e.type()))
                .toList();
        assertEquals(1, directEvents.size(), "exactly one tool_direct_result event expected");
        var data = directEvents.get(0).data();
        assertEquals("call_1", data.get("toolCallId"));
        assertEquals("query_employee_salary", data.get("toolName"));
        assertEquals(SECRET, data.get("result"));
        assertEquals("assistant_message", data.get("renderAs"));

        // (4) no tool_call_completed event for the direct tool — direct path replaces it
        boolean hasCompleted = result.events().stream()
                .anyMatch(e -> GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(e.type()));
        assertFalse(hasCompleted, "direct path replaces tool_call_completed; double-emit would " +
                "leak the placeholder into UI as a tool result card");
    }

    @Test
    @DisplayName("RFC-052: non-direct tool keeps existing behavior (no direct outputs)")
    void nonDirectTool_keepsBaselineBehavior() {
        ToolCallback normal = stubCallback("get_weather", false, args -> "sunny, 22C");
        ToolExecutionExecutor executor = newExecutor(normal);

        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call_w", "function", "get_weather", "{}");
        ToolExecutionExecutor.ToolExecutionResult result =
                executor.execute(List.of(call), "conv_w", "agent_w", false, "user_w", null);

        assertFalse(result.hasDirectOutputs(), "no direct tool ran; directOutputs must be empty");
        assertTrue(result.directOutputs().isEmpty());
        assertEquals(1, result.responses().size());
        assertEquals("sunny, 22C", result.responses().get(0).responseData());

        // no direct event
        boolean hasDirect = result.events().stream()
                .anyMatch(e -> GraphEventPublisher.EVENT_TOOL_DIRECT_RESULT.equals(e.type()));
        assertFalse(hasDirect);
    }

    @Test
    @DisplayName("RFC-052: returnDirect tool throwing yields generic message (no exception details leak)")
    void directTool_throwing_genericErrorMessage() {
        ToolCallback throwingDirect = stubCallback("query_employee_salary", true, args -> {
            throw new RuntimeException("OracleDriver: connection refused, secret-conn-str=user/PWD123@db");
        });
        ToolExecutionExecutor executor = newExecutor(throwingDirect);

        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call_e", "function", "query_employee_salary", "{}");
        ToolExecutionExecutor.ToolExecutionResult result =
                executor.execute(List.of(call), "conv_e", "agent_e", false, "user_e", null);

        // No directOutputs — exception aborted before the direct branch
        assertFalse(result.hasDirectOutputs());
        assertEquals(1, result.responses().size());
        String content = result.responses().get(0).responseData();
        assertEquals("Tool execution failed (details withheld per returnDirect policy)", content,
                "Direct-tool exception text must be replaced with a generic placeholder");
        assertFalse(content.contains("PWD123"), "Sensitive substring from exception must not leak");
        assertFalse(content.contains("OracleDriver"), "Stack/connection details must not leak");
    }

    @Test
    @DisplayName("RFC-052: pre-approved direct tool replays through direct path")
    void executePreApproved_directTool_takesDirectPath() {
        ToolCallback direct = stubCallback("query_secret", true, args -> "SECRET-PAYLOAD-123");
        ToolExecutionExecutor executor = newExecutor(direct);

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_a", "function", "query_secret", "{}");
        java.util.List<GraphEventPublisher.GraphEvent> events = new java.util.ArrayList<>();
        java.util.List<DirectToolOutput> directOutputs = new java.util.ArrayList<>();

        ToolResponseMessage.ToolResponse response = executor.executePreApproved(
                toolCall, "{}", events, "conv_a", null, directOutputs);

        // Without the directOutputs collector wired, executePreApproved would
        // have leaked SECRET-PAYLOAD-123 into the response. With the fix:
        assertEquals(ToolExecutionExecutor.DIRECT_TOOL_PLACEHOLDER, response.responseData(),
                "Pre-approved direct tool must produce a placeholder response");
        assertEquals(1, directOutputs.size());
        assertEquals("SECRET-PAYLOAD-123", directOutputs.get(0).fullResult());

        // tool_direct_result event present
        assertTrue(events.stream()
                .anyMatch(e -> GraphEventPublisher.EVENT_TOOL_DIRECT_RESULT.equals(e.type())));
    }

    @Test
    @DisplayName("RFC-052: legacy executePreApproved (no collector) does NOT silently leak — placeholder still applied")
    void executePreApproved_legacyOverload_stillProducesPlaceholder() {
        ToolCallback direct = stubCallback("query_secret", true, args -> "SECRET-OTHER-456");
        ToolExecutionExecutor executor = newExecutor(direct);

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_b", "function", "query_secret", "{}");
        java.util.List<GraphEventPublisher.GraphEvent> events = new java.util.ArrayList<>();

        // 5-arg overload with no directOutputs collector — directOutputs is
        // dropped on the floor, but the placeholder still keeps the LLM safe.
        ToolResponseMessage.ToolResponse response = executor.executePreApproved(
                toolCall, "{}", events, "conv_b", null);

        assertEquals(ToolExecutionExecutor.DIRECT_TOOL_PLACEHOLDER, response.responseData());
        assertFalse(response.responseData().contains("SECRET-OTHER-456"));
    }

    @Test
    @DisplayName("RFC-052: mixed batch — any direct tool triggers direct outputs while non-direct keeps result")
    void mixedBatch_directAndNonDirect() {
        ToolCallback direct = stubCallback("read_medical_record", true, args -> "PATIENT-DATA-XYZ");
        ToolCallback normal = stubCallback("get_weather", false, args -> "rainy, 12C");
        ToolExecutionExecutor executor = newExecutor(direct, normal);

        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("c1", "function", "read_medical_record", "{}"),
                new AssistantMessage.ToolCall("c2", "function", "get_weather", "{}"));

        ToolExecutionExecutor.ToolExecutionResult result =
                executor.execute(calls, "conv_m", "agent_m", false, "user_m", null);

        assertTrue(result.hasDirectOutputs());
        assertEquals(1, result.directOutputs().size());
        assertEquals("read_medical_record", result.directOutputs().get(0).toolName());
        assertEquals("PATIENT-DATA-XYZ", result.directOutputs().get(0).fullResult());

        // non-direct response still contains its own data; placeholder is only on direct
        assertEquals(2, result.responses().size());
        ToolResponseMessage.ToolResponse directResp = result.responses().get(0);
        ToolResponseMessage.ToolResponse normalResp = result.responses().get(1);
        assertEquals(ToolExecutionExecutor.DIRECT_TOOL_PLACEHOLDER, directResp.responseData());
        assertEquals("rainy, 12C", normalResp.responseData());
    }

    /** Build a minimal ToolCallback stub with an explicit returnDirect flag. */
    private static ToolCallback stubCallback(String name, boolean returnDirect,
                                              java.util.function.Function<String, String> handler) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description("test tool " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        ToolMetadata md = ToolMetadata.builder().returnDirect(returnDirect).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public ToolMetadata getToolMetadata() { return md; }
            @Override public String call(String arguments) { return handler.apply(arguments); }
            @Override public String call(String arguments, ToolContext toolContext) {
                return handler.apply(arguments);
            }
        };
    }
}
