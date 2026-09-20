package vip.mate.memory;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.memory.spi.MemoryProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryManagerResilienceTest {

    @Test
    void providerTimeoutDoesNotBlockLaterProviders() {
        MemoryProperties properties = new MemoryProperties();
        properties.setProviderPrefetchTimeoutMs(50);
        properties.setProviderPrefetchTotalBudgetMs(200);
        MemoryProvider slow = provider("slow", () -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
        MemoryProvider fast = provider("fast", () -> "useful");

        long started = System.nanoTime();
        try (MemoryManager manager = manager(properties, slow, fast)) {
            String result = manager.prefetchAll(1L, "query", "user:1");
            assertThat(result).contains("useful").doesNotContain("late");
        }
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_000);
    }

    @Test
    void totalBudgetStopsDispatchingRemainingProviders() {
        MemoryProperties properties = new MemoryProperties();
        properties.setProviderPrefetchTimeoutMs(500);
        properties.setProviderPrefetchTotalBudgetMs(60);
        AtomicInteger laterCalls = new AtomicInteger();
        MemoryProvider slow = provider("slow", () -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
        MemoryProvider later = provider("later", () -> {
            laterCalls.incrementAndGet();
            return "later";
        });

        try (MemoryManager manager = manager(properties, slow, later)) {
            assertThat(manager.prefetchAll(1L, "query")).isEmpty();
        }
        assertThat(laterCalls).hasValue(0);
    }

    @Test
    void openCircuitSkipsRepeatedFailures() {
        MemoryProperties properties = new MemoryProperties();
        properties.setProviderCircuitFailureThreshold(1);
        properties.setProviderCircuitCooldownSeconds(60);
        AtomicInteger calls = new AtomicInteger();
        MemoryProvider broken = provider("broken", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("offline");
        });

        try (MemoryManager manager = manager(properties, broken)) {
            assertThat(manager.prefetchAll(1L, "one")).isEmpty();
            assertThat(manager.prefetchAll(1L, "two")).isEmpty();
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void unregisterClosesOnlyTheExternalProvider() {
        MemoryProvider builtin = provider("builtin", () -> "builtin");
        AtomicBoolean pluginClosed = new AtomicBoolean();
        MemoryProvider plugin = new MemoryProvider() {
            @Override public String id() { return "plugin"; }
            @Override public void close() { pluginClosed.set(true); }
        };

        try (MemoryManager manager = manager(new MemoryProperties(), builtin)) {
            manager.registerPluginProvider(plugin);
            manager.unregisterPluginProvider("plugin");
            assertThat(pluginClosed).isTrue();
            assertThat(manager.getProviders()).containsExactly(builtin);
        }
    }

    private static MemoryProvider provider(String id, java.util.function.Supplier<String> prefetch) {
        return new MemoryProvider() {
            @Override public String id() { return id; }
            @Override public String prefetch(Long agentId, String query, String ownerKey) {
                return prefetch.get();
            }
        };
    }

    private static MemoryManager manager(MemoryProperties properties, MemoryProvider... providers) {
        ObjectProvider<MeterRegistry> noRegistry = new ObjectProvider<>() {
            @Override public MeterRegistry getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public MeterRegistry getIfAvailable() { return null; }
        };
        return new MemoryManager(List.of(providers), properties, noRegistry);
    }
}
