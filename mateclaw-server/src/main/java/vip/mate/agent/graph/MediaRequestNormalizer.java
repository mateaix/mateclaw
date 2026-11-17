package vip.mate.agent.graph;

import org.springframework.ai.chat.messages.*;
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
                var media = validMedia(user.getMedia());
                if (media.size() != user.getMedia().size()) {
                    String text = user.getText();
                    if (media.isEmpty() && (text == null || text.isBlank())) text = EMPTY_MEDIA;
                    normalized = UserMessage.builder().text(text).media(media).metadata(user.getMetadata()).build();
                }
            } else if (message instanceof AssistantMessage assistant) {
                var media = validMedia(assistant.getMedia());
                if (media.size() != assistant.getMedia().size()) {
                    String text = assistant.getText();
                    if (media.isEmpty() && assistant.getToolCalls().isEmpty() && (text == null || text.isBlank())) text = EMPTY_MEDIA;
                    normalized = AssistantMessage.builder().content(text).media(media)
                            .toolCalls(assistant.getToolCalls()).properties(assistant.getMetadata()).build();
                }
            }
            result.add(normalized);
            changed |= normalized != message;
        }
        return changed ? new Prompt(result, prompt.getOptions()) : prompt;
    }

    private static List<Media> validMedia(List<Media> media) {
        return media.stream().filter(item -> !isEmpty(item)).toList();
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
