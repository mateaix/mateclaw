package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.dashscope.NativeDashScopeUsage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NodeStreamingChatHelperUsageTest {
    private ChatResponse text() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("OK"))));
    }

    private ChatResponse usageOnly(int promptTokens, int completionTokens) {
        var nativeUsage = new NativeDashScopeUsage(promptTokens, completionTokens, promptTokens + completionTokens,
                7L, 3L, new NativeDashScopeUsage.CompletionTokenDetails(5), Map.of());
        return new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(
                new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens, nativeUsage, 7L, 3L)).build());
    }

    private NodeStreamingChatHelper.StreamResult call(Flux<ChatResponse> frames) throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(frames);
        var helper = new NodeStreamingChatHelper(mock(ChatStreamTracker.class), (ChatModel) null);
        return helper.streamCall(model, new Prompt("hello"), "usage-only", "reasoning");
    }

    @Test
    void keepsUsageFromAnEmptyGenerationTerminalFrame() throws Exception {
        var result = call(Flux.just(text(), usageOnly(100, 20)));
        assertEquals("OK", result.text());
        assertEquals(100, result.promptTokens());
        assertEquals(20, result.completionTokens());
        assertEquals(7, result.cacheReadTokens());
        assertEquals(3, result.cacheWriteTokens());
        assertEquals(5, result.reasoningTokens());
    }

    @Test
    void latestCumulativeUsageReplacesEarlierFrames() throws Exception {
        var result = call(Flux.just(text(), usageOnly(100, 20), usageOnly(150, 30)));
        assertEquals(150, result.promptTokens());
        assertEquals(30, result.completionTokens());
    }

    @Test
    void reportedUsageSurvivesAnErrorAfterPartialContent() throws Exception {
        var result = call(Flux.just(text(), usageOnly(100, 20))
                .concatWith(Flux.error(new IllegalStateException("stream failed after usage"))));
        assertTrue(result.partial());
        assertEquals("OK", result.text());
        assertEquals(100, result.promptTokens());
        assertEquals(20, result.completionTokens());
    }
}
