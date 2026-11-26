package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class LlmStatisticsDecoratorTest {

    @Test
    void testCallLatencyCalculation() {
        ChatModel mockModel = Mockito.mock(ChatModel.class);
        ChatResponse mockResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Hello"))));
        when(mockModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(50);
            return mockResponse;
        });

        LlmStatisticsCollector collector = new LlmStatisticsCollector();
        LlmStatisticsDecorator decorator = new LlmStatisticsDecorator(mockModel, "mock-prov", "mock-model", collector);

        ChatResponse response = decorator.call(new Prompt("hi"));
        assertNotNull(response);

        List<LlmRequestMetric> snapshot = collector.getMetricsSnapshot();
        assertEquals(1, snapshot.size());
        LlmRequestMetric metric = snapshot.get(0);
        assertEquals("mock-prov", metric.providerId());
        assertEquals("mock-model", metric.modelName());
        assertTrue(metric.overallLatencyMs() >= 50);
    }

    @Test
    void testStreamFirstTokenLatencyCalculation() {
        ChatModel mockModel = Mockito.mock(ChatModel.class);
        ChatResponse resp1 = new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
        ChatResponse resp2 = new ChatResponse(List.of(new Generation(new AssistantMessage("A"))));
        ChatResponse resp3 = new ChatResponse(List.of(new Generation(new AssistantMessage("B"))));

        when(mockModel.stream(any(Prompt.class))).thenReturn(
            Flux.just(resp1, resp2, resp3)
        );

        LlmStatisticsCollector collector = new LlmStatisticsCollector();
        LlmStatisticsDecorator decorator = new LlmStatisticsDecorator(mockModel, "mock-prov", "mock-model", collector);

        List<ChatResponse> responses = decorator.stream(new Prompt("stream")).collectList().block();
        assertNotNull(responses);
        assertEquals(3, responses.size());

        List<LlmRequestMetric> snapshot = collector.getMetricsSnapshot();
        assertEquals(1, snapshot.size());
        LlmRequestMetric metric = snapshot.get(0);
        assertTrue(metric.isStreaming());
        assertNotNull(metric.firstTokenLatencyMs());
    }
}
