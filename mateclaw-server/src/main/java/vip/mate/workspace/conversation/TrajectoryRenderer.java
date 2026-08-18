package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders a conversation into one linear, diffable transcript.
 * <p>
 * The chat UI is the wrong tool for verifying what a turn actually did: it
 * collapses reasoning, hides superseded spans, and its ordering has its own
 * bugs. This renderer reads the same {@code metadata.segments} timeline the UI
 * does and prints it verbatim, in emission order, with each span tagged by
 * kind — so "what did the model reason before that tool call" is answered by
 * reading, not by clicking through collapsed panels.
 * <p>
 * Output is plain text and intentionally boring, so it can be pasted into an
 * issue, diffed between two runs, or grepped:
 * <pre>
 * ## [3] assistant
 * &lt;think&gt;
 * ...
 * &lt;/think&gt;
 * &lt;tool_call name="execute_code"&gt;
 * {"code": "..."}
 * &lt;/tool_call&gt;
 * &lt;tool_response success="true"&gt;
 * ...
 * &lt;/tool_response&gt;
 * </pre>
 *
 * @author MateClaw Team
 */
@Slf4j
public class TrajectoryRenderer {

    private final ObjectMapper objectMapper;

    public TrajectoryRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Render an ordered list of messages. Assistant turns are expanded from
     * their segment timeline; every other role prints its rendered content.
     *
     * @param renderedContent per-message user-visible content, index-aligned with {@code messages}
     */
    public String render(String conversationId, List<MessageEntity> messages, List<String> renderedContent) {
        StringBuilder out = new StringBuilder();
        out.append("# trajectory ").append(conversationId).append('\n');
        out.append("# messages=").append(messages.size()).append('\n');

        for (int i = 0; i < messages.size(); i++) {
            MessageEntity m = messages.get(i);
            String role = m.getRole() != null ? m.getRole() : "unknown";
            out.append("\n## [").append(i).append("] ").append(role).append('\n');

            if (!"assistant".equals(role)) {
                appendBlock(out, textAt(renderedContent, i));
                continue;
            }
            List<JsonNode> segments = orderedSegments(m);
            if (segments.isEmpty()) {
                // No timeline (legacy row, or a turn that never streamed) — the
                // rendered content is all there is. Say so rather than emitting
                // an empty turn that reads like the model produced nothing.
                out.append("# (no segment timeline — rendered content only)\n");
                appendBlock(out, textAt(renderedContent, i));
                continue;
            }
            for (JsonNode seg : segments) {
                appendSegment(out, seg);
            }
        }
        return out.toString();
    }

    /**
     * Segments in emission order. Sorts by the producer-assigned {@code seq};
     * rows written before that field existed keep their stored array order,
     * which is the same order for those rows.
     */
    private List<JsonNode> orderedSegments(MessageEntity message) {
        List<JsonNode> segments = new ArrayList<>();
        String metadata = message.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return segments;
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            // An H2 JSON column hands the document back wrapped as a JSON string
            // literal, so a plain parse yields a TextNode and the timeline reads
            // as absent rather than as a parse failure. MessageVO unwraps the
            // same way for the chat UI; without it here the transcript quietly
            // degrades to "no segment timeline" on exactly the rows that have one.
            if (root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
            JsonNode node = root.path("segments");
            if (!node.isArray()) {
                return segments;
            }
            node.forEach(segments::add);
        } catch (Exception e) {
            log.warn("Failed to parse segments for message {}: {}", message.getId(), e.getMessage());
            return segments;
        }
        if (segments.stream().allMatch(s -> s.path("seq").isNumber())) {
            segments.sort(Comparator.comparingInt(s -> s.path("seq").asInt()));
        }
        return segments;
    }

    private void appendSegment(StringBuilder out, JsonNode seg) {
        String type = seg.path("type").asText("");
        switch (type) {
            case "thinking" -> {
                out.append("<think>\n");
                appendBlock(out, seg.path("thinkingText").asText(""));
                out.append("</think>\n");
            }
            case "tool_call" -> {
                out.append("<tool_call name=\"").append(seg.path("toolName").asText("")).append("\">\n");
                appendBlock(out, seg.path("toolArgs").asText(""));
                out.append("</tool_call>\n");
                out.append("<tool_response success=\"")
                        .append(seg.path("toolSuccess").asBoolean(true)).append("\">\n");
                appendBlock(out, seg.path("toolResult").asText(""));
                out.append("</tool_response>\n");
            }
            case "content" -> {
                // A superseded span is content the model drafted before its
                // tools ran. It is dropped from the UI but kept here — a wrong
                // answer that got corrected is exactly what a replay is after.
                if (seg.path("superseded").asBoolean(false)) {
                    out.append("<content superseded=\"true\">\n");
                } else {
                    out.append("<content>\n");
                }
                appendBlock(out, seg.path("text").asText(""));
                out.append("</content>\n");
            }
            default -> {
                out.append("<segment type=\"").append(type).append("\"/>\n");
            }
        }
    }

    private static String textAt(List<String> rendered, int index) {
        return rendered != null && index < rendered.size() ? rendered.get(index) : "";
    }

    /** Append a body, guaranteeing exactly one trailing newline and no blank body. */
    private static void appendBlock(StringBuilder out, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        out.append(body.stripTrailing()).append('\n');
    }
}
