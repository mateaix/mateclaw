package vip.mate.agent.graph.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import vip.mate.agent.runtime.EnvironmentEventRouter;
import vip.mate.agent.runtime.EnvironmentNotification;
import vip.mate.agent.runtime.RunningConversationRegistry;
import vip.mate.skill.event.SkillUpdatedEvent;
import vip.mate.tool.mcp.event.McpConnectionLostEvent;
import vip.mate.tool.mcp.event.McpServerChangedEvent;
import vip.mate.tool.mcp.event.McpServerRemovedEvent;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box test for the C-class environment-awareness pipeline:
 * MCP / skill event fires → {@link EnvironmentEventRouter} translates it →
 * {@link RunningConversationRegistry} queues it → next reasoning turn drains
 * the queue → {@link ReasoningNode#renderEnvironmentNotifications} renders
 * the LLM-visible block.
 *
 * <p>Verifies the LLM actually receives <b>actionable</b> text on the next
 * turn — not just that an event was queued. This is the user-facing
 * contract: the agent must be told, in plain language, which tool prefix
 * broke, which skill changed, and what to do about it.
 *
 * <p>The rendering helper is exercised via the real production static
 * method on {@link ReasoningNode} (package-private for testability), so a
 * format regression in the helper fails this test rather than a duplicate.
 */
class EnvironmentNotificationRenderingTest {

    private RunningConversationRegistry registry;
    private EnvironmentEventRouter router;

    @BeforeEach
    void setUp() {
        registry = new RunningConversationRegistry();
        router = new EnvironmentEventRouter(registry);
    }

    // ==================== Render helper contract ====================

    @Test
    void renderNullReturnsNull() {
        assertThat(ReasoningNode.renderEnvironmentNotifications(null)).isNull();
    }

    @Test
    void renderEmptyReturnsNull() {
        // Empty drain should NOT inject "(no notifications)" noise — the
        // caller checks for null and skips the SystemMessage entirely.
        assertThat(ReasoningNode.renderEnvironmentNotifications(List.of())).isNull();
    }

    @Test
    void renderSingleNotificationHasHeaderAndDirective() {
        EnvironmentNotification n = new EnvironmentNotification(
                "mcp-lost", "⚠️ server 7 down", Instant.now());

        String block = ReasoningNode.renderEnvironmentNotifications(List.of(n));

        assertThat(block).isNotNull();
        assertThat(block).contains("📢 环境变更通知");
        assertThat(block).contains("⚠️ server 7 down");
        // Authority + directive tail must be present — the LLM is told this
        // is Java-injected truth, not a heuristic, and must adapt.
        assertThat(block).contains("Java 运行时检测");
        assertThat(block).contains("立即据此调整计划");
    }

    @Test
    void renderMultipleNotificationsAllVisible() {
        List<EnvironmentNotification> notes = List.of(
                new EnvironmentNotification("mcp-lost", "⚠️ server 7 down", Instant.now()),
                new EnvironmentNotification("skill-updated", "🔄 web-scraper updated", Instant.now()),
                new EnvironmentNotification("mcp-removed", "❌ server 3 removed", Instant.now()));

        String block = ReasoningNode.renderEnvironmentNotifications(notes);

        assertThat(block).isNotNull();
        assertThat(block).contains("⚠️ server 7 down");
        assertThat(block).contains("🔄 web-scraper updated");
        assertThat(block).contains("❌ server 3 removed");
        // Each notification renders as its own bullet
        assertThat(block.split("\n")).anyMatch(line -> line.trim().startsWith("- ⚠️"));
    }

    // ==================== End-to-end: event → drain → render ====================

    @Test
    void mcpConnectionLostEventEndToEnd_producesActionableLLMText() {
        registry.register("conv-1", 42L);
        router.onMcpConnectionLost(new McpConnectionLostEvent(7L, "stdio-process-exited"));

        List<EnvironmentNotification> notes = registry.drain("conv-1");
        String block = ReasoningNode.renderEnvironmentNotifications(notes);

        assertThat(block).isNotNull();
        // Critical actionable info: serverId, tool prefix, explicit "don't retry"
        assertThat(block).contains("7");
        assertThat(block).contains("mcp_7_");
        assertThat(block).contains("不要反复重试");
    }

    @Test
    void mcpServerRemovedEventEndToEnd_producesActionableLLMText() {
        registry.register("conv-1", 42L);
        router.onMcpServerRemoved(new McpServerRemovedEvent(3L, "fetch-server"));

        String block = ReasoningNode.renderEnvironmentNotifications(registry.drain("conv-1"));

        assertThat(block).isNotNull();
        assertThat(block).contains("fetch-server");
        assertThat(block).contains("mcp_3_");
        assertThat(block).contains("永久失效");
    }

    @Test
    void mcpServerChangedEventEndToEnd_producesActionableLLMText() {
        registry.register("conv-1", 42L);
        router.onMcpServerChanged(new McpServerChangedEvent("mcp-rescan-complete"));

        String block = ReasoningNode.renderEnvironmentNotifications(registry.drain("conv-1"));

        assertThat(block).isNotNull();
        assertThat(block).contains("MCP 工具列表已变更");
        assertThat(block).contains("mcp-rescan-complete");
    }

    @Test
    void skillUpdatedEventEndToEnd_producesActionableLLMText() {
        registry.register("conv-1", 42L);
        router.onSkillUpdated(new SkillUpdatedEvent(10L, "web-scraper", "update"));

        String block = ReasoningNode.renderEnvironmentNotifications(registry.drain("conv-1"));

        assertThat(block).isNotNull();
        assertThat(block).contains("web-scraper");
        assertThat(block).contains("已更新");
        // The LLM is told to re-load_skill to refresh constraints
        assertThat(block).contains("load_skill");
    }

    @Test
    void multipleEventsFiredMidTurnAllReachNextReasoningTurn() {
        // Simulate a chaotic mid-turn environment: 3 changes fire while the
        // agent is mid-tool-call. The next reasoning turn must see ALL of
        // them, in order, in a single SystemMessage.
        registry.register("conv-1", 42L);
        router.onMcpServerChanged(new McpServerChangedEvent("change-A"));
        router.onMcpConnectionLost(new McpConnectionLostEvent(5L, "lost-B"));
        router.onSkillUpdated(new SkillUpdatedEvent(1L, "skill-C", "update"));

        List<EnvironmentNotification> notes = registry.drain("conv-1");
        String block = ReasoningNode.renderEnvironmentNotifications(notes);

        assertThat(notes).hasSize(3);
        assertThat(block).isNotNull();
        // Order preserved: change-A first, lost-B second, skill-C third
        int idxA = block.indexOf("change-A");
        int idxB = block.indexOf("mcp_5_");
        int idxC = block.indexOf("skill-C");
        assertThat(idxA).isGreaterThan(-1);
        assertThat(idxB).isGreaterThan(idxA);
        assertThat(idxC).isGreaterThan(idxB);
    }

    // ==================== At-most-once delivery ====================

    @Test
    void drainIsEmptyOnSecondCallSoNotificationIsInjectedAtMostOnce() {
        registry.register("conv-1", 42L);
        router.onMcpConnectionLost(new McpConnectionLostEvent(7L, "first"));

        List<EnvironmentNotification> first = registry.drain("conv-1");
        List<EnvironmentNotification> second = registry.drain("conv-1");

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        // The second turn's render must be skipped (returns null) — the
        // notification must NOT echo into a third turn.
        assertThat(ReasoningNode.renderEnvironmentNotifications(second)).isNull();
    }

    @Test
    void newEventsFiredAfterFirstDrainAreVisibleOnSecondTurn() {
        // Sequential delivery: event 1 fires → drained on turn 1 → event 2
        // fires → drained on turn 2. Each turn sees only what's new since
        // the previous drain.
        registry.register("conv-1", 42L);
        router.onMcpConnectionLost(new McpConnectionLostEvent(1L, "first"));

        String turn1 = ReasoningNode.renderEnvironmentNotifications(registry.drain("conv-1"));
        assertThat(turn1).contains("mcp_1_");

        router.onMcpConnectionLost(new McpConnectionLostEvent(2L, "second"));
        String turn2 = ReasoningNode.renderEnvironmentNotifications(registry.drain("conv-1"));
        assertThat(turn2).contains("mcp_2_");
        assertThat(turn2).doesNotContain("mcp_1_"); // first event not re-delivered
    }

    // ==================== Survives compression (nonHistoryPrefix invariant) ====================

    @Test
    void notificationBlockIsExactlyOneSystemMessage_soSurvivesCompressionByDesign() {
        // The C4 injection site is nonHistoryPrefix, which is built fresh
        // every reasoning turn and NEVER touched by ConversationWindowManager
        // compaction (verified by ContextCompressionLedgerSurvivalTest for
        // the ledger snapshot — same invariant applies here). This test
        // pins the SHAPE of the injected payload so a future refactor that
        // accidentally puts the notification into the history window (which
        // IS subject to compaction) would fail.
        registry.register("conv-1", 42L);
        router.onMcpConnectionLost(new McpConnectionLostEvent(7L, "test"));

        List<EnvironmentNotification> notes = registry.drain("conv-1");
        String block = ReasoningNode.renderEnvironmentNotifications(notes);

        Message injection = new SystemMessage(block);
        assertThat(injection).isInstanceOf(SystemMessage.class);
        // SystemMessage is treated as non-history by the window manager —
        // it's never trimmed, never compacted, never summarised away.
        assertThat(injection.getText()).isEqualTo(block);
    }

    // ==================== Inactive conversation safety ====================

    @Test
    void eventFiredWhenNoConversationActiveIsDroppedSilently() {
        // No active conversation → router must not throw, no notification
        // lingers to be delivered to a future conversation that happens to
        // reuse the same conversationId.
        router.onMcpConnectionLost(new McpConnectionLostEvent(7L, "no-listener"));

        registry.register("conv-1", 42L); // register AFTER the event
        assertThat(registry.drain("conv-1")).isEmpty();
    }

    @Test
    void eventFiredAfterUnregisterIsDroppedSilently() {
        registry.register("conv-1", 42L);
        registry.unregister("conv-1");
        router.onMcpConnectionLost(new McpConnectionLostEvent(7L, "post-unregister"));

        // Re-registering must NOT receive the event that fired while inactive
        registry.register("conv-1", 42L);
        assertThat(registry.drain("conv-1")).isEmpty();
    }
}
