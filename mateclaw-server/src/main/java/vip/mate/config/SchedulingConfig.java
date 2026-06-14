package vip.mate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Scheduled-task thread-pool configuration.
 * <p>
 * Spring Boot's auto-configured {@code TaskScheduler} defaults to
 * <b>pool-size = 1</b>, which serializes every {@code @Scheduled}
 * method across the entire application. With 15+ scheduled beans
 * (health checks, trigger sync, fact rebuild, feature-flag refresh,
 * etc.) and several firing on the same cron tick, a single-thread
 * pool causes back-pressure that makes the application appear "frozen"
 * when any task blocks briefly on database I/O or lock acquisition.
 * <p>
 * This config sets a pool large enough to absorb simultaneous
 * minute/half-hour boundaries without head-of-line blocking, while
 * keeping thread count low so the scheduler does not contend with
 * HikariCP or the async virtual-thread pool.
 * <p>
 * <b>IMPORTANT:</b> {@code @EnableScheduling} is declared once on
 * {@link vip.mate.MateClawApplication}.  Declaring it here as well
 * creates a <em>second</em> {@code ScheduledAnnotationBeanPostProcessor}
 * that competes with the first, resulting in some tasks unknowingly
 * scheduled on the default single-thread executor.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    /** Pool threads — enough for concurrent ticks but kept moderate. */
    private static final int POOL_SIZE = 4;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // Re-use the singleton TaskScheduler bean so we never create two
        // separate thread-pool instances (the @Bean above is the single
        // source of truth).
        registrar.setTaskScheduler(taskScheduler());
    }
}
