package vip.mate.cron.config;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * RFC-03 Lane G2 — distributed lock provider for the cron scheduler.
 *
 * <p>{@link CronJobService} runs in every node of a multi-instance
 * deployment; without coordination, every node fires every {@code CronTrigger}
 * tick, multiplying invocations and downstream side effects (channel
 * messages, LLM calls, approval rows). ShedLock's JDBC mode reuses the
 * existing application DataSource so we don't pull in Redis just for this
 * one purpose.
 *
 * <p>Schema lives in {@code db/migration/{h2,mysql}/V74__shedlock_table.sql}.
 *
 * <p>Default {@code lockAtMostFor=PT30M} on the {@link EnableSchedulerLock}
 * annotation is the safety net for a node that dies mid-execution — after
 * 30 min any other node can take the lock. Per-call {@code @SchedulerLock}
 * annotations may shorten this for predictable workloads.
 *
 * <p>Single-node deployments (desktop / single docker container) are
 * unaffected: the lock is acquired on the same node trivially.
 */
@Slf4j
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        log.info("[ShedLock] Initializing JDBC LockProvider for cron scheduling");
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        // usingDbTime() removed — KingbaseES not in ShedLock's
                        // built-in dialect map; app-server time is sufficient
                        // given lockAtMostFor=PT30M
                        .build()
        );
    }
}
