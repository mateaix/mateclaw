package vip.mate.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Periodically logs HikariCP pool metrics so operators can detect
 * connection exhaustion / leak patterns before the application stalls.
 * <p>
 * Runs every 30s — frequent enough to catch a pool drain within 1-2
 * ticks, cheap enough to never become a problem itself.
 */
@Slf4j
@Component
public class HikariPoolMonitor {

    private final HikariDataSource hikari;

    public HikariPoolMonitor(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hds) {
            this.hikari = hds;
        } else if (dataSource instanceof org.springframework.jdbc.datasource.DelegatingDataSource dds
                && dds.getTargetDataSource() instanceof HikariDataSource hds) {
            // Some auto-configurations wrap Hikari in a delegating DS.
            this.hikari = hds;
        } else {
            this.hikari = null;
            log.info("[HikariMonitor] DataSource is not HikariCP — pool monitor disabled");
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void logPoolStats() {
        if (hikari == null) return;

        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool == null) return;

        int active = pool.getActiveConnections();
        int idle = pool.getIdleConnections();
        int total = pool.getTotalConnections();
        int waiting = pool.getThreadsAwaitingConnection();

        // Normal — log at debug so it doesn't spam the console.
        log.debug("[HikariMonitor] pool: active={}, idle={}, total={}, max={}, waiting={}",
                active, idle, total, hikari.getMaximumPoolSize(), waiting);

        // Warning threshold: more than 80 % of the pool is active AND
        // threads are queued waiting for a connection.
        int maxPool = hikari.getMaximumPoolSize();
        if (active > maxPool * 0.8 && waiting > 0) {
            log.warn("[HikariMonitor] Pool pressure detected — active={}, idle={}, "
                    + "total={}, max={}, waiting={}", active, idle, total, maxPool, waiting);
        }

        // Critical: pool is fully saturated AND threads are waiting.
        if (active >= maxPool && waiting > 0) {
            log.error("[HikariMonitor] Pool EXHAUSTED — active={}, max={}, waiting={}. "
                    + "Application will appear frozen until connections are released.",
                    active, maxPool, waiting);
        }
    }
}
