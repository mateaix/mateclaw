package vip.mate.agent;

import java.util.Locale;

/**
 * Semantic category of a content-bearing stream delta, assigned once at the
 * producer (the agent graph) where the classification inputs — whether the
 * completion carried tool calls and whether any tool observation preceded the
 * text this turn — are definitively known.
 *
 * <p>Downstream consumers (web segment persistence, IM channel adapters, the
 * SSE client) MUST read this tag instead of re-deriving the category from
 * stream structure. Deltas from producers that predate this tag carry
 * {@code null}; consumers fall back to their legacy structural handling in
 * that case.
 */
public enum ContentKind {

    /**
     * Text emitted in a completion that also carries tool calls, before any
     * tool observation this turn. Not grounded in this turn's results — it may
     * be process narration or a fully fabricated "rehearsal" of the outcome.
     * Provisional: replaced by the next content of the same turn if one
     * arrives, kept only when the turn produces no later content at all.
     */
    PRE_TOOL_NARRATION,

    /**
     * Intermediate narration emitted after at least one tool observation this
     * turn (even when the same completion issues further tool calls). Grounded
     * in real results; never replaced.
     */
    GROUNDED_NARRATION,

    /** Final-answer text of the terminal turn. */
    FINAL_ANSWER;

    /** Stable lower-case token used in persisted segment metadata and SSE payloads. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
