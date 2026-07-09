package vip.mate.agent.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.skill.event.SkillUpdatedEvent;
import vip.mate.tool.mcp.event.McpConnectionLostEvent;
import vip.mate.tool.mcp.event.McpServerChangedEvent;
import vip.mate.tool.mcp.event.McpServerRemovedEvent;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * White-box tests for {@link RunningConversationRegistry} and
 * {@link EnvironmentEventRouter}. These verify the C-class event-routing
 * pipeline: register → event fires → notification queued → drain on next turn.
 */
class RunningConversationRegistryTest {

    private RunningConversationRegistry registry;
    private EnvironmentEventRouter router;

    @BeforeEach
    void setUp() {
        registry = new RunningConversationRegistry();
        router = new EnvironmentEventRouter(registry);
    }

    // ==================== Registry lifecycle ====================

    @Test
    void registerMakesConversationActive() {
        assertThat(registry.isActive("conv-1")).isFalse();
        registry.register("conv-1", 42L);
        assertThat(registry.isActive("conv-1")).isTrue();
        assertThat(registry.activeConversations()).contains("conv-1");
    }

    @Test
    void unregisterMakesConversationInactive() {
        registry.register("conv-1", 42L);
        registry.unregister("conv-1");
        assertThat(registry.isActive("conv-1")).isFalse();
        assertThat(registry.activeConversations()).doesNotContain("conv-1");
    }

    @Test
    void registerIsIdempotent() {
        registry.register("conv-1", 42L);
        registry.register("conv-1", 42L); // no-op, just refreshes lastActiveAt
        assertThat(registry.activeConversations()).hasSize(1);
    }

    @Test
    void unregisterUnknownConversationIsSafe() {
        registry.unregister("never-registered");
        // no exception thrown
    }

    @Test
    void nullAndBlankConversationIdIgnored() {
        registry.register(null, 42L);
        registry.register("", 42L);
        registry.register("  ", 42L);
        assertThat(registry.activeConversations()).isEmpty();
    }

    // ==================== Queue + drain ====================

    @Test
    void drainReturnsEmptyForInactiveConversation() {
        List<EnvironmentNotification> notes = registry.drain("never-active");
        assertThat(notes).isEmpty();
    }

    @Test
    void drainEmptiesTheQueue() {
        registry.register("conv-1", 42L);
        registry.enqueue("conv-1", new EnvironmentNotification("test", "msg-1", Instant.now()));
        registry.enqueue("conv-1", new EnvironmentNotification("test", "msg-2", Instant.now()));

        List<EnvironmentNotification> first = registry.drain("conv-1");
        assertThat(first).hasSize(2);

        List<EnvironmentNotification> second = registry.drain("conv-1");
        assertThat(second).isEmpty(); // queue was drained
    }

    @Test
    void enqueueToInactiveConversationDropsMessage() {
        // Event fires when no conversation is active — message is lost (by design)
        registry.enqueue("never-active", new EnvironmentNotification("test", "msg", Instant.now()));
        assertThat(registry.drain("never-active")).isEmpty();
    }

    @Test
    void queueBoundedToTenEvictsOldest() {
        registry.register("conv-1", 42L);
        for (int i = 0; i < 15; i++) {
            registry.enqueue("conv-1", new EnvironmentNotification("test", "msg-" + i, Instant.now()));
        }
        List<EnvironmentNotification> notes = registry.drain("conv-1");
        assertThat(notes).hasSize(RunningConversationRegistry.MAX_NOTIFICATIONS_PER_CONVERSATION);
        // Oldest messages (msg-0 through msg-4) should have been evicted
        assertThat(notes.get(0).message()).isEqualTo("msg-5");
        assertThat(notes.get(9).message()).isEqualTo("msg-14");
    }

    // ==================== Broadcast ====================

