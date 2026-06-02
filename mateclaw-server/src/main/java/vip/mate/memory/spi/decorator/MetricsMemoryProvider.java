package vip.mate.memory.spi.decorator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import vip.mate.memory.spi.MemoryProvider;

/**
 * Decorator that records Micrometer metrics for prefetch/sync/session-end operations.
 * <p>
 * Metrics emitted (all with tag provider=...):
 * - memory.prefetch.latency (Timer)
 * - memory.prefetch.failures (Counter)
 * - memory.sync.duration (Timer)
 * - memory.sync.failures (Counter)
 * - memory.session_end.duration (Timer)
 *
 * @author MateClaw Team
 */
public class MetricsMemoryProvider extends MemoryProviderDecorator {

    private final MeterRegistry meterRegistry;
    private final Timer prefetchTimer;
    private final Timer syncTimer;
    private final Timer sessionEndTimer;

    public MetricsMemoryProvider(MemoryProvider delegate, MeterRegistry meterRegistry) {
        super(delegate);
        this.meterRegistry = meterRegistry;
        String providerId = delegate.id();
        this.prefetchTimer = Timer.builder("memory.prefetch.latency")
                .tag("provider", providerId)
                .register(meterRegistry);
        this.syncTimer = Timer.builder("memory.sync.duration")
                .tag("provider", providerId)
                .register(meterRegistry);
        this.sessionEndTimer = Timer.builder("memory.session_end.duration")
                .tag("provider", providerId)
                .register(meterRegistry);
    }

    @Override
    public String prefetch(Long agentId, String userQuery) {
        return prefetch(agentId, userQuery, null);
    }

    @Override
    public String prefetch(Long agentId, String userQuery, String ownerKey) {
        return prefetchTimer.record(() -> {
            try {
                return delegate.prefetch(agentId, userQuery, ownerKey);
            } catch (Exception e) {
                meterRegistry.counter("memory.prefetch.failures",
                        "provider", delegate.id()).increment();
                throw e;
            }
        });
    }

    @Override
    public void syncTurn(Long agentId, String conversationId, String userMessage, String assistantReply) {
        syncTimer.record(() -> {
            try {
                delegate.syncTurn(agentId, conversationId, userMessage, assistantReply);
            } catch (Exception e) {
                meterRegistry.counter("memory.sync.failures",
                        "provider", delegate.id()).increment();
                throw e;
            }
        });
    }

    @Override
    public void onSessionEnd(Long agentId, String conversationId) {
        sessionEndTimer.record(() -> delegate.onSessionEnd(agentId, conversationId));
    }
}
