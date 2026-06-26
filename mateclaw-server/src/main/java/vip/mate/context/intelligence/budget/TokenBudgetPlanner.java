package vip.mate.context.intelligence.budget;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.context.intelligence.config.ContextIntelligenceProperties;
import vip.mate.context.intelligence.enums.ResourcePressure;
import vip.mate.context.intelligence.snapshot.EnvSnapshot;

/**
 * Multi-factor budget planner (exit of the read path).
 * <p>
 * Combines EnvSnapshot's effectiveWindow + pressure + diversity to compute the token budget
 * for each round of the ReAct loop.
 * <p>
 * <b>Three-tier fallback</b> (§8.1):
 * <ol>
 *   <li>EnvSnapshot valid (effectiveWindow > 0) -> dynamic value + multi-factor budget planning</li>
 *   <li>EnvSnapshot empty -> fall back to yml (caller passes fallbackWindow)</li>
 *   <li>No yml config either -> fall back to 128K (handled by caller)</li>
 * </ol>
 * <p>
 * <b>Key design</b> (§5.5 / C.5):
 * <ul>
 *   <li>Diversity P10 is applied once here (fixing v1's external clamp state machine issue)</li>
 *   <li>Pressure level affects allocation ratio (v1's ResourcePressure finally participates in decisions)</li>
 *   <li>Read path is fully lock-free (only reads EnvSnapshot)</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class TokenBudgetPlanner {

    private final ContextIntelligenceProperties props;

    public TokenBudgetPlanner(ContextIntelligenceProperties props) {
        this.props = props;
    }

    /**
     * Plan the token budget.
     *
     * @param snapshot       environment snapshot (may be EMPTY)
     * @param systemTokens   token count of the system message
     * @param toolsTokens    token count of tool declarations
     * @param fallbackWindow yml/hardcoded fallback window (used when snapshot is invalid)
     * @return TokenBudget (includes trace for monitoring)
     */
    public TokenBudget plan(EnvSnapshot snapshot, int systemTokens, int toolsTokens, int fallbackWindow) {
        // Step 1: determine the base window
        int baseWindow = calcBaseWindow(snapshot);
        if (baseWindow <= 0) {
            return buildLegacyBudget(fallbackWindow, systemTokens, toolsTokens, "fallback_yml");
        }

        // Step 2: apply the diversity ceiling (P10 clamp)
        int safeWindowApplied = 0;
        int ceiledWindow = baseWindow;
        if (snapshot.diversityDetected() && snapshot.safeWindow() > 0 && snapshot.safeWindow() < baseWindow) {
            safeWindowApplied = snapshot.safeWindow();
            ceiledWindow = snapshot.safeWindow();
        }

        // Step 3: adjust the allocation ratio based on the pressure factor
        double historyRatio = adjustPressureRatio(snapshot.pressure());

        // Step 4: compute the budget for each part
        ContextIntelligenceProperties.Budget budgetCfg = props.getBudget();
        int outputReserve = (int) (ceiledWindow * budgetCfg.getOutputReserveRatio());
        int available = ceiledWindow - systemTokens - toolsTokens - outputReserve;
        if (available <= 0) {
            return buildLegacyBudget(fallbackWindow, systemTokens, toolsTokens, "fallback_hardcode");
        }

        int historyBudget = (int) (available * historyRatio);
        int injectionBudget = available - historyBudget;
        int compactTrigger = historyBudget;
        int keepTail = (int) (historyBudget * budgetCfg.getKeepTailRatio());

        String reason = safeWindowApplied > 0 ? "diversity_clamp" : "normal";
        TokenBudgetTrace trace = new TokenBudgetTrace(
                baseWindow, ceiledWindow, safeWindowApplied,
                snapshot.pressure(), historyRatio,
                systemTokens, toolsTokens, outputReserve,
                false,  // legacyFallback
                null, 0, 0,  // phase/confidence fields not populated yet (requires extra Registry lookup)
                reason
        );
        return new TokenBudget(historyBudget, injectionBudget, compactTrigger, keepTail, trace);
    }

    // ==================== private methods (C.5 normalization)====================

    private int calcBaseWindow(EnvSnapshot snapshot) {
        if (snapshot == null || !snapshot.isAvailable()) {
            return 0;
        }
        return Math.max(0, snapshot.effectiveWindow());
    }

    private double adjustPressureRatio(ResourcePressure pressure) {
        ContextIntelligenceProperties.Budget budgetCfg = props.getBudget();
        return switch (pressure) {
            case NORMAL    -> budgetCfg.getNormalHistoryRatio();     // default 0.60
            case ELEVATED  -> budgetCfg.getElevatedHistoryRatio();   // default 0.70
            case HIGH      -> budgetCfg.getHighHistoryRatio();       // default 0.80
            case CRITICAL  -> budgetCfg.getCriticalHistoryRatio();   // default 0.85
        };
    }

    private TokenBudget buildLegacyBudget(int fallbackWindow, int systemTokens, int toolsTokens, String reason) {
        TokenBudget legacy = TokenBudget.legacy(fallbackWindow);
        TokenBudgetTrace trace = new TokenBudgetTrace(
                0, 0, 0,
                ResourcePressure.NORMAL, 0.60,
                systemTokens, toolsTokens, 0,
                true,  // legacyFallback
                null, 0, 0,
                reason
        );
        return new TokenBudget(
                legacy.historyBudget(),
                legacy.injectionBudget(),
                legacy.compactTriggerTokens(),
                legacy.keepTailTokens(),
                trace
        );
    }
}
