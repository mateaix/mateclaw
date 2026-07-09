package vip.mate.agent.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.skill.event.SkillRemovedEvent;
import vip.mate.skill.event.SkillUpdatedEvent;
import vip.mate.tool.mcp.event.McpConnectionLostEvent;
import vip.mate.tool.mcp.event.McpServerChangedEvent;
import vip.mate.tool.mcp.event.McpServerRemovedEvent;

import java.time.Instant;

/**
 * Bridges MCP / skill environment events into the agent runtime by translating
 * each event into an {@link EnvironmentNotification} and broadcasting it to
 * every currently-running conversation via {@link RunningConversationRegistry}.
 *
 * <p>This is the Java-side half of "agent environment awareness": instead of
 * expecting the LLM to notice that a tool disappeared (it won't — the tool
 * list is a static snapshot taken at turn start), Java detects the change
 * here and injects a one-shot notification into the agent's next reasoning
 * turn. The LLM only has to read and obey the notification; it does not have
 * to probe or guess.
 *
 * <p><b>Broadcast vs. targeted:</b> we broadcast to all active conversations
 * rather than filtering by "which agent has tools from this server". The
 * filtering would require a DB lookup per event (agent_tool_binding rows),
 * and the cost of a stray notification to an unaffected conversation is just
 * one extra SystemMessage — the LLM is told to ignore notifications about
 * tools it isn't using (see the "Environment Change Notifications" section
 * in the system prompt).
 *
 * <p>Coexists with the existing {@code @EventListener} methods in
 * {@code AgentService} which call {@code refreshAllAgents()} — those handle
 * cache invalidation so the NEXT turn sees fresh state; this router handles
 * in-flight notification so the CURRENT turn can adapt.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnvironmentEventRouter {

    private final RunningConversationRegistry registry;

    @EventListener
    public void onMcpServerChanged(McpServerChangedEvent event) {
        broadcast("mcp-changed",
                "🔧 MCP 工具列表已变更（原因: " + event.reason()
                        + "）。请重新检查可用工具列表，避免调用已失效的工具名。"
                        + "如果之前用过的工具现在不在列表里，说明它已被移除或重命名。");
    }

    @EventListener
    public void onMcpConnectionLost(McpConnectionLostEvent event) {
        broadcast("mcp-lost",
                "⚠️ MCP 服务器连接丢失（serverId=" + event.serverId()
                        + "，原因: " + event.reason() + "）。"
                        + "该服务器下所有工具（前缀 mcp_" + event.serverId()
                        + "_）暂不可用。请改用其他工具，或向用户报告该能力暂时缺失。"
                        + "不要反复重试同一工具名。");
    }

    @EventListener
    public void onMcpServerRemoved(McpServerRemovedEvent event) {
        broadcast("mcp-removed",
                "❌ MCP 服务器已移除（serverName=" + event.serverName()
                        + "，serverId=" + event.serverId() + "）。"
                        + "其下所有工具已永久失效，不要再尝试调用前缀 mcp_" + event.serverId()
                        + "_ 的任何工具。请改用其他途径完成任务。");
    }

    @EventListener
    public void onSkillRemoved(SkillRemovedEvent event) {
        broadcast("skill-removed",
                "❌ Skill 已移除（" + event.skillName() + "）。"
                        + "如果之前加载过该 skill，其固定约束已失效；不要再尝试 load_skill 加载它。"
                        + "请基于剩余能力重新规划任务。");
    }

    @EventListener
    public void onSkillUpdated(SkillUpdatedEvent event) {
        String verb = switch (event.changeType() == null ? "update" : event.changeType()) {
            case "enable" -> "已启用";
            case "disable" -> "已禁用";
            case "rescan" -> "安全扫描结果已更新";
            default -> "已更新";
        };
        broadcast("skill-updated",
                "🔄 Skill " + event.skillName() + " " + verb + "。"
                        + "如果之前加载过该 skill，请重新调用 load_skill 刷新其约束；"
                        + "旧约束可能不再适用，继续按旧约束执行可能导致错误。");
    }

    // ==================== Internal ====================

    private void broadcast(String type, String message) {
        try {
            int active = registry.activeConversations().size();
            if (active == 0) {
                return; // no-one to notify — skip the allocation
            }
            EnvironmentNotification n = new EnvironmentNotification(type, message, Instant.now());
            registry.broadcast(n);
            log.info("[EnvironmentEventRouter] Broadcasted {} to {} active conversation(s)", type, active);
        } catch (Exception e) {
            // Never let an event-routing failure bubble into the Spring event bus.
            log.warn("[EnvironmentEventRouter] Failed to broadcast {}: {}", type, e.getMessage());
        }
    }
}
