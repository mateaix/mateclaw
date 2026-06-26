package vip.mate.context.intelligence.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated thread pool config for signal processing.
 * <p>
 * Provides an independent thread pool for {@code @Async("signalExecutor")},
 * isolated from the virtual thread executor of the global {@code AsyncSecurityConfig},
 * to prevent signal processing tasks from crowding out business async task quota.
 * <p>
 * Rejection policy uses {@code CallerRunsPolicy}: when the queue is full, falls back to the caller thread
 * (reactor thread); losing a signal is not fatal (the next one will arrive), but fallback is safer than discard.
 * <p>
 * Created only when {@code mateclaw.context.intelligence.enabled=true};
 * when disabled there are no listeners and thus no need for a thread pool.
 *
 * @author MateClaw Team
 */
@Configuration
public class SignalExecutorConfig {

    @Bean("signalExecutor")
    @ConditionalOnProperty(name = "mateclaw.context.intelligence.enabled", havingValue = "true")
    public ThreadPoolTaskExecutor signalExecutor(ContextIntelligenceProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        int core = props.getExecutor().getCoreSize();
        ex.setCorePoolSize(core);
        ex.setMaxPoolSize(core * 2);
        ex.setQueueCapacity(props.getExecutor().getMaxQueueSize());
        ex.setThreadNamePrefix("ctx-signal-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }
}
