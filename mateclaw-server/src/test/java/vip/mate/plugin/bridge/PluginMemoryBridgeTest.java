package vip.mate.plugin.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.memory.spi.MemoryProvider;
import vip.mate.plugin.api.memory.PluginMemoryProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginMemoryBridge} adapts the self-contained plugin SPI
 * ({@link PluginMemoryProvider}) to the platform's internal
 * {@link MemoryProvider} interface.
 *
 * <p>These tests focus on the contract added for per-owner isolation:
 * the three-arg {@code prefetch(agentId, query, ownerKey)} must forward
 * {@code ownerKey} verbatim to the delegate. If a future refactor reverts
 * the bridge to the two-arg default, per-owner recall silently breaks for
 * every plugin provider — this test guards against that regression.
 */
class PluginMemoryBridgeTest {

    @Test
    @DisplayName("three-arg prefetch forwards ownerKey verbatim to the delegate")
    void threeArgPrefetchForwardsOwnerKey() {
        AtomicReference<String> receivedOwner = new AtomicReference<>();
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        AtomicReference<Long> receivedAgent = new AtomicReference<>();
        PluginMemoryProvider delegate = stub();
        delegate = new ForwardingPluginProvider(delegate) {
            @Override
            public String prefetch(Long agentId, String userQuery, String ownerKey) {
                receivedOwner.set(ownerKey);
                receivedQuery.set(userQuery);
                receivedAgent.set(agentId);
                return "[plugin recall]";
            }
        };

        PluginMemoryBridge bridge = new PluginMemoryBridge(delegate);
        String result = bridge.prefetch(42L, "hello", "user:42");

        assertEquals("user:42", receivedOwner.get(),
                "ownerKey must reach the delegate — per-owner isolation depends on it");
        assertEquals("hello", receivedQuery.get());
        assertEquals(42L, receivedAgent.get());
        assertEquals("[plugin recall]", result);
    }

    @Test
    @DisplayName("two-arg prefetch on the bridge forwards to the plugin's two-arg variant " +
            "(ownerKey is genuinely absent from this call path)")
    void twoArgPrefetchForwardsToTwoArgVariant() {
        AtomicReference<String> twoArgCalled = new AtomicReference<>("not-called");
        AtomicReference<String> threeArgCalled = new AtomicReference<>("not-called");
        PluginMemoryProvider delegate = new PluginMemoryProvider() {
            @Override
            public String id() { return "two-arg-tracker"; }

            @Override
            public String prefetch(Long agentId, String userQuery) {
                twoArgCalled.set("called");
                return "[two-arg result]";
            }

            @Override
            public String prefetch(Long agentId, String userQuery, String ownerKey) {
                threeArgCalled.set("called-with-" + ownerKey);
                return "[three-arg result]";
            }
        };

        PluginMemoryBridge bridge = new PluginMemoryBridge(delegate);
        String result = bridge.prefetch(42L, "hello");

        assertEquals("called", twoArgCalled.get(),
                "bridge two-arg prefetch must forward to the plugin's two-arg variant");
        assertEquals("not-called", threeArgCalled.get(),
                "bridge two-arg prefetch must NOT invoke the plugin's three-arg variant — " +
                        "ownerKey is absent from this call path");
        assertEquals("[two-arg result]", result);
    }

    @Test
    @DisplayName("when the plugin does not override the three-arg variant, " +
            "the SPI default degrades to two-arg (ownerKey dropped, not crashed)")
    void threeArgPrefetchDegradesToTwoArgWhenPluginDoesNotOverride() {
        // A plugin that only implements the two-arg prefetch.
        PluginMemoryProvider pluginOnlyTwoArg = new PluginMemoryProvider() {
            @Override
            public String id() { return "two-arg-only"; }

            @Override
            public String prefetch(Long agentId, String userQuery) {
                return "[two-arg recall for " + agentId + "]";
            }
        };

        PluginMemoryBridge bridge = new PluginMemoryBridge(pluginOnlyTwoArg);
        // Three-arg call must degrade gracefully via the SPI default method.
        String result = bridge.prefetch(42L, "hello", "user:42");
        assertEquals("[two-arg recall for 42]", result,
                "SPI default should drop ownerKey and route to the two-arg impl, " +
                        "not crash with AbstractMethodError");
    }

