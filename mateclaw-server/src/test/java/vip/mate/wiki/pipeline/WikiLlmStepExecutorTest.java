package vip.mate.wiki.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.wiki.job.WikiModelRoutingService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiLlmStepExecutor}: it builds a prompt from the step
 * config and previous output, calls the routed model, and returns its text.
 * Model routing is mocked to return a fixed-response ChatModel.
 */
class WikiLlmStepExecutorTest {

    private WikiLlmStepExecutor executor(String modelReply) {
        WikiModelRoutingService routing = mock(WikiModelRoutingService.class);
        ChatModel chat = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(modelReply))));
        when(routing.buildChatModel(any())).thenReturn(chat);
        return new WikiLlmStepExecutor(routing);
    }

    private WikiStepContext ctx(Map<String, Object> config, String previousOutput) {
        return new WikiStepContext(1L, 42L, "s1", config, previousOutput);
    }

    @Test
    void callsModelAndReturnsText() throws Exception {
        WikiLlmStepExecutor e = executor("pattern summary");
        String out = e.execute(ctx(Map.of("prompt", "Summarize the episodes"), "ep1, ep2"));
        assertEquals("pattern summary", out);
    }

    @Test
    void missingPrompt_throws() {
        WikiLlmStepExecutor e = executor("x");
        assertThrows(IllegalArgumentException.class, () -> e.execute(ctx(Map.of(), "prior")));
    }

    @Test
    void typeIsLlm() {
        assertTrue("llm".equals(executor("x").type()));
    }
}
