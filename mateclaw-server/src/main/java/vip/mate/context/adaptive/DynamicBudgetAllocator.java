package vip.mate.context.adaptive;

import lombok.extern.slf4j.Slf4j;

/**
 * Dynamic token budget allocator — the allocation layer core.
 *
 * <p>Distributes the effective context window (from the probe layer) across
 * the components of an LLM prompt in priority order:
 *
 * <ol>
 *   <li><b>outputReserve</b> (15%) — the LLM must have room to generate a response</li>
 *   <li><b>systemPrompt</b> (fixed) — non-compressible</li>
 *   <li><b>toolDefinitions</b> (fixed) — API-layer, not text-compressible</li>
 *   <li><b>currentUserMessage</b> (fixed) — non-compressible</li>
 *   <li><b>conversationHistory</b> (dynamic) — the only significantly compressible component</li>
 *   <li><b>runtimeInjections</b> (skill catalog, wiki, progress ledger) — secondary compressible</li>
 * </ol>
 *
 * <p>The allocator does NOT trigger compression itself; it provides token budgets
 * that the existing compression layers ({@code ConversationWindowManager},
 * {@code LoopMessageBudgeter}) use as their thresholds.
 *
 * @author MateClaw Team
 */
@Slf4j
public class DynamicBudgetAllocator {

    /** Output reserve: fraction of the effective window reserved for LLM generation */
    public static final double OUTPUT_RESERVE_RATIO = 0.15;

    /** Conversation history gets 60% of remaining space after fixed costs */
    public static final double HISTORY_RATIO = 0.60;

    /** Runtime injections get 40% of remaining space */
    public static final double INJECTIONS_RATIO = 0.40;

    /** Minimum compaction trigger token count */
    public static final int MIN_COMPACT_TRIGGER = 4_000;

    /** Minimum history budget (even under extreme pressure) */
    public static final int MIN_HISTORY_BUDGET = 2_000;

    /** Minimum injection budget */
    public static final int MIN_INJECTION_BUDGET = 1_000;

    /** Compaction trigger: compress when history exceeds this fraction of budget */
    public static final double COMPACT_TRIGGER_RATIO = 0.75;

    /** Tail retention: keep this fraction of history after compaction */
    public static final double TAIL_KEEP_RATIO = 0.30;

    /**
     * Budget allocation result. All fields are token counts.
     */
    public record Allocation(
            int effectiveWindow,
            int outputReserve,
            int systemTokens,
            int toolsTokens,
            int currentMsgTokens,
            int historyBudget,
            int injectionsBudget,
            int compactTriggerTokens,
            int keepTailTokens
    ) {
        /** Fallback allocation when no tracker is available (pure yml / provider default). */
        public static Allocation legacy(int contextWindow) {
            int effective = Math.max(contextWindow, MIN_COMPACT_TRIGGER * 2);
            int output = (int)(effective * OUTPUT_RESERVE_RATIO);
            int compactTrigger = (int)(effective * COMPACT_TRIGGER_RATIO);
            int tail = (int)(effective * TAIL_KEEP_RATIO);
            return new Allocation(effective, output, 0, 0, 0,
                    effective - output, 0, compactTrigger, tail);
        }
    }

    /**
     * Allocate token budgets for a single LLM call.
     *
     * @param monitor          context pressure monitor (nullable — falls back to legacy)
     * @param provider         provider ID for tracker lookup
     * @param modelName        model name for tracker lookup
     * @param systemTokens     token count of the system prompt
     * @param toolsTokens      token count of tool definitions
     * @param currentMsgTokens token count of the current user message
     * @param contextWindow    fallback context window from yml / provider config
     * @return budget allocation
     */
    public Allocation allocate(ContextPressureMonitor monitor,
                                String provider, String modelName,
                                int systemTokens, int toolsTokens,
                                int currentMsgTokens, int contextWindow) {
        // Resolve the effective window: tracker dynamic value > yml fallback
        int effectiveWindow = contextWindow;
        boolean fromTracker = false;

        if (monitor != null) {
            int tracked = monitor.getEffectiveWindow(provider, modelName);
            if (tracked > 0) {
                effectiveWindow = tracked;
                fromTracker = true;
            }
        }

        // Fixed costs
        int outputReserve = (int)(effectiveWindow * OUTPUT_RESERVE_RATIO);
        int fixed = systemTokens + toolsTokens + currentMsgTokens;
        int remaining = effectiveWindow - outputReserve - fixed;

        // Safety: when fixed costs consume >85% of the window, the
        // tool schemas are too large — warn and trigger tool prioritization
        if (remaining < MIN_HISTORY_BUDGET + MIN_INJECTION_BUDGET) {
            log.warn("[BudgetAllocator] Tight budget: window={}, fixed={}, remaining={}. "
                    + "Tool prioritization may be needed.", effectiveWindow, fixed, remaining);
        }

        // Distribute remaining space
        int compressions = Math.max(MIN_HISTORY_BUDGET, remaining);
        int historyBudget = Math.max(MIN_HISTORY_BUDGET, (int)(compressions * HISTORY_RATIO));
        int injectionsBudget = Math.max(MIN_INJECTION_BUDGET, compressions - historyBudget);

        // Compaction thresholds derived from history budget
        int compactTrigger = (int)(historyBudget * COMPACT_TRIGGER_RATIO);
        int keepTail = (int)(historyBudget * TAIL_KEEP_RATIO);

        if (fromTracker) {
            log.debug("[BudgetAllocator] Dynamic allocation: window={}, historyB={}, injB={}, "
                    + "trigger={}, tail={}", effectiveWindow, historyBudget, injectionsBudget,
                    compactTrigger, keepTail);
        }

        return new Allocation(effectiveWindow, outputReserve, systemTokens, toolsTokens,
                currentMsgTokens, historyBudget, injectionsBudget, compactTrigger, keepTail);
    }
}
