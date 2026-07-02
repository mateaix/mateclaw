package vip.mate.skill.reflection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the out-of-band skill reflection service — the post-turn
 * review that autonomously creates or improves skills from a finished
 * conversation, without consuming the live turn's context.
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.skill.reflection")
public class SkillReflectionProperties {

    /** Master switch. When {@code false} no post-turn skill review runs. */
    private boolean enabled = true;

    /**
     * Review cadence: trigger a review every N conversation messages. The
     * cooldown still applies on top, so a busy conversation reviews at most
     * once per {@link #cooldownMinutes}. {@code 0} disables the cadence gate.
     */
    private int reviewTurnInterval = 8;

    /**
     * Minimum number of assistant turns in the reviewed window before a review
     * is worth running — a one-shot exchange rarely contains a reusable
     * workflow. (Tool calls are not persisted as separate messages, so turn
     * count, not tool count, is the signal we can actually observe.)
     */
    private int minAssistantTurns = 2;

    /** Most recent messages fed to the reviewer. */
    private int maxMessages = 24;

    /** Per-conversation cooldown between reviews, in minutes. */
    private int cooldownMinutes = 30;

    /** Hard cap on create/edit/patch actions applied in a single review. */
    private int maxActionsPerRun = 3;

    /** Character budget for the existing-skills catalog handed to the reviewer. */
    private int catalogCharBudget = 8000;

    /** Review model ID ({@code null} = follow the system default model). */
    private String modelId;
}
