package vip.mate.tool.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.support.ToolCallbacks;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.graph.edge.ObservationDispatcher;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.node.ActionNode;
import vip.mate.agent.graph.node.FinalAnswerNode;
import vip.mate.agent.runtime.agentscope.NativePolicyDriver;
import vip.mate.agent.runtime.agentscope.SpringToolSchemaAdapter;
import vip.mate.agent.runtime.agentscope.ToolBatchResultAdapter;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.RuntimeExecutionScope;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.document.MarkdownDocxRenderer;
import vip.mate.tool.guard.ToolGuardResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class DocxRenderToolCompletionTest {
    @Test
    void nativeDocxGenerationReturnsToReasoningBeforePublishingNarrative(@TempDir Path cacheDir) {
        var cache = new GeneratedFileCache(cacheDir);
        var tool = new DocxRenderTool(new MarkdownDocxRenderer(), cache);
        var callbacks = Arrays.asList(ToolCallbacks.from(tool));
        var executor = new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), callbacks),
                (name, args) -> ToolGuardResult.allow(), null, null);
        var action = new ActionNode(executor);
        var dispatcher = new ObservationDispatcher();
        var finalAnswer = new FinalAnswerNode(cache);
        var rounds = new AtomicInteger();
        var download = new AtomicReference<String>();
        var call = new AssistantMessage.ToolCall("docx-call", "function", "renderDocx",
                "{\"markdown\":\"# Employee wiki\\nOnboarding, policies, and contacts.\","
                        + "\"filename\":\"employee-wiki\",\"pageSize\":\"A4\"}");
        var policies = NativePolicyDriver.Policies.singleTask(
                state -> {
                    if (rounds.incrementAndGet() == 1) return Map.of(TOOL_CALLS, List.of(call));
                    var messages = state.<List<Message>>value(MESSAGES, List.of());
                    var receipt = messages.stream().filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).findFirst().orElseThrow()
                            .getResponses().getFirst().responseData();
                    // Tool formatting instructions stay in model context; they are not the answer.
                    assertTrue(receipt.contains("重要："));
                    var matcher = Pattern.compile("\\[employee-wiki\\.docx]\\(([^)]+)\\)").matcher(receipt);
                    assertTrue(matcher.find());
                    download.set(matcher.group(1));
                    String narrative = "Created the employee wiki covering onboarding, policies, and contacts. "
                            + "[Download employee-wiki.docx](" + download.get() + ")";
                    return Map.of(FINAL_ANSWER, narrative, MESSAGES, List.of(new AssistantMessage(narrative)));
                },
                state -> state.<List<AssistantMessage.ToolCall>>value(TOOL_CALLS, List.of()).isEmpty()
                        ? FINAL_ANSWER_NODE : ACTION_NODE,
                state -> {
                    var result = action.executeBatch(state);
                    var output = new HashMap<>(action.completeBatch(state, result));
                    output.put(NativePolicyDriver.BATCH_RESULT, ToolBatchResultAdapter.project(List.of(call), result));
                    return output;
                },
                state -> Map.of(), dispatcher::apply,
                state -> Map.of(), state -> Map.of(), finalAnswer::apply);
        var driver = new NativePolicyDriver(policies, SpringToolSchemaAdapter.convert(callbacks), 4,
                RuntimeExecutionScope::open, NativePolicyDriver.ToolNamePolicy.APPLICATION_OWNER);
        var steps = driver.run(new RunState(Map.of(CONVERSATION_ID, "docx-completion",
                AGENT_ID, "docx-agent", MAX_ITERATIONS, 10)))
                .collectList().block(Duration.ofSeconds(15));

        assertNotNull(steps);
        assertEquals(List.of(REASONING_NODE, ACTION_NODE, OBSERVATION_NODE, REASONING_NODE, FINAL_ANSWER_NODE),
                steps.stream().map(step -> step.node()).toList());
        assertEquals(2, rounds.get(), "Rendering a document must not bypass the model's final response");
        String answer = steps.getLast().state().value(FINAL_ANSWER, "");
        assertTrue(answer.startsWith("Created the employee wiki covering onboarding, policies, and contacts."));
        assertTrue(answer.contains("](" + download.get() + ")"));
        assertFalse(answer.contains("重要："));
        assertFalse(answer.contains("IMPORTANT:"));
        assertEquals("normal", steps.getLast().state().value(FINISH_REASON, ""));
        String id = download.get().substring(download.get().lastIndexOf('/') + 1);
        assertTrue(cache.get(id).isPresent(), "The narrative must retain a real generated-file link");
    }
}
