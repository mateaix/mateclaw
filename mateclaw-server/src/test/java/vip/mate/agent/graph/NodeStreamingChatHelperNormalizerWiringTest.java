package vip.mate.agent.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the wiring: {@link NodeStreamingChatHelper#streamCall} must apply
 * {@link MessageNormalizer#normalize} before handing the prompt off to
 * {@link ChatModel#stream}.
 *
 * <p>The pure {@code MessageNormalizer} contract is covered by
 * {@code MessageNormalizerTest}. This test guards against a refactor that
 * accidentally drops the call site at the top of {@code doStreamCall} —
 * which is the only thing standing between MateClaw and the LM Studio
 * {@code 400 "System message must be at the beginning"} regression.
 */
class NodeStreamingChatHelperNormalizerWiringTest {

    private ChatStreamTracker streamTracker;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        when(streamTracker.isStopRequested(any())).thenReturn(false);
    }

    /** Mock that emits one successful chunk with the given text. */
    private static ChatModel successModel(String text) {
        ChatModel m = mock(ChatModel.class);
        Generation gen = new Generation(new AssistantMessage(text), ChatGenerationMetadata.NULL);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResults()).thenReturn(List.of(gen));
        when(resp.getResult()).thenReturn(gen);
        when(resp.getMetadata()).thenReturn(null);
        when(m.stream(any(Prompt.class))).thenReturn(Flux.just(resp));
        return m;
    }

    @Test
    @DisplayName("streamCall normalizes SystemMessages before invoking ChatModel.stream")
    void streamCallNormalizes() {
        ChatModel chatModel = successModel("hi");
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(streamTracker);

        // Build the exact problematic shape ReasoningNode produces today:
        // SystemMessages sprinkled around UserMessages — would 400 on LM Studio.
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("MAIN_PROMPT"),
                new SystemMessage("SKILL_CATALOG"),
                new UserMessage("RUNTIME_CTX"),
                new SystemMessage("LEDGER_SNAPSHOT"),
                new UserMessage("user question")
        ));

        helper.streamCall(chatModel, prompt, "conv-1", "reasoning");

        // Capture the Prompt that actually reached ChatModel.stream.
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        Prompt outbound = captor.getValue();
        List<Message> sent = outbound.getInstructions();

        // Exactly one SystemMessage, at index 0, containing all three system
        // segments in encounter order.
        assertThat(sent.stream().filter(m -> m instanceof SystemMessage)).hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(sent.get(0).getText()).isEqualTo(
                "MAIN_PROMPT" + MessageNormalizer.SEPARATOR
                        + "SKILL_CATALOG" + MessageNormalizer.SEPARATOR
                        + "LEDGER_SNAPSHOT");

        // Non-system relative order preserved.
        assertThat(sent.subList(1, sent.size()))
                .extracting(Message::getText)
                .containsExactly("RUNTIME_CTX", "user question");
    }
}
