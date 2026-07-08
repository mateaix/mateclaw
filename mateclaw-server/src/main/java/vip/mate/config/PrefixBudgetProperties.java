package vip.mate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget configuration for the prompt prefix's optional injection blocks
 * (memory, wiki relevance, skill catalog, extension-tool catalog, progress
 * ledger).
 *
 * <p>Each block already has its own absolute cap (chars), but those caps are
 * mutually blind and sized for large cloud models — stacked together they
 * easily exceed a local 8k/16k window on the very first request, when there
 * is no history to compact. This budget scales every block against the
 * model's effective context window instead: the enforced limit is always
 * {@code min(block's own cap, its share of the injection budget)}, so large
 * windows behave exactly as before while small windows shrink each block
 * proportionally.
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.context.prefix-budget")
public class PrefixBudgetProperties {

    /** Kill switch — false restores the previous per-block-absolute-cap behavior. */
    private boolean enabled = true;

    /** Fraction of the effective window granted to prefix injection blocks (normal profile). */
    private double injectionRatio = 0.35;

    /** Injection ratio when the window is below {@link #compactThresholdTokens}. */
    private double compactInjectionRatio = 0.25;

    /** Injection ratio when the window is below {@link #minimalThresholdTokens}. */
    private double minimalInjectionRatio = 0.15;

    /** Windows below this enter the compact profile. */
    private int compactThresholdTokens = 32768;

    /** Windows below this enter the minimal profile. */
    private int minimalThresholdTokens = 8192;

    /**
     * Compaction trigger ratio used for compact / minimal profiles instead of
     * the global {@code compactTriggerRatio}. Small windows should be used up
     * before summarizing — compacting at 75% of an 8k window wastes what
     * little room there is.
     */
    private double compactTriggerRatioOverride = 0.85;

    /**
     * Fraction of the effective window the advertised tool schemas may
     * occupy. When the core tool set estimates above this, the least
     * recently used demotable tools are auto-moved to the extension catalog
     * (recoverable via {@code enable_tool}) until the set fits.
     */
    private double toolSchemaRatio = 0.25;

    /** Relative shares of the injection budget. Normalized at plan time. */
    private Shares shares = new Shares();

    @Data
    public static class Shares {
        private double memory = 0.35;
        private double wiki = 0.30;
        private double skill = 0.20;
        private double extensionCatalog = 0.10;
        private double ledger = 0.05;
    }
}