    @Test
    @DisplayName("metadata (id/order/isAvailable) and lifecycle hooks pass through")
    void metadataAndLifecyclePassthrough() {
        PluginMemoryProvider delegate = stub();
        PluginMemoryBridge bridge = new PluginMemoryBridge(delegate);

        assertEquals("stub-mem", bridge.id());
        assertEquals(200, bridge.order());
        assertTrue(bridge.isAvailable());

        // syncTurn / onSessionEnd / systemPromptBlock are pure forwarders; calling
        // them must not throw.
        bridge.syncTurn(1L, "conv-1", "hi", "hello");
        bridge.onSessionEnd(1L, "conv-1");
        assertEquals("", bridge.systemPromptBlock(1L));
    }

    @Test
    @DisplayName("a null getToolBeans() from a sloppy plugin is normalised to emptyList")
    void nullToolBeansNormalised() {
        PluginMemoryProvider sloppy = new ForwardingPluginProvider(stub()) {
            @Override
            public List<Object> getToolBeans() {
                return null; // sloppy plugin returns null
            }
        };
        PluginMemoryBridge bridge = new PluginMemoryBridge(sloppy);

        List<Object> tools = bridge.getToolBeans();
        assertNotNull(tools, "bridge must never expose null to the platform");
        assertTrue(tools.isEmpty());
    }

    @Test
    @DisplayName("a non-empty getToolBeans() list is forwarded as-is")
    void nonEmptyToolBeansForwarded() {
        Object toolBean = new Object();
        PluginMemoryProvider delegate = new ForwardingPluginProvider(stub()) {
            @Override
            public List<Object> getToolBeans() {
                return List.of(toolBean);
            }
        };
        PluginMemoryBridge bridge = new PluginMemoryBridge(delegate);

        List<Object> tools = bridge.getToolBeans();
        assertEquals(1, tools.size());
        assertSame(toolBean, tools.get(0));
    }

    // ---- helpers ----

    private static PluginMemoryProvider stub() {
        return new PluginMemoryProvider() {
            @Override
            public String id() { return "stub-mem"; }
            // other methods keep their default implementations
        };
    }

    /**
     * Base class that forwards every method to a delegate, so individual tests
     * only override the one method they care about. Mirrors the pattern in
     * {@code PluginSearchBridgeTest}'s anonymous stubs.
     */
    private static class ForwardingPluginProvider implements PluginMemoryProvider {
        private final PluginMemoryProvider delegate;

        ForwardingPluginProvider(PluginMemoryProvider delegate) {
            this.delegate = delegate;
        }

        @Override public String id() { return delegate.id(); }
        @Override public int order() { return delegate.order(); }
        @Override public boolean isAvailable() { return delegate.isAvailable(); }
        @Override public String systemPromptBlock(Long agentId) {
            return delegate.systemPromptBlock(agentId);
        }
        @Override public String prefetch(Long agentId, String userQuery) {
            return delegate.prefetch(agentId, userQuery);
        }
        @Override public String prefetch(Long agentId, String userQuery, String ownerKey) {
            return delegate.prefetch(agentId, userQuery, ownerKey);
        }
        @Override public void syncTurn(Long agentId, String conversationId,
                                       String userMessage, String assistantReply) {
            delegate.syncTurn(agentId, conversationId, userMessage, assistantReply);
        }
        @Override public List<Object> getToolBeans() { return delegate.getToolBeans(); }
        @Override public void onSessionEnd(Long agentId, String conversationId) {
            delegate.onSessionEnd(agentId, conversationId);
        }
    }
}
