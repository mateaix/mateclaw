package vip.mate.skill.lifecycle;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Published after every lifecycle sweep completes. Carries the applied
 * counts (zero for a dry-run) so a downstream notification listener can
 * surface the run without re-reading the report file.
 *
 * <p>This event has no compile-time dependency on any notification
 * subsystem: if nothing listens, it is simply a no-op.
 *
 * @author MateClaw Team
 */
public record SkillCuratorRunCompletedEvent(
        String runId,
        int markedStale,
        int archived,
        int reactivated,
        boolean dryRun,
        Path reportPath,
        LocalDateTime runAt) {
}
