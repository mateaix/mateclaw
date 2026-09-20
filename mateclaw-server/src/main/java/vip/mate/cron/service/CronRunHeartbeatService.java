package vip.mate.cron.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Maintains a durable liveness signal while a cron run is inside a long agent call. */
@Slf4j
@Service
public class CronRunHeartbeatService {

    static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

    private final CronJobRunMapper runMapper;
    private final ScheduledExecutorService scheduler;
    private final Duration interval;
    private final Clock clock;
    private final boolean ownsScheduler;

    @Autowired
    public CronRunHeartbeatService(CronJobRunMapper runMapper) {
        this(runMapper, newScheduler(), DEFAULT_INTERVAL, Clock.systemDefaultZone(), true);
    }

    CronRunHeartbeatService(CronJobRunMapper runMapper,
                            ScheduledExecutorService scheduler,
                            Duration interval,
                            Clock clock,
                            boolean ownsScheduler) {
        this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownsScheduler = ownsScheduler;
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("heartbeat interval must be positive");
        }
    }

    /**
     * Start refreshing one run. The returned lease is idempotent and must be
     * closed when the long call exits, including exceptional exits.
     */
    public Lease begin(Long runId) {
        Objects.requireNonNull(runId, "runId");
        long periodMillis = interval.toMillis();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> safeTouch(runId), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false);
            }
        };
    }

    private void safeTouch(Long runId) {
        try {
            int updated = runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                    .eq(CronJobRunEntity::getId, runId)
                    .eq(CronJobRunEntity::getStatus, "running")
                    .set(CronJobRunEntity::getHeartbeatAt, LocalDateTime.now(clock)));
            if (updated == 0) {
                log.debug("[CronHeartbeat] Run {} is no longer running; heartbeat ignored", runId);
            }
        } catch (RuntimeException e) {
            // ScheduledExecutorService suppresses all later ticks if a task
            // escapes with an exception. Keep the liveness loop recoverable.
            log.warn("[CronHeartbeat] Failed to refresh run {}: {}", runId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cron-run-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
