package vip.mate.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.graph.edge.ObservationDispatcher;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.node.ActionNode;
import vip.mate.agent.graph.node.FinalAnswerNode;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * RFC-052 end-to-end chain test — exercises the full graph traversal across
 * three real components without mocking:
 *
 * <pre>
 *   ToolExecutionExecutor (executes returnDirect tool)
 *        ↓ writes ToolResponseMessage + events + directOutputs
 *   ActionNode (interprets ToolExecutionResult, sets RETURN_DIRECT_TRIGGERED)
 *        ↓ state mutation
 *   ObservationDispatcher (routes to FinalAnswerNode)
 *        ↓ edge decision
 *   FinalAnswerNode (assembles final answer from DIRECT_TOOL_OUTPUTS)
 *        ↓ produces FINAL_ANSWER + finishReason=RETURN_DIRECT
 * </pre>
 *
 * <p>This complements the per-component unit tests by verifying the
 * <em>composition</em> works: invariants flow correctly between nodes via
 * {@link OverAllState}, no integration glue is missing, no state key is
 * misnamed across boundaries.
 *
 * <p>What this does NOT test (still requires manual / SpringBootTest):
 * <ul>
 *   <li>{@code StateGraphReActAgent} stream emission of {@code FINAL_ANSWER}
 *       as {@code content_delta}</li>
 *   <li>{@code StreamAccumulator} capturing {@code tool_direct_result} into
 *       {@code metadata.directToolNames} (covered by the manual demo)</li>
 *   <li>{@code BaseAgent.toSpringMessage} scrubbing on the next user turn
 *       (covered by {@code BaseAgentDirectToolHistoryScrubTest})</li>
 * </ul>
 */
class ReturnDirectEndToEndTest {

    private static final String SECRET =
            "EMPLOYEE-SALARY-RECORD\n" +
            "Name: Alice\n" +
            "Base: 12345\n" +
            "Bonus: 67890\n" +
            "SSN: 999-88-7777";

    @Test
    @DisplayName("RFC-052 end-to-end: secret reaches FINAL_ANSWER verbatim, never enters LLM-bound messages")
    void fullChain_directToolFlowsAcrossNodes() throws Exception {
        // ===== Setup: real executor + real ActionNode + real Dispatcher + real FinalAnswerNode =====
        ToolCallback directTool = stubCallback("query_employee_salary", true, args -> SECRET);
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(directTool));
        ToolGuard alwaysAllow = (n, a) -> ToolGuardResult.allow();
        ToolExecutionExecutor executor = new ToolExecutionExecutor(toolSet, alwaysAllow, null, null);

        ActionNode actionNode = new ActionNode(executor);
        ObservationDispatcher dispatcher = new ObservationDispatcher();
        FinalAnswerNode finalAnswerNode = new FinalAnswerNode();

        // ===== Step 1: simulate ReasoningNode having decided to call the direct tool =====
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_x", "function", "query_employee_salary", "{}");
        Map<String, Object> initialState = new HashMap<>();
        initialState.put(TOOL_CALLS, List.of(toolCall));
        initialState.put(CONVERSATION_ID, "conv_e2e");
        initialState.put(AGENT_ID, "agent_e2e");
        OverAllState state1 = new OverAllState(initialState);

        // ===== Step 2: ActionNode runs the executor =====
        Map<String, Object> actionOut = actionNode.apply(state1);

        // Verify ActionNode set the trigger flags
        assertEquals(Boolean.TRUE, actionOut.get(RETURN_DIRECT_TRIGGERED),
                "ActionNode must set RETURN_DIRECT_TRIGGERED when executor produced direct outputs");
        @SuppressWarnings("unchecked")
        List<DirectToolOutput> outputs = (List<DirectToolOutput>) actionOut.get(DIRECT_TOOL_OUTPUTS);
        assertNotNull(outputs);
        assertEquals(1, outputs.size());
        assertEquals(SECRET, outputs.get(0).fullResult(),
                "Full secret must reach DIRECT_TOOL_OUTPUTS verbatim");

