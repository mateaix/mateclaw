package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import vip.mate.agent.BaseAgent;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalMediaReductionTest {
    private static final String USER_INPUT = "mateclaw.real_user_input";
    private Media image() { return new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[4096])); }
    private UserMessage current(String text) {
        return UserMessage.builder().text(text).metadata(Map.of(USER_INPUT, true)).build();
    }
    @Test void reducesHistoricalMediaEvenForShortConversationWithoutChangingCanonicalHistory() {
        var old = UserMessage.builder().text("previous photo").media(List.of(image())).metadata(Map.of("id", "old")).build();
        var active = UserMessage.builder().text("current photo").media(List.of(image())).metadata(Map.of(USER_INPUT, true)).build();
        var history = List.<Message>of(old, new AssistantMessage("previous answer"), active);
        var reduced = PayloadReduction.reduce(history);
        assertNotNull(reduced); assertEquals(3, reduced.size());
        var oldCopy = (UserMessage) reduced.getFirst();
        assertTrue(oldCopy.getMedia().isEmpty()); assertTrue(oldCopy.getText().startsWith("previous photo"));
        assertTrue(oldCopy.getText().contains("省略")); assertEquals(old.getMetadata(), oldCopy.getMetadata());
        assertSame(active, reduced.getLast()); assertEquals(1, old.getMedia().size());
        assertNull(PayloadReduction.reduce(reduced));
    }
    @Test void syntheticContinuationCannotMoveTheCurrentAttachmentBoundary() {
        var old = UserMessage.builder().text("old").media(List.of(image())).build();
        var active = UserMessage.builder().text("act on current image").media(List.of(image())).metadata(Map.of(USER_INPUT, true)).build();
        var continuation = new UserMessage("[Runtime completion gate] continue");
        var reduced = PayloadReduction.reduce(List.of(old, active, continuation));
        assertNotNull(reduced); assertTrue(((UserMessage) reduced.getFirst()).getMedia().isEmpty());
        assertSame(active, reduced.get(1)); assertSame(continuation, reduced.get(2));
    }
    @Test void assistantMediaCanShrinkWithoutBreakingToolPairsOrProperties() {
        var call = new AssistantMessage.ToolCall("id", "function", "tool", "{}");
        var assistant = AssistantMessage.builder().content("generated image").media(List.of(image()))
                .properties(Map.of("reasoning_content", "reason")).toolCalls(List.of(call)).build();
        var response = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id", "tool", "ok"))).build();
        var reduced = PayloadReduction.reduce(List.of(assistant, response, current("next request")));
        assertNotNull(reduced);
        var copy = (AssistantMessage) reduced.getFirst();
        assertTrue(copy.getMedia().isEmpty()); assertEquals(assistant.getMetadata(), copy.getMetadata());
        assertEquals(List.of(call), copy.getToolCalls()); assertSame(response, reduced.get(1));
        assertEquals(1, assistant.getMedia().size());
    }
    @Test void unknownUserBoundaryKeepsAttachmentsAndMandatoryCurrentMediaCannotShrink() {
        var media = UserMessage.builder().text("image").media(List.of(image())).build();
        assertNull(PayloadReduction.reduce(List.of(media, new UserMessage("possibly synthetic"))));
        var active = UserMessage.builder().text("current").media(List.of(image())).metadata(Map.of(USER_INPUT, true)).build();
        assertNull(PayloadReduction.reduce(List.of(active, new AssistantMessage("working"))));
    }
    @Test void currentTurnWrapperMarksRealInputAndPreservesMetadataAndMedia() {
        var original = UserMessage.builder().text("current").media(List.of(image())).metadata(Map.of("origin", "web")).build();
        var tagged = new BaseAgent.CurrentTurnUserMessage(original, null).userMessage();
        assertEquals(true, tagged.getMetadata().get(USER_INPUT));
        assertEquals("web", tagged.getMetadata().get("origin")); assertEquals(original.getMedia(), tagged.getMedia());
        assertFalse(original.getMetadata().containsKey(USER_INPUT));
    }
}
