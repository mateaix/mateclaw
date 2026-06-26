package vip.mate.context.intelligence.budget;

import vip.mate.context.intelligence.enums.ResourcePressure;

/**
 * Budget decision trace record (for monitoring/debugging, to locate the root cause of budget anomalies).
 * <p>
 * Named {@code TokenBudgetTrace} rather than {@code BudgetTrace}, because the nested record
 * {@code LoopMessageBudgeter.BudgetTrace} already exists; a top-level class with the same name
 * would cause import ambiguity.
 *
 * @param baseWindow         original effectiveWindow
 * @param ceiledWindow       window after applying the P10 ceiling
 * @param safeWindowApplied  safeWindow actually applied (0 means not applied)
 * @param pressure           pressure level at decision time
 * @param historyRatio       history ratio actually used
 * @param systemTokens       system message tokens
 * @param toolsTokens        tool declaration tokens
 * @param outputReserve      reserved output tokens
 * @param legacyFallback     whether the yml fallback was used
 * @param phase              WindowProbe phase at decision time (COLD/PROBING/...), null means unknown
 * @param confidenceLower    lower bound of the state machine confidence at decision time
 * @param confidenceUpper    upper bound of the state machine confidence at decision time
 * @param reason             decision path marker: normal / diversity_clamp / pressure_adjust / fallback_yml / fallback_hardcode
 * @author MateClaw Team
 */
public record TokenBudgetTrace(
        int baseWindow,
        int ceiledWindow,
        int safeWindowApplied,
        ResourcePressure pressure,
        double historyRatio,
        int systemTokens,
        int toolsTokens,
        int outputReserve,
        boolean legacyFallback,
        String phase,
        int confidenceLower,
        int confidenceUpper,
        String reason
) {}
