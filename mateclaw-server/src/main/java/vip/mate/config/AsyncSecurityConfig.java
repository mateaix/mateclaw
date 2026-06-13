package vip.mate.config;

import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.task.DelegatingSecurityContextTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Global async executor config — ensures SecurityContext propagation to @Async threads.
 * <p>
 * The inner delegate uses virtual threads (JDK 21); the outer
 * {@link DelegatingSecurityContextTaskExecutor} wrapper propagates the caller's
 * SecurityContext (JWT identity, audit permissions) to every @Async invocation.
 * <p>
 * A concurrency limit is set to prevent runaway virtual-thread creation
 * from exhausting the HikariCP pool (typical pattern: a stampede of
 * {@code @Async} tasks all trying to acquire DB connections at the same
 * minute boundary). Excess tasks are rejected immediately so the
 * scheduler threads never block on submission.
 *
 * @author MateClaw Team
 */
@Configuration
@EnableAsync
public class AsyncSecurityConfig implements AsyncConfigurer {

    /**
     * Cap in-flight async tasks. Well below HikariCP maximum-pool-size (30)
     * so async tasks never saturate the pool on their own — non-async paths
     * (HTTP requests, SSE, channel adapters) always have headroom.
     */
    private static final int ASYNC_CONCURRENCY_LIMIT = 24;

    @Override
    public Executor getAsyncExecutor() {
        // Keep DelegatingSecurityContextTaskExecutor so SecurityContext
        // is propagated to every @Async invocation (JWT, audit, permission checks).
        // Replace the inner platform-thread pool with a virtual-thread executor.
        var delegate = new SimpleAsyncTaskExecutorBuilder()
                .virtualThreads(true)
                .concurrencyLimit(ASYNC_CONCURRENCY_LIMIT)
                .threadNamePrefix("async-vt-")
                .build();
        return new DelegatingSecurityContextTaskExecutor(delegate);
    }
}
