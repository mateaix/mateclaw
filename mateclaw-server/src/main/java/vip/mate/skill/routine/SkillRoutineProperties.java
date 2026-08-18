package vip.mate.skill.routine;

import lombok.Data;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for routine mining — the cross-session pass that detects
 * requests the user makes habitually and promotes them into skills.
 *
 * @author MateClaw Team
 */
@Data
@Validated
@ConfigurationProperties(prefix = "mateclaw.skill.routine")
public class SkillRoutineProperties {

    /** Master switch. When {@code false} neither mining nor promotion runs. */
    private boolean enabled = false;

    /** How far back the mining pass looks, in days. */
    @Min(1)
    @Max(3650)
    private int lookbackDays = 30;

    /**
     * Shingle-similarity threshold above which two conversation openers are
     * considered the same request. Tuned toward precision: a false merge
     * produces a skill describing a routine the user does not actually have,
     * which is worse than missing one and catching it on the next sweep.
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double similarityThreshold = 0.62;

    /**
     * Conversations a cluster needs before promotion. Two is coincidence.
     */
    @Min(2)
    @Max(10_000)
    private int minOccurrences = 3;

    /**
     * Distinct calendar days a cluster must span before promotion. Guards
     * against a single afternoon of retries reading as a daily habit.
     */
    @Min(2)
    @Max(3650)
    private int minDistinctDays = 3;

    /** Shortest opener worth clustering; below this the text carries no intent. */
    @Min(1)
    @Max(10_000)
    private int minOpenerChars = 8;

    /** Longest opener prefix fed to the shingler. */
    @Min(20)
    @Max(100_000)
    private int maxOpenerChars = 400;

    /** Conversation ids retained per candidate as promotion evidence. */
    @Min(1)
    @Max(100)
    private int maxSamplesPerCandidate = 8;

    /** Candidates promoted in a single sweep, bounding LLM cost per run. */
    @Min(1)
    @Max(100)
    private int maxPromotionsPerRun = 2;

    /** Conversations scanned per sweep, bounding memory and query cost. */
    @Min(1)
    @Max(100_000)
    private int maxConversationsPerRun = 1000;

    /** Messages of each sample conversation shown to the synthesizer. */
    @Min(2)
    @Max(1_000)
    private int transcriptMessagesPerSample = 12;

    /** Per-message truncation when building the synthesis transcript. */
    @Min(100)
    @Max(100_000)
    private int transcriptTruncateChars = 800;

    /** Synthesis model ID ({@code null} = follow the system default model). */
    private String modelId;
}
