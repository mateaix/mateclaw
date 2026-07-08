package vip.mate.agent.context;

/**
 * Per-agent-build token budget for the prompt prefix's optional injection
 * blocks. Produced once by {@link PrefixBudgetPlanner} when the agent graph
 * is assembled (the inputs — effective window, base prompt, tool schemas —
 * are all stable per build) and handed to each injection site.
 *
 * <p>{@code enabled == false} means budgeting is switched off: every budget
 * field holds {@link Integer#MAX_VALUE} and consumers keep their existing
 * absolute caps untouched.
 */
public record PrefixBudgetPlan(
        boolean enabled,
        int effectiveMaxTokens,
        Profile profile,
        int injectionBudgetTokens,
        int memoryTokens,
        int wikiTokens,
        int skillCatalogTokens,
        int extensionCatalogTokens,
        int ledgerTokens,
        int toolSchemaBudgetTokens) {

    /** Window-size tier. Small windows tighten the injection ratio. */
    public enum Profile {
        /** Regular window — budget shares rarely bind (absolute caps are smaller). */
        NORMAL,
        /** Window below the compact threshold — tightened injection ratio. */
        COMPACT,
        /** Window below the minimal threshold — injection cut to the bone. */
        MINIMAL
    }

    /** Budgeting disabled — unlimited budgets, previous behavior. */
    public static PrefixBudgetPlan unlimited(int effectiveMaxTokens) {
        return new PrefixBudgetPlan(false, effectiveMaxTokens, Profile.NORMAL,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }
}
