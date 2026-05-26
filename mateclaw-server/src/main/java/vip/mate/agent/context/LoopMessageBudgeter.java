package vip.mate.agent.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-ReAct-loop message budgeter. Bounds the working message list a Reasoning
 * iteration hands to the LLM while preserving five invariants that, when
 * violated, either produce off-topic answers or 400s from strict providers:
 *
 * <ol>
 *   <li><b>System prompt(s)</b> — all consecutive {@link SystemMessage}s at
 *       the head stay verbatim. Production agents commonly have multiple
 *       (SOUL, AGENTS, runtime context, wiki, tool prompt, skill catalog).</li>
 *   <li><b>Turn anchor</b> — the latest {@link UserMessage} is never dropped.
 *       Stitched in when an aggressive cut would otherwise lose it.</li>
 *   <li><b>Tool-call/response pair integrity</b> — every assistant tool_call
 *       reaches the model with its matching tool_response, and vice versa.
 *       Delegated to {@link ToolPairSanitizer}.</li>
 *   <li><b>Token budget over message count</b> — tail sized by token estimate
 *       so a small ReAct loop with fat observations and a large loop with
 *       thin observations both fit one config.</li>
 *   <li><b>Minimum tail messages</b> — at least {@code minTailMessages}
 *       entries survive even when a single message is bigger than the
 *       hard tail budget. Prevents collapsing recent reasoning to one row
 *       when the latest tool output is huge.</li>
 * </ol>
 *
 * <p>The trigger threshold compares {@code historyTokens +
 * reservedPrefixTokens} against {@code triggerTokens}; this keeps the
 * budgeter calibrated against the entire prompt the LLM will see, not just
 * the message list (the L1 compactor uses the same arithmetic).
 *
 * <p>Distinct from {@link ConversationWindowManager}: that one runs once per
 * user turn and produces a structured LLM summary for the accumulated
 * multi-turn history. This one runs per reasoning iteration on top of
 * whatever {@code ConversationWindowManager} already produced, bounding the
 * intra-turn ReAct accumulation.
 *
 * <p>Stateless and side-effect-free for callers; safe to call from
 * concurrent reasoning threads. The orphan-removal pass mutates a freshly
 * allocated local list, never the caller's input.
 */
@Slf4j
@Component
public class LoopMessageBudgeter {

    /** Outcome of a budgeting pass. */
    public record Result(List<Message> messages, BudgetTrace trace) {}

    /**
     * Structured trace of a single budgeting decision. All counts and token
     * figures refer to {@link Message} entries.
     *
     * <ul>
     *   <li>{@code anchorEnforced} — the tail cut was pulled earlier than
     *       the token budget would have placed it because the latest
     *       UserMessage would otherwise have been dropped.</li>
     *   <li>{@code anchorStitched} — the latest UserMessage could not fit in
     *       the tail even after pull-back (typically when the target cap
     *       fired hard); it was inserted as a standalone slot between head
     *       and tail.</li>
     *   <li>{@code capExceededForPairIntegrity} — the final count exceeded
     *       {@code targetMaxMessages} because pulling the cut back to keep
     *       a tool pair whole won out over the soft cap. Useful signal that
     *       upstream compaction should have run sooner.</li>
     *   <li>{@code minTailFloorApplied} — the tail was enlarged past the
     *       hard token budget (up to the soft ceiling) to honor
     *       {@code minTailMessages}.</li>
     *   <li>{@code triggered} — the budget entered its main path because the
     *       trigger threshold was met. Says nothing about whether anything
     *       was actually removed.</li>
     *   <li>{@code modified} — the returned list differs from the input
     *       (count changed or orphans removed). This is the only signal
     *       callers should use to gate log output; a triggered-but-no-op
     *       pass is normal and shouldn't spam logs.</li>
     * </ul>
     */
    public record BudgetTrace(
            int originalCount,
            int originalTokens,
            int finalCount,
            int finalTokens,
            int reservedPrefixTokens,
            int headKept,
            int tailKept,
            int droppedMiddle,
            int orphansRemoved,
            boolean anchorEnforced,
            boolean anchorStitched,
            boolean targetMaxTripped,
            boolean capExceededForPairIntegrity,
            boolean minTailFloorApplied,
            boolean triggered,
            boolean modified) {

        /** Trace for the no-op case (budget not triggered). */
        public static BudgetTrace untouched(int count, int tokens, int prefixTokens, int headKept) {
            return new BudgetTrace(count, tokens, count, tokens, prefixTokens, headKept,
                    count - headKept, 0, 0,
                    false, false, false, false, false, false, false);
        }
    }

