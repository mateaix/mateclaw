package vip.mate.context.intelligence.budget;

/**
 * Token budget (return value of {@link TokenBudgetPlanner}).
 * <p>
 * Computed by the multi-factor budget planner from effectiveWindow + pressure + diversity.
 *
 * @param historyBudget        available tokens for history messages
 * @param injectionBudget      available tokens for injected content (memory/wiki/tool)
 * @param compactTriggerTokens threshold that triggers compaction
 * @param keepTailTokens       tail-retained tokens
 * @param trace                decision trace (for monitoring, nullable)
 * @author MateClaw Team
 */
public record TokenBudget(
        int historyBudget,
        int injectionBudget,
        int compactTriggerTokens,
        int keepTailTokens,
        TokenBudgetTrace trace
) {
    /**
     * fallback factory: derive from yml static values when the snapshot is empty.
     * <p>
     * Ratios are consistent with v1: history 60% / injection 40% / compactTrigger 60% / keepTail 36%.
     */
    public static TokenBudget legacy(int fallbackWindow) {
        return new TokenBudget(
                (int) (fallbackWindow * 0.60),    // historyBudget
                (int) (fallbackWindow * 0.40),    // injectionBudget
                (int) (fallbackWindow * 0.60),    // compactTrigger
                (int) (fallbackWindow * 0.36),    // keepTail
                null);                            // no trace
    }
}
