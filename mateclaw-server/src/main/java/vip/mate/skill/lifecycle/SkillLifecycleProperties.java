package vip.mate.skill.lifecycle;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the skill lifecycle curator — the daily job that moves
 * idle, agent-created skills through {@code active -> stale -> archived}.
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.skill.curator")
public class SkillLifecycleProperties {

    /** Master switch. When {@code false} the daily sweep never runs. */
    private boolean enabled = true;

    /** Cron expression for the daily sweep. Defaults to 02:00 every day. */
    private String cron = "0 0 2 * * *";

    /** Days of inactivity after which an active skill becomes {@code stale}. */
    private int staleAfterDays = 30;

    /** Days of inactivity after which a stale skill becomes {@code archived}. */
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
}
