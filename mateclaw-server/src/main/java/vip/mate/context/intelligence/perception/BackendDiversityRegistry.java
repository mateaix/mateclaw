package vip.mate.context.intelligence.perception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.context.intelligence.config.ContextIntelligenceProperties;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-model BackendDiversityTracker registry.
 * <p>
 * Mirrors the compute pattern of {@code WindowProbeRegistry}, managing tracker instances
 * keyed by (provider, model). One independent tracker per key (not global), because the
 * detection targets the variance "under the same model name".
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class BackendDiversityRegistry {

    private final ConcurrentHashMap<String, BackendDiversityTracker> trackers = new ConcurrentHashMap<>();

    private final ContextIntelligenceProperties props;

    public BackendDiversityRegistry(ContextIntelligenceProperties props) {
        this.props = props;
    }

    /** Record a successful call */
    public void recordSuccess(String key, int promptTokens) {
        try {
            trackers.computeIfAbsent(key, k -> new BackendDiversityTracker(props.getDiversity()))
                    .recordSuccess(promptTokens);
        } catch (Exception e) {
            log.debug("[ContextIntel] DiversityTracker recordSuccess failed for {}: {}", key, e.getMessage());
        }
    }

    /** Record an overflow call (v1 Bug 2 fix: overflow finally feeds into diversity detection) */
    public void recordOverflow(String key, int attemptedTokens) {
        try {
            trackers.computeIfAbsent(key, k -> new BackendDiversityTracker(props.getDiversity()))
                    .recordOverflow(attemptedTokens);
        } catch (Exception e) {
            log.debug("[ContextIntel] DiversityTracker recordOverflow failed for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Get the tracker (for the read path).
     * <p>
     * May return null (computeIfAbsent not yet triggered on first access).
     */
    public BackendDiversityTracker get(String key) {
        return trackers.get(key);
    }

    /** Restore the diversityDetected flag from DB (§5.7 restart recovery) */
    public void restore(String key, boolean diversityDetected) {
        trackers.compute(key, (k, existing) -> {
            if (existing != null) {
                // already exists: do not overwrite the existing tracker (in-memory data takes priority)
                return existing;
            }
            return BackendDiversityTracker.restore(diversityDetected, props.getDiversity());
        });
    }

    /** Idle eviction (coordinated by WindowProbeRegistry.evictIdleProbes) */
    public void evict(String key) {
        trackers.remove(key);
    }
}