        // Critical: the ToolResponseMessage stored in MESSAGES must NOT contain the secret —
        // it must contain the placeholder, since this is what would be re-fed to the LLM
        // if the graph weren't short-circuiting.
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) actionOut.get(MESSAGES);
        assertNotNull(messages);
        assertEquals(1, messages.size());
        ToolResponseMessage tr = (ToolResponseMessage) messages.get(0);
        assertEquals(1, tr.getResponses().size());
        ToolResponseMessage.ToolResponse resp = tr.getResponses().get(0);
        // RFC-052 §2.4 contract: the placeholder is a fixed, English, business-data-free
        // sentence. Asserting the exact text doubles as a contract test — if anyone
        // changes the placeholder text this fails and the RFC needs updating too.
        assertEquals(
                "[Tool result returned directly to user. " +
                "Content withheld from model context per tool policy.]",
                resp.responseData(),
                "Tool response carried in MESSAGES must be the §2.4 placeholder, not the secret");
        assertFalse(resp.responseData().contains("12345"),
                "Sanity: the salary number must not be on the LLM-bound path");
        assertFalse(resp.responseData().contains("999-88-7777"),
                "Sanity: SSN must not be on the LLM-bound path");

        // ===== Step 3: ObservationDispatcher decides where to route =====
        // Build a state that reflects what the graph would have AFTER ActionNode
        // (we manually merge ActionNode's output for the dispatcher input — the
        // actual graph engine does this via state merge strategies).
        Map<String, Object> stateAfterAction = new HashMap<>(initialState);
        stateAfterAction.putAll(actionOut);
        // Skip ObservationNode for simplicity — it doesn't touch our flags. Real
        // graph runs Action → Observation → Dispatcher; we verify the dispatcher
        // contract directly.
        OverAllState state2 = new OverAllState(stateAfterAction);

        String route = dispatcher.apply(state2);
        assertEquals(FINAL_ANSWER_NODE, route,
                "Dispatcher must route RETURN_DIRECT_TRIGGERED to FinalAnswerNode, " +
                "skipping the next LLM call");

        // ===== Step 4: FinalAnswerNode assembles the final answer =====
        Map<String, Object> finalOut = finalAnswerNode.apply(state2);

        assertEquals(SECRET, finalOut.get(FINAL_ANSWER),
                "FinalAnswerNode must surface the direct tool's full text verbatim as the final answer");
        assertEquals("return_direct", finalOut.get(FINISH_REASON),
                "finishReason must be RETURN_DIRECT");
    }

    @Test
    @DisplayName("RFC-052 end-to-end: mixed batch — direct tool A succeeds, non-direct tool B succeeds, plan still short-circuits")
    void fullChain_mixedBatch_directWins() throws Exception {
        ToolCallback direct = stubCallback("read_medical_record", true, args -> "PATIENT-DATA-XYZ");
        ToolCallback normal = stubCallback("get_weather", false, args -> "sunny, 22C");
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(direct, normal));
        ToolGuard alwaysAllow = (n, a) -> ToolGuardResult.allow();
        ToolExecutionExecutor executor = new ToolExecutionExecutor(toolSet, alwaysAllow, null, null);

        ActionNode actionNode = new ActionNode(executor);
        ObservationDispatcher dispatcher = new ObservationDispatcher();
        FinalAnswerNode finalAnswerNode = new FinalAnswerNode();

        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("c1", "function", "read_medical_record", "{}"),
                new AssistantMessage.ToolCall("c2", "function", "get_weather", "{}"));
        Map<String, Object> initial = new HashMap<>();
        initial.put(TOOL_CALLS, calls);
        initial.put(CONVERSATION_ID, "conv_mixed");
        initial.put(AGENT_ID, "agent_mixed");

        Map<String, Object> actionOut = actionNode.apply(new OverAllState(initial));
        assertEquals(Boolean.TRUE, actionOut.get(RETURN_DIRECT_TRIGGERED));

        Map<String, Object> merged = new HashMap<>(initial);
        merged.putAll(actionOut);
        OverAllState merged2 = new OverAllState(merged);

        assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(merged2),
                "Even with a non-direct tool in the batch, the direct one short-circuits");

        Map<String, Object> finalOut = finalAnswerNode.apply(merged2);
        assertEquals("PATIENT-DATA-XYZ", finalOut.get(FINAL_ANSWER),
                "Single direct output rendered verbatim (single-output path, no headings)");
        assertEquals("return_direct", finalOut.get(FINISH_REASON));
    }

    @Test
    @DisplayName("RFC-052 end-to-end: non-direct tool DOES NOT trigger short-circuit")
    void fullChain_nonDirectTool_runsNormalLoop() throws Exception {
        ToolCallback normal = stubCallback("get_weather", false, args -> "rainy, 12C");
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(normal));
        ToolGuard alwaysAllow = (n, a) -> ToolGuardResult.allow();
        ToolExecutionExecutor executor = new ToolExecutionExecutor(toolSet, alwaysAllow, null, null);

        ActionNode actionNode = new ActionNode(executor);
        ObservationDispatcher dispatcher = new ObservationDispatcher();

        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call_w", "function", "get_weather", "{}");
        Map<String, Object> initial = new HashMap<>();
        initial.put(TOOL_CALLS, List.of(call));
        initial.put(CONVERSATION_ID, "conv_w");
        initial.put(AGENT_ID, "agent_w");
        initial.put(CURRENT_ITERATION, 0);
        initial.put(MAX_ITERATIONS, 10);

        Map<String, Object> actionOut = actionNode.apply(new OverAllState(initial));

        // RETURN_DIRECT_TRIGGERED must NOT be set
        assertNull(actionOut.get(RETURN_DIRECT_TRIGGERED),
                "Non-direct tool must not flip RETURN_DIRECT_TRIGGERED");

        // Dispatcher routes to REASONING_NODE for next loop iteration
        Map<String, Object> merged = new HashMap<>(initial);
        merged.putAll(actionOut);
        String route = dispatcher.apply(new OverAllState(merged));
        assertEquals(REASONING_NODE, route,
                "Without the direct flag, dispatcher must continue the ReAct loop");
    }

    /** Stub ToolCallback with explicit returnDirect flag (mirrors the unit-test helper). */
    private static ToolCallback stubCallback(String name, boolean returnDirect,
                                              java.util.function.Function<String, String> handler) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description("e2e test tool " + name)
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
