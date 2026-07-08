package vip.mate.workspace.conversation.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_message")
public class MessageEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话唯一标识 */
    private String conversationId;

    /** 消息角色：user / assistant / system / tool */
    private String role;

    /** 消息内容 */
    @TableField(value = "content", updateStrategy = FieldStrategy.ALWAYS)
    private String content;

    /** 结构化内容片段（JSON） */
    @TableField(value = "content_parts", updateStrategy = FieldStrategy.ALWAYS)
    private String contentParts;

    /** 工具调用名称（role=tool 时使用） */
    private String toolName;

    /** Token 使用量 */
    private Integer tokenUsage;

    /** Prompt tokens 消耗 */
    private Integer promptTokens;

    /** Completion tokens 消耗 */
    private Integer completionTokens;

    /** Prompt cache 命中 tokens（provider 未上报时为 0） */
    private Integer cacheReadTokens;

    /** Prompt cache 写入 tokens（provider 未上报时为 0） */
    private Integer cacheWriteTokens;

    /** 思考（reasoning）阶段消耗的 completion tokens（provider 未上报时为 0） */
    private Integer reasoningTokens;

    /** 运行时模型名称 */
    private String runtimeModel;

    /** 运行时 Provider ID */
    private String runtimeProvider;

    /** 消息状态：generating / completed / stopped / failed */
    private String status;

    /** Agent 事件元数据（JSON）：toolCalls, plan, currentPhase, pendingApproval 等 */
    @TableField(value = "metadata", updateStrategy = FieldStrategy.ALWAYS)
    private String metadata;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
