package vip.mate.workspace.conversation.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_conversation")
public class ConversationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话唯一标识（前端生成的UUID） */
    private String conversationId;

    /** 会话标题（取第一条消息前20字） */
    private String title;

    /** 关联的 Agent ID */
    private Long agentId;

    /** 创建用户 */
    private String username;

    /** 消息数量 */
    private Integer messageCount;

    /** 最后一条消息摘要 */
    @TableField(value = "last_message", updateStrategy = FieldStrategy.ALWAYS)
    private String lastMessage;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveTime;

    /** 流状态：idle（空闲）/ running（生成中） */
    private String streamStatus;

    /** 所属工作区 ID（默认 1 = default） */
    private Long workspaceId;

    /** 父会话 ID（委派场景下，子会话记录其父会话的 conversationId） */
    private String parentConversationId;

    /** Pin flag: 0 = normal, 1 = pinned to the top of the sidebar list */
    private Integer pinned;

    /**
     * Provider id of the model this conversation is pinned to. NULL means
     * "inherit" — fall back to the agent's model override, then the global
     * default. Paired with {@link #modelName}.
     */
    private String modelProvider;

    /** Model id this conversation is pinned to. See {@link #modelProvider}. */
    private String modelName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
