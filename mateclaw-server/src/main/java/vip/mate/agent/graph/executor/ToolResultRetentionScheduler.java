package vip.mate.agent.graph.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link ToolResultStorage#cleanupExpired()} on a cron schedule so
 * spill files don't accumulate forever. Kept in its own class instead of
 * inlined into {@link ToolResultStorage} for two reasons:
 *
 * <ul>
 *   <li>Tests can exercise {@code cleanupExpired()} directly without
 *       fighting the Spring scheduler.</li>
 *   <li>Deployments that want to disable the schedule entirely can simply
 *       leave this component out of the autoconfigure path.</li>
 * </ul>
 *
 * <p>The cron expression comes from
 * {@link ToolResultProperties#getCleanupCron()} (default {@code 0 0 3 * * ?},
 * i.e. once a day at 03:00 server-local time). The retention horizon comes
 * from {@link ToolResultProperties#getRetentionDays()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResultRetentionScheduler {

    private final ToolResultStorage storage;
    private final ToolResultProperties props;

    /**
     * Cron-fired hook. Failures are logged at WARN so they show up in
     * standard log scrapes without aborting the scheduler thread — losing
     * a single sweep is fine, the next one will catch the same files.
     */
    @Scheduled(cron = "${mate.agent.tool-result.cleanup-cron:0 0 3 * * ?}")
    public void cleanup() {
        if (props.getRetentionDays() <= 0) {
            log.debug("[ToolResultRetentionScheduler] retentionDays<=0, skipping sweep");
            return;
        }
        try {
            int deleted = storage.cleanupExpired();
            if (deleted > 0) {
                log.info("[ToolResultRetentionScheduler] sweep deleted {} spill file(s) older than {} days",
                        deleted, props.getRetentionDays());
            }
        } catch (Exception e) {
            log.warn("[ToolResultRetentionScheduler] sweep failed: {}", e.getMessage(), e);
        }
    }
}
