package vip.mate.workspace.conversation.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mate.workspace.conversation.model.ConversationEntity;

import java.time.LocalDateTime;

/**
 * 会话视图对象（VO）
 * 在 ConversationEntity 基础上补充前端展示所需的关联字段
 * 对应前端 Sessions.vue 所需的 agentName / agentIcon / status / updateTime
 *
 * @author MateClaw Team
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationVO extends ConversationEntity {

    /**
     * 关联 Agent 名称（来自 mate_agent.name）
     */
    private String agentName;

    /**
     * 关联 Agent 图标（来自 mate_agent.icon）
     */
    private String agentIcon;

    /**
     * 会话状态：active（活跃）/ closed（已关闭）
     * 根据 lastActiveTime 距今是否超过 24 小时自动判断
     */
    private String status;

    /**
     * 流状态：idle（空闲）/ running（生成中）
     * 表示当前是否有正在进行的 SSE 流式输出
     */
    private String streamStatus;

    /**
     * 消息来源渠道：web / feishu / dingtalk / telegram / discord / wecom / qq / weixin / cron
     * 从 conversationId 前缀自动提取
     */
    private String source;

    /**
     * 工厂方法：从实体构建 VO，补充 agentName/agentIcon/status
     *
     * @param entity      会话实体
     * @param agentName   关联 Agent 名称（可为 null）
     * @param agentIcon   关联 Agent 图标（可为 null）
     * @return ConversationVO
     */
    public static ConversationVO from(ConversationEntity entity, String agentName, String agentIcon) {
        ConversationVO vo = new ConversationVO();
        // 复制实体字段
        vo.setId(entity.getId());
        vo.setConversationId(entity.getConversationId());
        vo.setTitle(entity.getTitle());
        vo.setAgentId(entity.getAgentId());
        vo.setUsername(entity.getUsername());
        vo.setMessageCount(entity.getMessageCount());
        vo.setLastMessage(entity.getLastMessage());
        vo.setLastActiveTime(entity.getLastActiveTime());
        vo.setWorkspaceId(entity.getWorkspaceId());
        vo.setPinned(entity.getPinned() != null ? entity.getPinned() : 0);
        vo.setModelProvider(entity.getModelProvider());
        vo.setModelName(entity.getModelName());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        // 补充关联字段
        vo.setAgentName(agentName != null ? agentName : "未知 Agent");
        vo.setAgentIcon(agentIcon != null ? agentIcon : "🤖");
        // 流状态
        vo.setStreamStatus(entity.getStreamStatus() != null ? entity.getStreamStatus() : "idle");
        // 计算状态：24 小时内活跃为 active，否则为 closed
        if (entity.getLastActiveTime() != null) {
            boolean isActive = entity.getLastActiveTime()
                    .isAfter(LocalDateTime.now().minusHours(24));
            vo.setStatus(isActive ? "active" : "closed");
        } else {
            vo.setStatus("closed");
        }
        // 从 conversationId 提取消息来源
        vo.setSource(extractSource(entity.getConversationId()));
        return vo;
    }

    private static String extractSource(String conversationId) {
        if (conversationId == null) return "web";
        // Underscore-prefixed cron buckets — use the cron icon for both.
        // tasks_<wsId> is the unified per-workspace cron output conversation
        // (CronConversationResolver.resolve for web-origin jobs). cron_<id>
        // is the legacy per-job orphan kept as the IM-cron fallback when
        // no channel session exists yet.
        if (conversationId.startsWith("tasks_") || conversationId.startsWith("cron_")) {
            return "cron";
        }
        int colonIdx = conversationId.indexOf(':');
        if (colonIdx <= 0) return "web";
        String prefix = conversationId.substring(0, colonIdx);
        return switch (prefix) {
            case "feishu", "dingtalk", "telegram", "discord", "wecom", "qq", "weixin" -> prefix;
            case "cron" -> "cron";
            default -> "web";
        };
    }
}
