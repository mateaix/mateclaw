package vip.mate.agent.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.config.PrefixBudgetProperties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PrefixBudgetPlanner} — profile selection, share
 * allocation, and the disabled/rollback path.
 */
class PrefixBudgetPlannerTest {

    private PrefixBudgetProperties properties;
    private ConversationWindowProperties windowProperties;
    private PrefixBudgetPlanner planner;

    @BeforeEach
    void setUp() {
        properties = new PrefixBudgetProperties();
        windowProperties = new ConversationWindowProperties();
        planner = new PrefixBudgetPlanner(properties, windowProperties);
    }

    @Test
    @DisplayName("large window → NORMAL profile, budget = max*ratio − base − tools")
    void normalProfile() {
        PrefixBudgetPlan plan = planner.plan(128000, 2000, 3000);
        assertEquals(PrefixBudgetPlan.Profile.NORMAL, plan.profile());
        assertEquals(128000, plan.effectiveMaxTokens());
        assertEquals((int) (128000 * 0.35) - 2000 - 3000, plan.injectionBudgetTokens());
        assertTrue(plan.enabled());
    }

    @Test
    @DisplayName("16k window → COMPACT profile with tightened ratio")
    void compactProfile() {
        PrefixBudgetPlan plan = planner.plan(16384, 1000, 1000);
        assertEquals(PrefixBudgetPlan.Profile.COMPACT, plan.profile());
        assertEquals((int) (16384 * 0.25) - 2000, plan.injectionBudgetTokens());
    }

    @Test
    @DisplayName("4k window → MINIMAL profile, wiki injection disabled outright")
    void minimalProfile() {
        PrefixBudgetPlan plan = planner.plan(4096, 500, 500);
        assertEquals(PrefixBudgetPlan.Profile.MINIMAL, plan.profile());
        assertEquals(Math.max(0, (int) (4096 * 0.15) - 1000), plan.injectionBudgetTokens());
        assertEquals(0, plan.wikiTokens());
    }

    @Test
    @DisplayName("COMPACT profile caps the wiki injection at roughly one page")
    void compactCapsWiki() {
        properties.getShares().setWiki(1.0);
        properties.getShares().setMemory(0.0);
        properties.getShares().setSkill(0.0);
        properties.getShares().setExtensionCatalog(0.0);
        properties.getShares().setLedger(0.0);
        PrefixBudgetPlan plan = planner.plan(20000, 0, 0);
        assertEquals(PrefixBudgetPlan.Profile.COMPACT, plan.profile());
        assertTrue(plan.injectionBudgetTokens() > PrefixBudgetPlanner.COMPACT_WIKI_TOKEN_CAP);
        assertEquals(PrefixBudgetPlanner.COMPACT_WIKI_TOKEN_CAP, plan.wikiTokens());
    }

    @Test
    @DisplayName("plan carries an independent tool-schema budget from toolSchemaRatio")
    void toolSchemaBudget() {
        PrefixBudgetPlan plan = planner.plan(16384, 0, 0);
        assertEquals((int) (16384 * 0.25), plan.toolSchemaBudgetTokens());
        properties.setEnabled(false);
        assertEquals(Integer.MAX_VALUE, planner.plan(16384, 0, 0).toolSchemaBudgetTokens());
    }

    @Test
    @DisplayName("oversized base prompt + tools clamp the budget to zero, never negative")
    void budgetNeverNegative() {
        PrefixBudgetPlan plan = planner.plan(8192, 9000, 5000);
        assertEquals(0, plan.injectionBudgetTokens());
        assertEquals(0, plan.memoryTokens());
        assertEquals(0, plan.wikiTokens());
    }

    @Test
    @DisplayName("shares split the injection budget and sum to at most the budget")
    void sharesSplitBudget() {
        PrefixBudgetPlan plan = planner.plan(128000, 0, 0);
        int budget = plan.injectionBudgetTokens();
        // ±1 token tolerance: share division is floating point.
        assertEquals(budget * 0.35, plan.memoryTokens(), 1.0);
        assertEquals(budget * 0.30, plan.wikiTokens(), 1.0);
        assertEquals(budget * 0.20, plan.skillCatalogTokens(), 1.0);
        assertEquals(budget * 0.10, plan.extensionCatalogTokens(), 1.0);
        assertEquals(budget * 0.05, plan.ledgerTokens(), 1.0);
        int sum = plan.memoryTokens() + plan.wikiTokens() + plan.skillCatalogTokens()
                + plan.extensionCatalogTokens() + plan.ledgerTokens();
        assertTrue(sum <= budget);
    }

    @Test
    @DisplayName("shares not summing to 1 are normalized")
    void sharesNormalized() {
        properties.getShares().setMemory(2.0);
        properties.getShares().setWiki(2.0);
        properties.getShares().setSkill(0.0);
        properties.getShares().setExtensionCatalog(0.0);
        properties.getShares().setLedger(0.0);
        PrefixBudgetPlan plan = planner.plan(128000, 0, 0);
        assertEquals(plan.injectionBudgetTokens() / 2, plan.memoryTokens());
        assertEquals(plan.injectionBudgetTokens() / 2, plan.wikiTokens());
        assertEquals(0, plan.skillCatalogTokens());
    }

    @Test
    @DisplayName("disabled → unlimited plan, previous behavior")
    void disabledYieldsUnlimited() {
        properties.setEnabled(false);
        PrefixBudgetPlan plan = planner.plan(8192, 100, 100);
        assertFalse(plan.enabled());
        assertEquals(Integer.MAX_VALUE, plan.memoryTokens());
        assertEquals(Integer.MAX_VALUE, plan.wikiTokens());
        assertEquals(8192, plan.effectiveMaxTokens());
    }

    @Test
    @DisplayName("null/zero effective window falls back to the global default")
    void nullWindowFallsBackToGlobalDefault() {
        PrefixBudgetPlan plan = planner.plan(null, 0, 0);
        assertEquals(windowProperties.getDefaultMaxInputTokens(), plan.effectiveMaxTokens());
        assertEquals(PrefixBudgetPlan.Profile.NORMAL, plan.profile());
    }

    @Test
    @DisplayName("small windows compact later (0.85), large windows keep the default ratio")
    void compactTriggerRatioAdapts() {
        assertEquals(0.75, planner.compactTriggerRatioFor(128000, 0.75));
        assertEquals(0.85, planner.compactTriggerRatioFor(16384, 0.75));
        assertEquals(0.85, planner.compactTriggerRatioFor(4096, 0.75));
        properties.setEnabled(false);
        assertEquals(0.75, planner.compactTriggerRatioFor(4096, 0.75));
    }
}
