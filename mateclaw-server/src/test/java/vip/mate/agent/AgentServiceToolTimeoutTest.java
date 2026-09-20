package vip.mate.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.config.ToolTimeoutProperties;
import vip.mate.memory.MemoryProperties;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AgentServiceToolTimeoutTest {
    @Test
    void singleToolTimeoutFinishesTurnAndSameConversationCanRunAgain() {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean();
        ToolCallback tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("extract_document_text")
                        .description("document extraction").inputSchema("{\"type\":\"object\"}").build();
            }
            @Override public String call(String arguments) {
                if (firstCall.getAndSet(false)) {
                    try {
                        Thread.sleep(3_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                }
                return "ok";
            }
        };
        ToolTimeoutProperties timeouts = new ToolTimeoutProperties();
        timeouts.setDefaultTimeoutSeconds(1);
        var executor = new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), List.of(tool)),
                null, null, null, timeouts);
        var call = new AssistantMessage.ToolCall("call", "function", "extract_document_text", "{}");
        MemoryProperties memory = new MemoryProperties();
        memory.setLifecycleMediatorEnabled(false);
        var service = new AgentService(null, null, null, null, memory, null, null);
        BiFunction<String, String, Flux<String>> invoke = (message, conversation) -> Flux.defer(() ->
                Flux.just(executor.execute(List.of(call), conversation, "1", false)
                        .responses().getFirst().responseData()));
        Function<String, String> content = Function.identity();
        Flux<String> first = ReflectionTestUtils.invokeMethod(service, "withLifecycleFlux",
                1L, "read spreadsheet", "same-conversation", invoke, content);
        assertNotNull(first);
        assertTrue(first.blockLast().contains("timed out"));
        assertTrue(interrupted.get());
        assertFalse(Thread.currentThread().isInterrupted());

        Flux<String> second = ReflectionTestUtils.invokeMethod(service, "withLifecycleFlux",
                1L, "retry", "same-conversation", invoke, content);
        assertNotNull(second);
        assertEquals("ok", second.blockLast(), "the previous turn must release admission");
    }
}
