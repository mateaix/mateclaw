package vip.mate.memory;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.memory.spi.MemoryProvider;
import vip.mate.plugin.api.memory.PluginMemoryProvider;
import vip.mate.plugin.bridge.PluginMemoryBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the end-to-end prefetch path:
 * <pre>
 *   MemoryManager.prefetchAll(agentId, query, ownerKey)
 *     → MemoryProvider (which is a PluginMemoryBridge)
 *       → PluginMemoryProvider.prefetch(agentId, query, ownerKey)  [three-arg variant]
 * </pre>
 *
 * <p>This is the test that catches the silent regression where the bridge
 * forgets to override the three-arg variant and the plugin's per-owner
 * isolation is lost. The L2 {@code PluginMemoryBridgeTest} covers the
 * bridge in isolation; this test verifies the platform actually invokes
 * the three-arg path when an owner key is present.
 *
 * <p>Style follows {@link MemoryManagerBudgetTest}: construct the manager
 * directly with stub providers, no Spring context.
 */
class MemoryManagerPluginPrefetchTest {

    @Test
    @DisplayName("prefetchAll with ownerKey reaches the plugin's three-arg prefetch verbatim")
    void prefetchAllForwardsOwnerKeyToPlugin() {
        AtomicReference<String> receivedOwner = new AtomicReference<>("sentinel");
        AtomicReference<Long> receivedAgent = new AtomicReference<>();
        AtomicReference<String> receivedQuery = new AtomicReference<>();

        PluginMemoryProvider plugin = new PluginMemoryProvider() {
            @Override
            public String id() { return "test-plugin-mem"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public String prefetch(Long agentId, String userQuery, String ownerKey) {
                receivedOwner.set(ownerKey);
                receivedAgent.set(agentId);
                receivedQuery.set(userQuery);
                return "[Plugin Recall] user has Scala + Cats Effect stack";
            }
        };

        MemoryProvider bridge = new PluginMemoryBridge(plugin);
        MemoryManager manager = newManager(bridge);

        String result = manager.prefetchAll(7L, "what stack does my project use", "user:42");

        assertEquals("user:42", receivedOwner.get(),
                "ownerKey must reach the plugin — this is the whole point of the SPI extension");
        assertEquals(7L, receivedAgent.get());
        assertEquals("what stack does my project use", receivedQuery.get());
        assertTrue(result.contains("[Plugin Recall]"),
                "plugin's recall block must appear in the merged context: " + result);
    }

    @Test
    @DisplayName("prefetchAll(agentId, query) without ownerKey delegates to the three-arg path " +
            "with null ownerKey — plugins see null and can opt out of per-owner recall")
    void prefetchAllWithoutOwnerKeyPassesNullToThreeArg() {
        AtomicReference<String> receivedOwner = new AtomicReference<>("sentinel");
        PluginMemoryProvider plugin = new PluginMemoryProvider() {
            @Override
            public String id() { return "test-plugin-mem"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public String prefetch(Long agentId, String userQuery, String ownerKey) {
                receivedOwner.set(ownerKey);
                return ownerKey == null ? "" : "[should not happen]";
            }
        };

        MemoryManager manager = newManager(new PluginMemoryBridge(plugin));
        String result = manager.prefetchAll(7L, "hello");

        assertEquals(null, receivedOwner.get(),
                "two-arg prefetchAll must surface as null ownerKey to the plugin's " +
                        "three-arg variant — null is the contract signal that lets plugins " +
                        "skip per-owner work without a separate code path");
        assertFalse(result.contains("[should not happen]"));
    }

    @Test
    @DisplayName("multiple providers — plugin's block joins the others, ownerKey still reaches it")
    void multipleProvidersJoinAndOwnerKeyReachesPlugin() {
        AtomicReference<String> pluginOwner = new AtomicReference<>();
        // A builtin-style provider that ignores ownerKey (two-arg semantics).
        MemoryProvider builtin = new MemoryProvider() {
            @Override
            public String id() { return "builtin"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public String prefetch(Long agentId, String userQuery, String ownerKey) {
                return "[Builtin] recent context";
            }
        };
        PluginMemoryProvider plugin = new PluginMemoryProvider() {
            @Override
            public String id() { return "test-plugin-mem"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public String prefetch(Long agentId, String userQuery, String ownerKey) {
                pluginOwner.set(ownerKey);
                return "[Plugin Recall] semantic match";
            }
        };

        MemoryManager manager = newManager(builtin, new PluginMemoryBridge(plugin));
        String result = manager.prefetchAll(1L, "query", "user:99");

        assertEquals("user:99", pluginOwner.get());
        assertTrue(result.contains("[Builtin]"), "builtin block present: " + result);
        assertTrue(result.contains("[Plugin Recall]"), "plugin block present: " + result);
    }

    @Test
    @DisplayName("plugin prefetch exception is swallowed — fault isolation holds at the manager level")
    void pluginExceptionIsSwallowed() {
        PluginMemoryProvider plugin = new PluginMemoryProvider() {
            @Override
            public String id() { return "test-plugin-mem"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public String prefetch(Long agentId, String userQuery, String ownerKey) {
                throw new IllegalStateException("simulated Mem0 outage");
            }
        };
        MemoryProvider builtin = stubBuiltin();
        MemoryManager manager = newManager(builtin, new PluginMemoryBridge(plugin));

        // Must NOT throw — Mem0 being down must not break the platform.
        String result = manager.prefetchAll(1L, "query", "user:42");
        assertTrue(result.contains("[Builtin]"),
                "builtin provider must still contribute even if the plugin threw: " + result);
    }

    @Test
    @DisplayName("an unavailable plugin is filtered out at construction (isAvailable()=false)")
    void unavailablePluginIsFiltered() {
        List<String> called = new ArrayList<>();
        PluginMemoryProvider plugin = new PluginMemoryProvider() {
            @Override
            public String id() { return "test-plugin-mem"; }

            @Override
            public boolean isAvailable() { return false; } // configured but not usable

            @Override
            public String prefetch(Long agentId, String userQuery, String ownerKey) {
                called.add("should-not-reach");
                return "[Plugin Recall]";
            }
        };
        MemoryManager manager = newManager(new PluginMemoryBridge(plugin));

        String result = manager.prefetchAll(1L, "query", "user:42");
        assertTrue(called.isEmpty(),
                "an unavailable plugin must not be called — manager filters at construction");
        assertFalse(result.contains("[Plugin Recall]"));
    }

    // ---- helpers ----

    private static MemoryProvider stubBuiltin() {
        return new MemoryProvider() {
            @Override
            public String id() { return "builtin"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public String prefetch(Long agentId, String userQuery, String ownerKey) {
                return "[Builtin] recent context";
            }
        };
    }

    private static MemoryManager newManager(MemoryProvider... providers) {
        ObjectProvider<MeterRegistry> noRegistry = new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MeterRegistry getIfAvailable() {
                return null;
            }
        };
        return new MemoryManager(List.of(providers), new MemoryProperties(), noRegistry);
    }
}
