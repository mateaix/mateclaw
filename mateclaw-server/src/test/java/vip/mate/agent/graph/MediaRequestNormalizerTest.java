package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import java.net.URI;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MediaRequestNormalizerTest {
    static Media empty() { return new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[0])); }
    static Media valid() { return new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.invalid/image.png")); }
    @Test void dropsEmptyBytesButKeepsValidMediaAndMetadataWithoutMutatingOriginal() {
        var image = valid();
        var user = UserMessage.builder().text("describe").media(List.of(empty(), image)).metadata(Map.of("source", "user")).build();
        var prompt = new Prompt(List.of(user));
        var normalized = MediaRequestNormalizer.normalize(prompt);
        var result = (UserMessage) normalized.getInstructions().getFirst();
        assertEquals(List.of(image), result.getMedia());
        assertEquals(user.getText(), result.getText()); assertEquals(user.getMetadata(), result.getMetadata());
        assertEquals(2, user.getMedia().size());
        assertSame(prompt.getOptions(), normalized.getOptions());
    }
    @Test void mediaOnlyEmptyInputBecomesAnHonestPlaceholder() {
        var user = UserMessage.builder().text("").media(List.of(empty())).build();
        var result = (UserMessage) MediaRequestNormalizer.normalize(new Prompt(List.of(user))).getInstructions().getFirst();
        assertTrue(result.getMedia().isEmpty()); assertFalse(result.getText().isBlank());
        assertTrue(result.getText().contains("为空"));
    }
    @Test void assistantToolCallsAndPropertiesSurviveFiltering() {
        var call = new AssistantMessage.ToolCall("id", "function", "tool", "{}");
        var assistant = AssistantMessage.builder().content("").properties(Map.of("reasoning_content", "evidence"))
                .toolCalls(List.of(call)).media(List.of(empty())).build();
        var result = (AssistantMessage) MediaRequestNormalizer.normalize(new Prompt(List.of(assistant))).getInstructions().getFirst();
        assertTrue(result.getMedia().isEmpty()); assertEquals(List.of(call), result.getToolCalls());
        assertEquals(assistant.getMetadata(), result.getMetadata()); assertEquals("", result.getText());
    }
    @Test void blankLocatorsAndEmptyDataUrisAreFiltered() {
        for (String uri : List.of("", "data:image/png;base64,")) {
            var user = UserMessage.builder().text("describe").media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, URI.create(uri)))).build();
            assertTrue(((UserMessage) MediaRequestNormalizer.normalize(new Prompt(List.of(user))).getInstructions().getFirst()).getMedia().isEmpty());
        }
    }
    @Test void validPromptAndToolResponsesAreUnchanged() {
        var user = UserMessage.builder().text("").media(List.of(valid())).build();
        var response = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id", "tool", ""))).build();
        var prompt = new Prompt(List.of(user, response));
        assertSame(prompt, MediaRequestNormalizer.normalize(prompt));
    }
    @Test void streamingEgressFiltersEmptyMediaBeforeCallingProvider() {
        var model = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(model.stream(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new org.springframework.ai.chat.model.Generation(new AssistantMessage("ok"))))));
        var helper = new NodeStreamingChatHelper(org.mockito.Mockito.mock(vip.mate.channel.web.ChatStreamTracker.class));
        var user = UserMessage.builder().text("describe").media(List.of(empty(), valid())).build();
        helper.streamCall(model, new Prompt(List.of(user)), "empty-media-test", "test");
        var captured = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).stream(captured.capture());
        var sent = (UserMessage) captured.getValue().getInstructions().getFirst();
        assertEquals(1, sent.getMedia().size()); assertEquals(2, user.getMedia().size());
    }
}