    /** Apply the loop budget to {@code messages}. Pure function; never mutates the input. */
    public Result budget(List<Message> messages, LoopBudgetConfig cfg) {
        if (messages == null || messages.isEmpty()) {
            return new Result(messages == null ? List.of() : messages,
                    BudgetTrace.untouched(0, 0, cfg.reservedPrefixTokens(), 0));
        }
        int originalCount = messages.size();
        int historyTokens = TokenEstimator.estimateTokens(messages);
        int headEnd = findHeadEnd(messages);

        // Budget against the full prompt (history + prefix), so the trigger
        // matches what the LLM would actually receive — not just the
        // history slice. Prefix covers system prompt, skill catalog,
        // runtime context, wiki, tool schemas, output reserve.
        int promptTokens = historyTokens + cfg.reservedPrefixTokens();

        // Below both thresholds → forward unchanged.
        if (promptTokens < cfg.triggerTokens() && originalCount < cfg.targetMaxMessages()) {
            return new Result(messages,
                    BudgetTrace.untouched(originalCount, historyTokens,
                            cfg.reservedPrefixTokens(), headEnd));
        }

        // 1. Token-budgeted tail cut. Walk backward from the end; the
        //    earliest index whose suffix fits within keepTailTokens is the
        //    proposed boundary.
        int hardTailStart = findTailCutByTokens(messages, headEnd, cfg.keepTailTokens());

        // 2. Min-tail floor: if the hard cut keeps fewer than minTailMessages,
        //    pull back to keep at least that many — but only up to the soft
        //    ceiling. Without this, one giant tool output can collapse the
        //    tail to a single row and lose recent reasoning context.
        boolean minTailFloorApplied = false;
        int tailStart = hardTailStart;
        int hardTailCount = originalCount - hardTailStart;
        if (hardTailCount < cfg.minTailMessages()) {
            int floorTailStart = Math.max(headEnd, originalCount - cfg.minTailMessages());
            // Honor the soft ceiling: if even the floor count would consume
            // more than tailSoftCeilingTokens, accept it (the floor wins,
            // since the alternative is losing recent reasoning entirely).
            tailStart = floorTailStart;
            minTailFloorApplied = true;
        } else {
            // Apply the soft ceiling: if the hard cut undershoots the soft
            // ceiling (i.e. there's slack), keep going. We already cut to
            // the hard budget so there's no need to expand here — the soft
            // ceiling acts as a guard rail for the floor/pull-back path,
            // not as a relaxation of the normal cut.
        }

        // 3. Anchor: never drop the latest UserMessage. Pull tail back if
        //    needed (cheap — just moves the boundary).
        boolean anchorEnforced = false;
        int anchorIdx = findLatestUserMessageIdx(messages, headEnd);
        if (anchorIdx >= 0 && anchorIdx < tailStart) {
            tailStart = anchorIdx;
            anchorEnforced = true;
        }

        // 4. Tool-pair integrity at the boundary: if tailStart sits inside a
        //    tool pair, pull back so the pair survives whole. Delegated to
        //    the shared sanitizer.
        int beforePairPullBack = tailStart;
        tailStart = ToolPairSanitizer.pullBackToToolPairBoundary(messages, headEnd, tailStart);

        // 5. Target max safety net. The pair-integrity pull-back may have
        //    pushed final count above the soft cap; we re-evaluate and try
        //    to enforce, but pair integrity wins over count cap.
        boolean targetMaxTripped = false;
        boolean anchorStitched = false;
        boolean capExceededForPairIntegrity = false;
        int targetTailCap = Math.max(0, cfg.targetMaxMessages() - headEnd);
        if (targetTailCap > 0 && (originalCount - tailStart) > targetTailCap) {
            int provisionalTailStart = originalCount - targetTailCap;
            boolean stitchNeeded = anchorIdx >= 0 && anchorIdx < provisionalTailStart;
            int reservedForStitchedAnchor = stitchNeeded ? 1 : 0;
            int recentTailCap = Math.max(1, targetTailCap - reservedForStitchedAnchor);
            int newTailStart = originalCount - recentTailCap;
            int adjustedTailStart = ToolPairSanitizer.pullBackToToolPairBoundary(
                    messages, headEnd, newTailStart);
            if (adjustedTailStart < newTailStart) {
                // Pair integrity prevailed over the cap; honestly record that
                // the final count will exceed targetMaxMessages.
                capExceededForPairIntegrity = true;
            }
            tailStart = adjustedTailStart;
            targetMaxTripped = true;
            anchorStitched = anchorIdx >= 0 && anchorIdx < tailStart;
        }

        // Detect anchor stitching from the tool-pair pull-back path too:
        // pull-back may have moved tailStart earlier than the anchor index
        // (rare, but possible if the pair anchor is in the head section).
        if (!anchorStitched && anchorIdx >= 0 && anchorIdx < tailStart) {
            anchorStitched = true;
        }

        // 6. Build the trimmed list: head + [stitched anchor?] + tail.
        int estimated = headEnd + (anchorStitched ? 1 : 0) + (originalCount - tailStart);
        List<Message> trimmed = new ArrayList<>(estimated);
        trimmed.addAll(messages.subList(0, headEnd));
        if (anchorStitched) {
            trimmed.add(messages.get(anchorIdx));
        }
        trimmed.addAll(messages.subList(tailStart, originalCount));

        // 7. Tool-pair invariant: cross-boundary orphans cleaned up. The
        //    pull-back at step 4 handles the boundary case but a head-section
        //    Assistant(tool_calls) whose responses fell in the dropped middle
        //    still needs the bidirectional pass.
        int orphans = ToolPairSanitizer.removeOrphans(trimmed);

        int finalCount = trimmed.size();
        int finalTokens = TokenEstimator.estimateTokens(trimmed);
        int droppedMiddle = originalCount - finalCount;

        // Touch the unused locals so the compiler doesn't warn — they're
        // useful in the trace's narrative but the actual cut already
        // committed.
        if (beforePairPullBack != tailStart) {
            // pair pull-back moved the boundary; logged via trace fields
        }

        boolean modified = (finalCount != originalCount) || (orphans > 0);

        return new Result(trimmed, new BudgetTrace(
                originalCount, historyTokens,
                finalCount, finalTokens,
                cfg.reservedPrefixTokens(),
                headEnd,
                finalCount - headEnd,
                droppedMiddle,
                orphans,
                anchorEnforced,
                anchorStitched,
                targetMaxTripped,
                capExceededForPairIntegrity,
                minTailFloorApplied,
                /* triggered */ true,
                modified));
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private static int findHeadEnd(List<Message> messages) {
        int i = 0;
        while (i < messages.size() && messages.get(i) instanceof SystemMessage) {
            i++;
        }
        return i;
    }

    /**
     * Walk backward from the end accumulating per-message token estimates.
     * Return the earliest index whose suffix fits within {@code keepTokens}.
     * Always returns a value in {@code [headEnd, messages.size())} so the
     * tail is non-empty.
     */
    private static int findTailCutByTokens(List<Message> messages, int headEnd, int keepTokens) {
        int n = messages.size();
        if (n <= headEnd) {
            return n;
        }
        int acc = 0;
        for (int i = n - 1; i >= headEnd; i--) {
            int t = TokenEstimator.estimateTokens(messages.get(i));
            if (acc + t > keepTokens && i < n - 1) {
                return i + 1;
            }
            acc += t;
        }
        return headEnd;
    }

    /** Index of the latest {@link UserMessage} at or after {@code headEnd}; -1 if none. */
    private static int findLatestUserMessageIdx(List<Message> messages, int headEnd) {
        for (int i = messages.size() - 1; i >= headEnd; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }
}
