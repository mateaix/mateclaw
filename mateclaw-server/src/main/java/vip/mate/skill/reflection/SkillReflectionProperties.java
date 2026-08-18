package vip.mate.skill.reflection;

import lombok.Data;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the out-of-band skill reflection service — the post-turn
 * review that autonomously creates or improves skills from a finished
 * conversation, without consuming the live turn's context.
 *
 * @author MateClaw Team
 */
@Data
@Validated
@ConfigurationProperties(prefix = "mateclaw.skill.reflection")
public class SkillReflectionProperties {

    /** Master switch. When {@code false} no post-turn skill review runs. */
    private boolean enabled = false;

    /**
     * Explicit opt-in for applying reviewer output. When false the reviewer
     * may be exercised in tests/preview flows but cannot mutate the registry.
     */
    private boolean autoApply = false;

    /**
     * Review cadence: trigger a review every N conversation messages. The
     * cooldown still applies on top, so a busy conversation reviews at most
     * once per {@link #cooldownMinutes}. {@code 0} disables the cadence gate.
     */
    @Min(0)
    @Max(10_000)
    private int reviewTurnInterval = 8;

    /**
     * Minimum number of assistant turns in the reviewed window before a review
     * is worth running — a one-shot exchange rarely contains a reusable
     * workflow. (Tool calls are not persisted as separate messages, so turn
     * count, not tool count, is the signal we can actually observe.)
     */
    @Min(0)
    @Max(1_000)
    private int minAssistantTurns = 2;

    /** Most recent messages fed to the reviewer. */
    @Min(1)
    @Max(1_000)
    private int maxMessages = 24;

    /** Per-conversation cooldown between reviews, in minutes. */
    @Min(0)
    @Max(43_200)
    private int cooldownMinutes = 30;

    /** Hard cap on create/edit/patch actions applied in a single review. */
    @Min(1)
    @Max(20)
    private int maxActionsPerRun = 3;

    /** Character budget for the existing-skills catalog handed to the reviewer. */
    @Min(1_000)
    @Max(1_000_000)
    private int catalogCharBudget = 8000;

    /** Review model ID ({@code null} = follow the system default model). */
    private String modelId;
}
