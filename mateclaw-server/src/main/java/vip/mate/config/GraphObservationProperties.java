package vip.mate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Graph observation thresholds for triggering summarize / compaction.
 * <p>
 * <b>Tune values in {@code application.yml} under {@code mate.agent.graph.observation},
 * not in the Java field defaults.</b> The yml is the source of truth and is loaded into
 * a Spring bean by {@link ConfigurationProperties}; field defaults below are kept as a
 * conservative fallback for tests / unit constructors and intentionally do NOT reflect
 * production-tuned values.
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.agent.graph.observation")
public class GraphObservationProperties {

    /** Max chars retained per tool result (truncate above this). Tune via yml. */
    private int maxSingleObservationChars = 4000;

    /** Total observation chars across history+current that trigger summarize. Tune via yml. */
    private int maxTotalObservationChars = 12000;

    /** Single tool result above this is treated as "large" and triggers summarize. Tune via yml. */
    private int largeResultThreshold = 3000;

    /** Minimum observation rounds that triggers summarize as a runaway-loop safety net. Tune via yml. */
    private int minRoundsForSummarize = 3;

    /** Truncation: head fraction kept (0-1). */
    private double headRatio = 0.4;

    /** Truncation marker; %d is replaced with original char count. */
    private String truncationMarker = "\n\n...[TRUNCATED: %d chars total, middle omitted. Do NOT infer or "
            + "fabricate omitted content; retrieve the full data (e.g. read_file) or tell the user "
            + "the result is incomplete.]...\n\n";

    /** Tail-keep ratio when an error pattern is detected at the tail (prefer keeping error info). */
    private double errorTailRatio = 0.8;

    /** Minimum chars retained on truncation to avoid losing all information. */
    private int minKeepChars = 2000;
}