    @Test
    void broadcastReachesAllActiveConversations() {
        registry.register("conv-A", 1L);
        registry.register("conv-B", 2L);
        registry.register("conv-C", 3L);

        registry.broadcast(new EnvironmentNotification("mcp-lost", "server down", Instant.now()));

        assertThat(registry.drain("conv-A")).hasSize(1);
        assertThat(registry.drain("conv-B")).hasSize(1);
        assertThat(registry.drain("conv-C")).hasSize(1);
    }

    @Test
    void broadcastToNoActiveConversationsIsNoOp() {
        registry.broadcast(new EnvironmentNotification("test", "msg", Instant.now()));
        // no exception, no side effect
    }

    // ==================== Event Router ====================

    @Test
    void mcpServerChangedEventQueuesNotification() {
        registry.register("conv-1", 42L);
        router.onMcpServerChanged(new McpServerChangedEvent("mcp-tools-changed:99"));

        List<EnvironmentNotification> notes = registry.drain("conv-1");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).type()).isEqualTo("mcp-changed");
        assertThat(notes.get(0).message()).contains("MCP 工具列表已变更");
        assertThat(notes.get(0).message()).contains("mcp-tools-changed:99");
    }

    @Test
    void mcpConnectionLostEventQueuesNotificationWithServerId() {
        registry.register("conv-1", 42L);
        router.onMcpConnectionLost(new McpConnectionLostEvent(7L, "stdio-process-exited"));

        List<EnvironmentNotification> notes = registry.drain("conv-1");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).type()).isEqualTo("mcp-lost");
        assertThat(notes.get(0).message()).contains("7");
        assertThat(notes.get(0).message()).contains("mcp_7_");
    }

    @Test
    void mcpServerRemovedEventQueuesNotification() {
        registry.register("conv-1", 42L);
        router.onMcpServerRemoved(new McpServerRemovedEvent(3L, "my-server"));

        List<EnvironmentNotification> notes = registry.drain("conv-1");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).type()).isEqualTo("mcp-removed");
        assertThat(notes.get(0).message()).contains("my-server");
        assertThat(notes.get(0).message()).contains("mcp_3_");
    }

    @Test
    void skillUpdatedEventQueuesNotification() {
        registry.register("conv-1", 42L);
        router.onSkillUpdated(new SkillUpdatedEvent(10L, "web-scraper", "update"));

        List<EnvironmentNotification> notes = registry.drain("conv-1");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).type()).isEqualTo("skill-updated");
        assertThat(notes.get(0).message()).contains("web-scraper");
        assertThat(notes.get(0).message()).contains("已更新");
    }

    @Test
    void skillUpdatedEnableEventUsesCorrectVerb() {
        registry.register("conv-1", 42L);
        router.onSkillUpdated(new SkillUpdatedEvent(11L, "data-processor", "enable"));

        List<EnvironmentNotification> notes = registry.drain("conv-1");
        assertThat(notes.get(0).message()).contains("已启用");
    }

    @Test
    void eventWithNoActiveConversationsIsSilentlyDropped() {
        // No conversation registered — router should not throw
        router.onMcpServerChanged(new McpServerChangedEvent("test"));
        router.onMcpConnectionLost(new McpConnectionLostEvent(1L, "test"));
        router.onMcpServerRemoved(new McpServerRemovedEvent(1L, "test"));
        // No exception thrown
    }

    @Test
    void multipleEventsQueueInOrder() {
        registry.register("conv-1", 42L);
        router.onMcpServerChanged(new McpServerChangedEvent("change-1"));
        router.onMcpConnectionLost(new McpConnectionLostEvent(5L, "lost-1"));
        router.onSkillUpdated(new SkillUpdatedEvent(1L, "skill", "update"));

        List<EnvironmentNotification> notes = registry.drain("conv-1");
        assertThat(notes).hasSize(3);
        assertThat(notes.get(0).type()).isEqualTo("mcp-changed");
        assertThat(notes.get(1).type()).isEqualTo("mcp-lost");
        assertThat(notes.get(2).type()).isEqualTo("skill-updated");
    }

    // ==================== Stale-handle cleanup ====================

    @Test
    void cleanupStaleRemovesOldHandles() throws Exception {
        registry.register("stale-conv", 1L);
        // Backdate lastActiveAt to 1 hour ago via reflection
        backdateLastActive("stale-conv", java.time.Instant.now().minusSeconds(3600));

        registry.register("fresh-conv", 2L);  // fresh — just registered

        int removed = registry.cleanupStale(java.time.Duration.ofMinutes(30));

        assertThat(removed).isEqualTo(1);
        assertThat(registry.isActive("stale-conv")).isFalse();
        assertThat(registry.isActive("fresh-conv")).isTrue();
    }

    @Test
    void cleanupStaleKeepsActiveConversations() {
        registry.register("conv-1", 1L);
        registry.register("conv-2", 2L);
        registry.register("conv-3", 3L);

        int removed = registry.cleanupStale(java.time.Duration.ofMinutes(30));

        assertThat(removed).isZero();
        assertThat(registry.activeConversations()).hasSize(3);
    }

    @Test
    void cleanupStaleWithZeroOrNegativeDurationIsNoOp() {
        registry.register("conv-1", 1L);
        assertThat(registry.cleanupStale(java.time.Duration.ZERO)).isZero();
        assertThat(registry.cleanupStale(java.time.Duration.ofMillis(-1))).isZero();
        assertThat(registry.cleanupStale(null)).isZero();
        assertThat(registry.isActive("conv-1")).isTrue();
    }

    @Test
    void cleanupStaleDoesNotRemoveRefreshedHandle() throws Exception {
        registry.register("conv-1", 1L);
        // Backdate, then re-register (which refreshes lastActiveAt)
        backdateLastActive("conv-1", java.time.Instant.now().minusSeconds(3600));
        registry.register("conv-1", 1L);  // refresh

        int removed = registry.cleanupStale(java.time.Duration.ofMinutes(30));

        assertThat(removed).isZero();
        assertThat(registry.isActive("conv-1")).isTrue();
    }

    @Test
    void cleanupStaleRemovesMultipleStaleHandles() throws Exception {
        registry.register("stale-1", 1L);
        registry.register("stale-2", 2L);
        registry.register("stale-3", 3L);
        registry.register("fresh-1", 4L);

        for (String conv : new String[]{"stale-1", "stale-2", "stale-3"}) {
            backdateLastActive(conv, java.time.Instant.now().minusSeconds(3600));
        }

        int removed = registry.cleanupStale(java.time.Duration.ofMinutes(30));

        assertThat(removed).isEqualTo(3);
        assertThat(registry.isActive("stale-1")).isFalse();
        assertThat(registry.isActive("stale-2")).isFalse();
        assertThat(registry.isActive("stale-3")).isFalse();
        assertThat(registry.isActive("fresh-1")).isTrue();
    }

    @Test
    void scheduledCleanupIsSafeToCallWithNoActiveConversations() {
        registry.scheduledCleanup();
        // No exception, no side effect
    }

    /**
     * Helper: use reflection to backdate a conversation handle's
     * {@code lastActiveAt} field, simulating a stale registration.
     */
    private void backdateLastActive(String conversationId, java.time.Instant past) throws Exception {
        java.lang.reflect.Field activeField = RunningConversationRegistry.class
                .getDeclaredField("active");
        activeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentMap<String, Object> active =
                (java.util.concurrent.ConcurrentMap<String, Object>) activeField.get(registry);
        Object handle = active.get(conversationId);
        assertThat(handle).as("handle must exist for conv " + conversationId).isNotNull();
        java.lang.reflect.Field lastActiveField = handle.getClass()
                .getDeclaredField("lastActiveAt");
        lastActiveField.setAccessible(true);
        lastActiveField.set(handle, past);
    }
}
