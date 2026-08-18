package vip.mate.skill.lifecycle;

import lombok.Data;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the skill lifecycle curator — the daily job that moves
 * idle, agent-created skills through {@code active -> stale -> archived}.
 *
 * @author MateClaw Team
 */
@Data
@Validated
@ConfigurationProperties(prefix = "mateclaw.skill.curator")
public class SkillLifecycleProperties {

    /** Master switch. When {@code false} the daily sweep never runs. */
    private boolean enabled = true;

    /** Cron expression for the daily sweep. Defaults to 02:00 every day. */
    private String cron = "0 0 2 * * *";

    /** Days of inactivity after which an active skill becomes {@code stale}. */
    @Min(1)
    @Max(36_500)
    private int staleAfterDays = 30;

    /** Days of inactivity after which a stale skill becomes {@code archived}. */
    @Min(1)
    @Max(36_500)
    private int archiveAfterDays = 90;

    /**
     * Which skills the curator considers:
     * <ul>
     *   <li>{@code AGENT_CREATED} — only skills with a source conversation
     *       (created by an agent); the most conservative default.</li>
     *   <li>{@code ALL_DYNAMIC} — also includes manually-created dynamic
     *       skills.</li>
     *   <li>{@code OFF} — disables the sweep regardless of {@link #enabled}.</li>
     * </ul>
     */
    private String scope = "AGENT_CREATED";

    /** Skills whose name starts with any of these prefixes are never touched. */
    private List<String> protectPrefixes = new ArrayList<>(List.of("sys-", "ops-"));

    /**
     * Whether the daily sweep also runs a consolidation pass that merges
     * near-duplicate agent-created skills into broader umbrella skills.
     * Off by default — it spends an LLM call and rewrites skills, so opt-in.
     */
    private boolean consolidate = false;

    /** Minimum candidate skills present before a consolidation pass runs. */
    @Min(2)
    @Max(10_000)
    private int consolidateMinSkills = 4;

    /** Hard cap on merge groups applied in a single consolidation pass. */
    @Min(1)
    @Max(100)
    private int consolidateMaxGroupsPerRun = 2;

    /** Character budget for the catalog handed to the consolidation reviewer. */
    @Min(1_000)
    @Max(2_000_000)
    private int consolidateCatalogCharBudget = 12000;

    /** Consolidation model ID ({@code null} = follow the system default model). */
    private String consolidateModelId;

    /**
     * Whether a restore point is captured before each mutating sweep. Gates
     * both the automatic pre-sweep capture and the manual one, so there is no
     * configuration in which a mutating run silently skips its snapshot.
     */
    private boolean backupEnabled = true;

    /** Restore points retained; older ones are pruned after each capture. */
    private int backupKeep = 5;
}
