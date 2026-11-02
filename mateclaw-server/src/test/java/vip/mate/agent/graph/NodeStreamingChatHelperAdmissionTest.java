package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.failover.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NodeStreamingChatHelperAdmissionTest {
    @Test
    void cooldownIsPublishedBeforeNextQueuedRequestCanStart() throws Exception {
        var recording = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var props = new ProviderHealthProperties();
        props.setFailureThreshold(1);
        var health = new ProviderHealthTracker(props) {
            @Override public void recordFailure(String id, long hint) {
                recording.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                super.recordFailure(id, hint);
            }
        };
        var tracker = mock(ChatStreamTracker.class);
        var bad = mock(ChatModel.class);
        when(bad.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("401 Unauthorized")));
        var unexpectedCall = new CountDownLatch(1);
        var next = mock(ChatModel.class);
        when(next.stream(any(Prompt.class))).thenAnswer(ignored -> {
            unexpectedCall.countDown();
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("unexpected")))));
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> new NodeStreamingChatHelper(tracker, List.of(), null, health, "same")
                    .streamCall(bad, new Prompt("hi"), "first", "reasoning"));
            assertTrue(recording.await(1, TimeUnit.SECONDS));
            var second = workers.submit(() -> new NodeStreamingChatHelper(tracker, List.of(), null, health, "same")
                    .streamCall(next, new Prompt("hi"), "next", "reasoning"));
            try { assertFalse(unexpectedCall.await(150, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            first.get(3, TimeUnit.SECONDS);
            assertEquals(NodeStreamingChatHelper.ErrorType.PROVIDER_BUSY,
                    second.get(3, TimeUnit.SECONDS).errorType());
        } finally { release.countDown(); }
        verify(next, never()).stream(any(Prompt.class));
    }

    @Test
    void separateHelpersShareProviderAdmissionForWholeStream() throws Exception {
        exerciseSharedAdmission(false);
    }

    @Test
    void fallbackStreamsUseTheSameSharedAdmission() throws Exception {
        exerciseSharedAdmission(true);
    }

    private void exerciseSharedAdmission(boolean fallback) throws Exception {
        var tracker = mock(ChatStreamTracker.class);
        var health = new ProviderHealthTracker(new ProviderHealthProperties());
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var model = mock(ChatModel.class);
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        when(model.stream(any(Prompt.class))).thenAnswer(ignored -> Flux.defer(() -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            return Flux.just(response).delayElements(Duration.ofMillis(250))
                    .doOnComplete(active::decrementAndGet);
        }));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) {
                String id = "admission-" + i;
                var primary = fallback ? mock(ChatModel.class) : model;
                if (fallback) when(primary.stream(any(Prompt.class)))
                        .thenReturn(Flux.error(new RuntimeException("401 Unauthorized")));
                var helper = new NodeStreamingChatHelper(tracker,
                        fallback ? List.of(new FallbackEntry("shared", model)) : List.of(),
                        null, health, fallback ? "primary-" + i : "shared");
                futures.add(workers.submit(() -> {
                    start.await();
                    assertEquals("ok", helper.streamCall(primary, new Prompt("hello"), id, "reasoning").text());
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, peak.get(), "Same-provider streams must not overlap across helpers");
    }

    @Test
    void cooldownWithoutFallbackIsTypedAndWarningHasOneJsonEnvelope() throws Exception {
        var tracker = mock(ChatStreamTracker.class);
        var health = new ProviderHealthTracker(new ProviderHealthProperties());
        health.recordFailure("limited", 60_000);
        var model = mock(ChatModel.class);
        var helper = new NodeStreamingChatHelper(tracker, List.of(), null, health, "limited");
        var result = helper.streamCall(model, new Prompt("hello"), "cooldown", "plan_summary");
        assertEquals(NodeStreamingChatHelper.ErrorType.PROVIDER_BUSY, result.errorType());
        assertFalse(result.errorMessage().contains("最大重试"), "No request was attempted");
        verify(model, never()).stream(any(Prompt.class));
        var payloads = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(tracker, atLeastOnce()).broadcast(eq("cooldown"), eq("warning"), payloads.capture());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String payload : payloads.getAllValues())
            assertFalse(json.readTree(payload).path("delta").asText().startsWith("{"));
    }
}
