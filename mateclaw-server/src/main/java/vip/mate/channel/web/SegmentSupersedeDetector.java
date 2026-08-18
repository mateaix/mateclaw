package vip.mate.channel.web;

import java.util.List;
import java.util.Map;

/**
 * Marks assistant content emitted <em>before</em> its tool calls ran as superseded
 * by the post-tool content that follows.
 *
 * <p>The rule is purely structural — no text inspection. A content segment that
 * (a) does not directly follow a tool result and (b) is followed by a tool call
 * before any other content segment was produced in the same model completion as
 * those tool calls. Whatever it says — process narration, a predicted result, or
 * an answer copied from stale conversation history — it is not grounded in this
 * turn's observations. When any content segment exists after that tool call
 * (the answer written with the actual results in hand), the pre-tool segment is
 * marked superseded so renderers collapse it in favor of the grounded answer.
 *
 * <p>Content that directly follows a tool result is never marked: it was written
 * after observing real output and may carry standalone value (e.g. a download
 * link for an intermediate artifact in a multi-file run).
 */
final class SegmentSupersedeDetector {

    static final String REASON_PRE_TOOL_CONTENT_REPLACED = "pre_tool_content_replaced_by_post_tool_answer";

    private SegmentSupersedeDetector() {
    }

    static void markSuperseded(List<Map<String, Object>> segments) {
        if (segments == null || segments.size() < 3) {
            return;
        }

        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> candidate = segments.get(i);
            if (!isContent(candidate) || Boolean.TRUE.equals(candidate.get("superseded"))
                    || followsToolResult(segments, i)) {
                continue;
            }

            int toolIndex = nextToolIndexBeforeContent(segments, i + 1);
            if (toolIndex < 0) {
                continue;
            }

            // The replacement is the first content segment written after the tool
            // ran — grounded in its observation. Later tool calls may sit in
            // between (parallel or chained calls from the same completion), so the
            // scan crosses tool boundaries. Tool success is irrelevant: on failure
            // the post-tool content carries the authoritative failure explanation,
            // which supersedes an optimistic pre-tool claim all the same.
            int replacementIndex = nextContentIndex(segments, toolIndex + 1);
            if (replacementIndex < 0) {
                continue;
            }

            Map<String, Object> replacement = segments.get(replacementIndex);
            candidate.put("superseded", true);
            candidate.put("supersededBySegmentId", String.valueOf(replacement.getOrDefault("id", "")));
            candidate.put("supersededReason", REASON_PRE_TOOL_CONTENT_REPLACED);
        }
    }

    /**
     * Index of the next tool_call segment after {@code start}, or -1 when a
     * content segment appears first — a following content segment means the
     * candidate closed its completion without issuing tool calls, so it is not
     * pre-tool narration.
     */
    private static int nextToolIndexBeforeContent(List<Map<String, Object>> segments, int start) {
        for (int i = start; i < segments.size(); i++) {
            Map<String, Object> segment = segments.get(i);
            if (isToolCall(segment)) {
                return i;
            }
            if (isContent(segment)) {
                return -1;
            }
        }
        return -1;
    }

    /** Whether the nearest preceding non-thinking segment is a tool call. */
    private static boolean followsToolResult(List<Map<String, Object>> segments, int index) {
        for (int i = index - 1; i >= 0; i--) {
            Map<String, Object> segment = segments.get(i);
            if (isContent(segment)) {
                return false;
            }
            if (isToolCall(segment)) {
                return true;
            }
        }
        return false;
    }

    /** First content segment at or after {@code start}, crossing tool boundaries; -1 when none. */
    private static int nextContentIndex(List<Map<String, Object>> segments, int start) {
        for (int i = start; i < segments.size(); i++) {
            if (isContent(segments.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isContent(Map<String, Object> segment) {
        return segment != null && "content".equals(segment.get("type"));
    }

    private static boolean isToolCall(Map<String, Object> segment) {
        return segment != null && "tool_call".equals(segment.get("type"));
    }
}
