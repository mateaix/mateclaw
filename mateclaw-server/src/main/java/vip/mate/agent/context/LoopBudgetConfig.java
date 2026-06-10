package vip.mate.agent.context;

/**
 * Configuration for per-reasoning-loop message budgeting.
 *
 * <p>Used by {@link LoopMessageBudgeter} to decide when and how to trim the
 * working message list that a ReAct iteration hands to the LLM. Distinct from
 * the multi-turn history compression configured by
 * {@link vip.mate.config.ConversationWindowProperties}: this one applies inside
 * a single user turn while the ReAct loop accumulates reasoning steps and
 * tool-call/tool-response pairs.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code triggerTokens} — token threshold above which budgeting kicks
 *       in. Compared against {@code historyTokens + reservedPrefixTokens}
 *       so the budgeter accounts for the full prompt the LLM will see,
 *       not just the message list.</li>
 *   <li>{@code keepTailTokens} — token budget reserved for the tail (recent
 *       observations + the current user message). Scales with the model
 *       window instead of relying on a fixed count.</li>
 *   <li>{@code minTailMessages} — floor on the kept-tail count. Prevents a
 *       single huge tool output from collapsing the tail to one message and
 *       losing recent reasoning context.</li>
 *   <li>{@code tailSoftCeilingRatio} — multiplier applied to
 *       {@code keepTailTokens} when honoring the floor or pulling back to
 *       keep a tool pair whole. Lets the tail overshoot the hard budget by
 *       up to this factor before more aggressive cuts kick in.</li>
 *   <li>{@code reservedPrefixTokens} — estimated tokens consumed by the
 *       non-history portion of the prompt (system prompt, skill catalog,
 *       runtime context, wiki injection, tool schemas, output reserve).
 *       Surfaces these from the caller so the budget covers the whole
 *       prompt, not just the message list.</li>
 *   <li>{@code targetMaxMessages} — soft ceiling on the count fed to the
 *       LLM. Best-effort: the budgeter may exceed it slightly to keep a
 *       tool pair whole rather than orphan a call/response — that case is
 *       reported via {@code BudgetTrace.capExceededForPairIntegrity}.</li>
 * </ul>
 */
public record LoopBudgetConfig(
        int triggerTokens,
        int keepTailTokens,
        int minTailMessages,
        double tailSoftCeilingRatio,
        int reservedPrefixTokens,
        int targetMaxMessages) {

    /** Smallest useful trigger threshold; below this budgeting is effectively disabled. */
    public static final int MIN_TRIGGER_TOKENS = 1_000;

    /** Smallest sensible tail budget; below this even one observation may not fit. */
    public static final int MIN_TAIL_TOKENS = 2_000;

    /** Floor on minTailMessages — fewer than 3 collapses recent context too aggressively. */
    public static final int MIN_TAIL_MESSAGES_FLOOR = 3;

    /** Floor on the soft ceiling ratio — anything below 1.0 is degenerate. */
    public static final double MIN_TAIL_SOFT_CEILING_RATIO = 1.0;

    /** Smallest sensible target cap; below this even a normal ReAct loop trips it. */
    public static final int MIN_TARGET_MAX = 20;

    public LoopBudgetConfig {
        if (triggerTokens < MIN_TRIGGER_TOKENS) {
            throw new IllegalArgumentException(
                    "triggerTokens must be >= " + MIN_TRIGGER_TOKENS + ", got " + triggerTokens);
        }
        if (keepTailTokens < MIN_TAIL_TOKENS) {
            throw new IllegalArgumentException(
                    "keepTailTokens must be >= " + MIN_TAIL_TOKENS + ", got " + keepTailTokens);
        }
        if (minTailMessages < MIN_TAIL_MESSAGES_FLOOR) {
            throw new IllegalArgumentException(
                    "minTailMessages must be >= " + MIN_TAIL_MESSAGES_FLOOR
                            + ", got " + minTailMessages);
        }
        if (tailSoftCeilingRatio < MIN_TAIL_SOFT_CEILING_RATIO) {
            throw new IllegalArgumentException(
                    "tailSoftCeilingRatio must be >= " + MIN_TAIL_SOFT_CEILING_RATIO
                            + ", got " + tailSoftCeilingRatio);
        }
        if (reservedPrefixTokens < 0) {
            throw new IllegalArgumentException(
                    "reservedPrefixTokens must be >= 0, got " + reservedPrefixTokens);
        }
        if (targetMaxMessages < MIN_TARGET_MAX) {
            throw new IllegalArgumentException(
                    "targetMaxMessages must be >= " + MIN_TARGET_MAX
                            + ", got " + targetMaxMessages);
        }
        if (keepTailTokens >= triggerTokens) {
            throw new IllegalArgumentException(
                    "keepTailTokens (" + keepTailTokens + ") must be < triggerTokens ("
                            + triggerTokens + ") — otherwise budgeting would never reduce anything");
        }
    }

    /** Tail budget after applying the soft ceiling. */
    public int tailSoftCeilingTokens() {
        return (int) (keepTailTokens * tailSoftCeilingRatio);
    }

    /**
     * Derive a sensible config from a model's context window. The ratios were
     * chosen so the budgeter triggers well before the model's actual limit and
     * leaves enough headroom for the LLM's own response.
     *
     * <ul>
     *   <li>trigger = 50% of the window — same threshold the multi-turn
     *       compressor uses, so the two layers stay calibrated.</li>
     *   <li>tail budget = 30% of the window.</li>
     *   <li>minTailMessages = 4 — at least one full reasoning/action cycle
     *       stays visible to the LLM no matter how big a single tool output is.</li>
     *   <li>tailSoftCeilingRatio = 1.5 — let the tail overshoot by 50% when
     *       enforcing the floor or pulling back to keep a tool pair whole.</li>
     *   <li>reservedPrefixTokens = 0 — caller should override with the real
     *       prefix estimate; left at 0 the budget still works but errs on
     *       the side of triggering later than it should.</li>
     *   <li>targetMaxMessages = 200 — well above a normal ReAct loop's 20–40
     *       working messages, low enough to be a meaningful guard rail.</li>
     * </ul>
     */
    public static LoopBudgetConfig forContext(int contextWindowTokens) {
        if (contextWindowTokens <= 0) {
            contextWindowTokens = 32_000;
        }
        int trigger = Math.max(MIN_TRIGGER_TOKENS, (int) (contextWindowTokens * 0.50));
        int tail = Math.max(MIN_TAIL_TOKENS, (int) (contextWindowTokens * 0.30));
        if (tail >= trigger) {
            tail = Math.max(MIN_TAIL_TOKENS, trigger - MIN_TRIGGER_TOKENS);
        }
        return new LoopBudgetConfig(trigger, tail, 4, 1.5, 0, 200);
    }

    /** Return a copy with {@code reservedPrefixTokens} replaced. */
    public LoopBudgetConfig withReservedPrefixTokens(int reservedPrefixTokens) {
        return new LoopBudgetConfig(triggerTokens, keepTailTokens, minTailMessages,
                tailSoftCeilingRatio, reservedPrefixTokens, targetMaxMessages);
    }
}
