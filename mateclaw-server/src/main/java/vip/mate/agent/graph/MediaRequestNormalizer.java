package vip.mate.agent.graph;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import java.util.ArrayList;
import java.util.List;

/** Request-only media validation; canonical messages are never modified. */
public final class MediaRequestNormalizer {
    private static final String EMPTY_MEDIA = "[附件内容为空，未传入模型；请告知用户重新提供有效附件。]";
    private MediaRequestNormalizer() {}

    public static Prompt normalize(Prompt prompt) {
        if (prompt == null) return null;
        List<Message> result = new ArrayList<>(prompt.getInstructions().size());
        boolean changed = false;
        for (Message message : prompt.getInstructions()) {
            Message normalized = message;
            if (message instanceof UserMessage user) {
                var parts = validParts(user.getParts());
                var media = media(parts);
                if (!parts.equals(user.getParts())) {
                    String text = user.getText();
                    if (media.isEmpty() && (text == null || text.isBlank())) text = EMPTY_MEDIA;
                    var builder = UserMessage.builder().metadata(user.getMetadata()).parts(parts);
                    if (!java.util.Objects.equals(text, user.getText())) builder.text(text);
                    normalized = builder.build();
                }
            } else if (message instanceof AssistantMessage assistant) {
                var parts = validParts(assistant.getParts());
                var media = media(parts);
                if (!parts.equals(assistant.getParts())) {
                    String text = assistant.getText();
                    if (media.isEmpty() && assistant.getToolCalls().isEmpty() && (text == null || text.isBlank())) text = EMPTY_MEDIA;
                    var builder = AssistantMessage.builder().properties(assistant.getMetadata()).parts(parts);
                    if (!java.util.Objects.equals(text, assistant.getText())) builder.content(text);
                    normalized = builder.build();
                }
            }
            result.add(normalized);
            changed |= normalized != message;
        }
        return changed ? new Prompt(result, prompt.getOptions()) : prompt;
    }

    private static List<MessagePart> validParts(List<MessagePart> parts) {
        List<MessagePart> result = new ArrayList<>(parts.size());
        for (MessagePart part : parts) {
            if (part instanceof MediaPart attachment) {
                if (isEmpty(attachment.media())) continue;
                Media resized = RequestImageResizer.resize(attachment.media());
                result.add(resized == attachment.media() ? part
                        : new MediaPart(resized, attachment.payload(), attachment.attributes()));
            } else {
                result.add(part);
            }
        }
        return result;
    }

    private static List<Media> media(List<MessagePart> parts) {
        return parts.stream().filter(MediaPart.class::isInstance).map(MediaPart.class::cast)
                .map(MediaPart::media).toList();
    }

    private static boolean isEmpty(Media media) {
        Object data = media.getData();
        if (data instanceof byte[] bytes) return bytes.length == 0;
        if (data instanceof String locator) {
            if (locator.isBlank()) return true;
            if (locator.regionMatches(true, 0, "data:", 0, 5)) {
                int comma = locator.indexOf(',');
                return comma >= 0 && locator.substring(comma + 1).isBlank();
            }
        }
        return false;
    }
}
