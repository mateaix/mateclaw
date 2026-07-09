package vip.mate.agent.context;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.config.PrefixBudgetProperties;

/**
 * Computes the {@link PrefixBudgetPlan} for one agent build: how many tokens
 * each optional prefix injection block (memory / wiki / skill catalog /
 * extension catalog / progress ledger) may spend, scaled to the model's
 * effective context window.
 *
 * <pre>
 * injectionBudget = max(0, effectiveMax × ratio(profile)
 *                          − basePromptTokens − toolSchemaTokens)
 * block budget    = injectionBudget × normalizedShare(block)
 * </pre>
 *
 * The agent's own prompt and the tool schemas are never truncated here —
 * they are subtracted from the injection budget so the optional blocks
 * absorb the squeeze. On large windows the shares far exceed each block's
 * absolute cap, so behavior is byte-identical to the pre-budget code.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(PrefixBudgetProperties.class)
public class PrefixBudgetPlanner {

    /** COMPACT-profile ceiling for the wiki relevance injection (~one page). */
    static final int COMPACT_WIKI_TOKEN_CAP = 2000;

    private final PrefixBudgetProperties properties;
    private final ConversationWindowProperties windowProperties;

    /**
     * @param effectiveMaxInputTokens the model's effective window (explicit
     *                                config or probed); null/0 falls back to
     *                                the global default
     * @param basePromptTokens        estimated tokens of the agent's own
     *                                identity prompt (before memory/guidance)
     * @param toolSchemaTokens        estimated tokens of the advertised tool
     *                                schemas
     */
    public PrefixBudgetPlan plan(Integer effectiveMaxInputTokens, int basePromptTokens, int toolSchemaTokens) {
        int effectiveMax = (effectiveMaxInputTokens != null && effectiveMaxInputTokens > 0)
                ? effectiveMaxInputTokens : windowProperties.getDefaultMaxInputTokens();
        if (!properties.isEnabled()) {
            return PrefixBudgetPlan.unlimited(effectiveMax);
        }

        PrefixBudgetPlan.Profile profile = profileFor(effectiveMax);
        double ratio = switch (profile) {
            case NORMAL -> properties.getInjectionRatio();
            case COMPACT -> properties.getCompactInjectionRatio();
            case MINIMAL -> properties.getMinimalInjectionRatio();
        };

        int injectionBudget = Math.max(0,
                (int) (effectiveMax * ratio) - Math.max(0, basePromptTokens) - Math.max(0, toolSchemaTokens));

        PrefixBudgetProperties.Shares shares = properties.getShares();
        double sum = shares.getMemory() + shares.getWiki() + shares.getSkill()
                + shares.getExtensionCatalog() + shares.getLedger();
        if (sum <= 0) {
            sum = 1.0;
        }

        // Profile-specific wiki clamps: knowledge-base reference pages are the
        // most dispensable block on a small window — the wiki tools stay
        // callable, only the automatic pre-injection shrinks. COMPACT caps it
        // at roughly one page; MINIMAL disables it outright.
        int wikiTokens = (int) (injectionBudget * shares.getWiki() / sum);
        wikiTokens = switch (profile) {
            case NORMAL -> wikiTokens;
            case COMPACT -> Math.min(wikiTokens, COMPACT_WIKI_TOKEN_CAP);
            case MINIMAL -> 0;
        };

        PrefixBudgetPlan plan = new PrefixBudgetPlan(
                true, effectiveMax, profile, injectionBudget,
                (int) (injectionBudget * shares.getMemory() / sum),
                wikiTokens,
                (int) (injectionBudget * shares.getSkill() / sum),
                (int) (injectionBudget * shares.getExtensionCatalog() / sum),
                (int) (injectionBudget * shares.getLedger() / sum),
                (int) (effectiveMax * properties.getToolSchemaRatio()));

        if (profile != PrefixBudgetPlan.Profile.NORMAL) {
            log.info("[PrefixBudget] 窗口 {} tokens 进入 {} 档:注入预算 {} tokens"
                            + "(memory={}, wiki={}, skill={}, extCatalog={}, ledger={})",
                    effectiveMax, profile, injectionBudget,
                    plan.memoryTokens(), plan.wikiTokens(), plan.skillCatalogTokens(),
                    plan.extensionCatalogTokens(), plan.ledgerTokens());
        }
        return plan;
    }

    /** Compaction trigger ratio for this window size (small windows fill up before compacting). */
    public double compactTriggerRatioFor(int effectiveMaxTokens, double defaultRatio) {
        if (!properties.isEnabled()) {
            return defaultRatio;
        }
        return profileFor(effectiveMaxTokens) == PrefixBudgetPlan.Profile.NORMAL
                ? defaultRatio : properties.getCompactTriggerRatioOverride();
    }

    private PrefixBudgetPlan.Profile profileFor(int effectiveMax) {
        if (effectiveMax < properties.getMinimalThresholdTokens()) {
            return PrefixBudgetPlan.Profile.MINIMAL;
        }
        if (effectiveMax < properties.getCompactThresholdTokens()) {
            return PrefixBudgetPlan.Profile.COMPACT;
        }
        return PrefixBudgetPlan.Profile.NORMAL;
    }
}
