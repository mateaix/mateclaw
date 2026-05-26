package vip.mate.agent.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-function utilities that enforce the OpenAI-compatible
 * tool_call ↔ tool_response pairing invariant:
 *
 * <ul>
 *   <li>Every {@code tool_call.id} on an {@link AssistantMessage} has a
 *       matching {@code tool_response.id} on a {@link ToolResponseMessage}
 *       <em>after</em> it in the list.</li>
 *   <li>Every {@code tool_response.id} on a {@link ToolResponseMessage} has
 *       a matching {@code tool_call.id} on an {@link AssistantMessage}
 *       <em>before</em> it.</li>
 *   <li>No empty/null ids on either side.</li>
 * </ul>
 *
 * <p>Violating either rule causes strict providers (kimi-code, anthropic in
 * tool-use mode, OpenAI's responses API on certain models) to reject the
 * request with a 400 error such as
 * {@code "tool_call_id is not found"}. This sanitizer is the single source of
 * truth for that invariant — any trim / cut / window logic should run its
 * pre/post passes here rather than reimplementing them.
 *
 * <p>All methods are {@code static} and side-effect-free except where
 * documented (e.g. {@link #removeOrphans(List)} mutates the list in place to
 * avoid an extra allocation hot in the reasoning loop). They never touch the
 * input list when no fix is needed.
 */
public final class ToolPairSanitizer {

    private ToolPairSanitizer() {
        // utility class
    }

    /**
     * Pull a proposed cut boundary earlier so an Assistant(tool_calls) that
     * issued ids matching {@link ToolResponseMessage}s in the kept tail
     * survives into the tail alongside its responses. Prevents producing an
     * orphan response at the cut boundary in the first place.
     *
     * @param messages  full message list (read-only)
     * @param headEnd   index after the last protected head message
     * @param tailStart proposed boundary; messages at and after this index
     *                  are kept, those between {@code headEnd} and
     *                  {@code tailStart} are dropped
     * @return possibly-earlier {@code tailStart} that keeps tool pairs whole
     */
    public static int pullBackToToolPairBoundary(List<Message> messages, int headEnd, int tailStart) {
        if (tailStart <= headEnd || messages == null || messages.isEmpty()) {
            return tailStart;
        }
        Set<String> tailResponseIds = new HashSet<>();
        for (int i = tailStart; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (r.id() != null && !r.id().isEmpty()) {
                        tailResponseIds.add(r.id());
                    }
                }
            }
        }
        if (tailResponseIds.isEmpty()) {
            return tailStart;
        }
        for (int i = tailStart - 1; i >= headEnd; i--) {
            Message m = messages.get(i);
            if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                boolean overlaps = am.getToolCalls().stream()
                        .anyMatch(tc -> tc.id() != null && tailResponseIds.contains(tc.id()));
                if (overlaps) {
                    return i;
                }
            }
        }
        return tailStart;
    }

    /**
     * Iteratively remove tool-pair orphans from {@code messages} (mutates the
     * list in place). Two shapes are handled:
     *
     * <p><b>P0</b>: a {@link ToolResponseMessage} whose response id has no
     * matching assistant tool_call in the list.
     *
     * <p><b>P1</b>: an {@link AssistantMessage} whose every tool_call id
     * has no matching response in the list. (An assistant with both matched
     * and unmatched calls is left alone — removing it would harm more than
     * it helps; strict providers tolerate extra calls more readily than
     * dropping the whole assistant message.)
     *
     * <p>Iterates until convergence: removing an assistant for P1 can expose
     * a P0 orphan that needs cleaning, and vice versa.
     *
     * <p>Also removes any tool_call or tool_response with a null or empty id
     * — those have no useful pairing semantics and confuse both the strict
     * providers and the matching logic.
     *
     * @return total number of messages removed across all passes
     */
    public static int removeOrphans(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int totalRemoved = 0;
        boolean changed;
        do {
            Set<String> callIds = new HashSet<>();
            Set<String> respIds = new HashSet<>();
            for (Message m : messages) {
                if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        if (tc.id() != null && !tc.id().isEmpty()) {
                            callIds.add(tc.id());
                        }
                    }
                }
                if (m instanceof ToolResponseMessage trm) {
                    for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                        if (r.id() != null && !r.id().isEmpty()) {
                            respIds.add(r.id());
                        }
                    }
                }
            }
            int before = messages.size();
            messages.removeIf(m -> {
                if (m instanceof ToolResponseMessage trm) {
                    // P0: a response with a null/empty id, or whose id has
                    // no matching tool_call.
                    return trm.getResponses().stream().anyMatch(r ->
                            r.id() == null || r.id().isEmpty() || !callIds.contains(r.id()));
                }
                if (m instanceof AssistantMessage am && am.getToolCalls() != null
                        && !am.getToolCalls().isEmpty()) {
                    // P1: every tool_call on this assistant has no matching response.
                    return am.getToolCalls().stream().allMatch(tc ->
                            tc.id() == null || tc.id().isEmpty() || !respIds.contains(tc.id()));
                }
                return false;
            });
            int removed = before - messages.size();
            totalRemoved += removed;
            changed = removed > 0;
        } while (changed);
        return totalRemoved;
    }

    /**
     * Post-condition check: returns {@code true} iff {@code messages}
     * satisfies the pairing invariant — every assistant tool_call has a
     * matching response after it, every response has a matching call before
     * it, all ids are non-empty. Intended for tests and defensive asserts;
     * production code should run {@link #removeOrphans(List)} which
     * guarantees this holds on return.
     */
    public static boolean isPaired(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return true;
        }
        Set<String> callIds = new HashSet<>();
        Set<String> respIds = new HashSet<>();
        for (Message m : messages) {
            if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    if (tc.id() == null || tc.id().isEmpty()) return false;
                    callIds.add(tc.id());
                }
            }
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (r.id() == null || r.id().isEmpty()) return false;
                    respIds.add(r.id());
                }
            }
        }
        for (String c : callIds) {
            if (!respIds.contains(c)) return false;
        }
        for (String r : respIds) {
            if (!callIds.contains(r)) return false;
        }
        return true;
    }
}
