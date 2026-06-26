package vip.mate.context.intelligence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vip.mate.context.intelligence.config.ContextIntelligenceProperties;
import vip.mate.context.intelligence.listener.ContextSignalProcessor;
import vip.mate.context.intelligence.metrics.ContextIntelMetrics;
import vip.mate.context.intelligence.perception.BackendDiversityRegistry;
import vip.mate.context.intelligence.perception.PressureInferencer;
import vip.mate.context.intelligence.persist.PersistRetryQueue;
import vip.mate.context.intelligence.persist.WindowStateRepository;
import vip.mate.context.intelligence.probe.WindowProbeRegistry;
import vip.mate.context.intelligence.snapshot.EnvSnapshotStore;

/**
 * Context Intelligence v2 auto-configuration entry.
 * <p>
 * <b>Assembly strategy</b> (following project conventions, see {@code HookAutoConfiguration} / {@code MemoryAutoConfiguration}):
 * <ul>
 *   <li>{@link ContextIntelligenceProperties} <b>always registered</b>:
 *       Integration points (ReasoningNode / AgentGraphBuilder) need {@code isEnabled()} to decide whether to fall back to yml,
 *       so the config class must be injectable even when the module is disabled.</li>
 *   <li>{@link WindowProbeRegistry} / {@link EnvSnapshotStore} / {@link TokenBudgetPlanner} etc.
 *       {@code @Component} <b>always auto-scanned</b>:
 *       Components have internal {@code isEnabled()} guards (e.g. {@code evictIdleProbes} returns directly when disabled);
 *       when disabled they are idle beans, and the read path relies on EnvSnapshot=EMPTY to trigger yml fallback.</li>
 *   <li>{@link ContextSignalProcessor} <b>conditionally registered</b> (enabled=true):
 *       When disabled, no events are listened to and EnvSnapshot is always EMPTY, naturally triggering the three-layer fallback.
 *       Meanwhile, the thread pool of {@code SignalExecutorConfig} is also conditionally created, with no overhead when disabled.</li>
 * </ul>
 *
 * <p><b>Default enabled=false</b> (design doc §11 Phase 1: new module coexists without affecting the existing system).
 * After integration is complete, explicitly enable it via {@code application.yml}.</p>
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(ContextIntelligenceProperties.class)
public class ContextIntelligenceAutoConfiguration {

    /**
     * Signal processor bean (write path entry).
     * <p>
     * Registered only when {@code mateclaw.context.intelligence.enabled=true}.
     * When disabled, no events are published and no listeners exist; the read path relies on EnvSnapshotStore returning EMPTY to trigger fallback.
     */
    @Bean
    @ConditionalOnProperty(name = "mateclaw.context.intelligence.enabled", havingValue = "true")
    public ContextSignalProcessor contextSignalProcessor(
            WindowProbeRegistry windowProbeRegistry,
            BackendDiversityRegistry diversityRegistry,
            PressureInferencer pressureInferencer,
            EnvSnapshotStore envSnapshotStore,
            WindowStateRepository windowStateRepository,
            PersistRetryQueue persistRetryQueue,
            ContextIntelMetrics metrics) {
        return new ContextSignalProcessor(
                windowProbeRegistry,
                diversityRegistry,
                pressureInferencer,
                envSnapshotStore,
                windowStateRepository,
                persistRetryQueue,
                metrics);
    }
}
