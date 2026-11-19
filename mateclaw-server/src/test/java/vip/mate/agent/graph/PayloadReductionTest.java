package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PayloadReductionTest {
    private String classify(Throwable error) throws Exception {
        Method method = NodeStreamingChatHelper.class.getDeclaredMethod("classifyError", Throwable.class);
        method.setAccessible(true);
        return method.invoke(null, error).toString();
    }
    @Test void structuredNested413WinsOverMisleadingBody() throws Exception {
        assertEquals("PAYLOAD_TOO_LARGE", classify(new RuntimeException("wrapper", new RestClientResponseException(
                "context length exceeded", 413, "too large", HttpHeaders.EMPTY, null, null))));
    }
    @Test void narrowPayloadTextAndIncidentalNumber() throws Exception {
        assertEquals("PAYLOAD_TOO_LARGE", classify(new RuntimeException("Bad request: 请求体过大（HTTP 413）")));
        assertNotEquals("PAYLOAD_TOO_LARGE", classify(new RuntimeException("invalid field value 413")));
    }
    @Test void localReductionPreservesMandatoryMessagesAndCanonicalHistory() throws Exception {
        List<Message> history = List.of(new SystemMessage("skill"), new UserMessage("original goal"),
                new AssistantMessage("old".repeat(100)), new UserMessage("continue"),
                new AssistantMessage("recent1"), new AssistantMessage("recent2"),
                new AssistantMessage("recent3"), new AssistantMessage("recent4"));
        Class<?> helper = Class.forName("vip.mate.agent.graph.PayloadReduction");
        @SuppressWarnings("unchecked") List<Message> reduced = (List<Message>) helper.getMethod("reduce", List.class).invoke(null, history);
        assertNotNull(reduced);
        assertTrue(reduced.contains(history.get(0)));
        assertTrue(reduced.contains(history.get(1)));
        assertTrue(reduced.contains(history.get(3)));
        assertFalse(reduced.contains(history.get(2)));
        assertEquals(8, history.size());
    }
    @Test void crossingToolPairRemainsTogether() {
        var call = AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "tool", "{}"))).build();
        var response = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id", "tool", "result"))).build();
        List<Message> history = List.of(new UserMessage("goal"), new AssistantMessage("old"), call,
                response,new AssistantMessage("r1"),new AssistantMessage("r2"),new AssistantMessage("r3"));
        List<Message> reduced = PayloadReduction.reduce(history);
        assertNotNull(reduced);
        assertTrue(reduced.contains(call)); assertTrue(reduced.contains(response));
    }
    @Test void payloadNeverRetriesOrFallsOverOrCountsAgainstHealth() throws Exception {
        var primary = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var fallback = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var health = org.mockito.Mockito.mock(vip.mate.llm.failover.ProviderHealthTracker.class);
        var tracker = org.mockito.Mockito.mock(vip.mate.channel.web.ChatStreamTracker.class);
        org.mockito.Mockito.when(health.acquireRequest(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.util.function.BooleanSupplier.class)))
                .thenReturn(org.mockito.Mockito.mock(vip.mate.llm.failover.ProviderHealthTracker.RequestPermit.class));
        org.mockito.Mockito.when(primary.stream(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(reactor.core.publisher.Flux.error(new RuntimeException("HTTP 413")));
        var helper = new NodeStreamingChatHelper(tracker,
                List.of(new vip.mate.llm.failover.FallbackEntry("fallback",fallback)),null,health,"primary");
        var result = helper.streamCall(primary,new org.springframework.ai.chat.prompt.Prompt("goal"),"payload","test");
        assertTrue(result.isPayloadTooLarge());
        org.mockito.Mockito.verify(primary,org.mockito.Mockito.times(1)).stream(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class));
        org.mockito.Mockito.verifyNoInteractions(fallback);
        org.mockito.Mockito.verify(health,org.mockito.Mockito.never()).recordFailure(org.mockito.ArgumentMatchers.anyString());
    }
    @Test void loadedSkillBodyAndItsCallAreMandatory() {
        var call = AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("skill", "function", "load_skill", "{}"), new AssistantMessage.ToolCall("other", "function", "execute_code", "{}"))).build();
        var response = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("skill", "load_skill", "required instructions"), new ToolResponseMessage.ToolResponse("other", "execute_code", "output"))).build();
        List<Message> history = List.of(new UserMessage("goal"), call, response, new AssistantMessage("old"),
                new AssistantMessage("r1"),new AssistantMessage("r2"),new AssistantMessage("r3"),new AssistantMessage("r4"));
        List<Message> reduced=PayloadReduction.reduce(history);
        assertNotNull(reduced); assertTrue(reduced.contains(call)); assertTrue(reduced.contains(response));
    }
    @Test void mandatoryOnlyPayloadCannotBeReduced() throws Exception {
        Class<?> helper = Class.forName("vip.mate.agent.graph.PayloadReduction");
        assertNull(helper.getMethod("reduce", List.class).invoke(null, List.of(new SystemMessage("skill"), new UserMessage("goal"))));
    }
}
