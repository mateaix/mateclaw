package vip.mate.tool.guard.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具安全审计日志实体
 */
@Data
@TableName("mate_tool_guard_audit_log")
public class ToolGuardAuditLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String conversationId;
    private String agentId;
    private String userId;
    private String channelType;
    private String toolName;
    private String toolParamsJson;
    private String decision;
    private String maxSeverity;
    private String findingsJson;
    private String pendingId;
    private String replayPayloadHash;

    /**
     * Auto-approve resolution outcome for NEEDS_APPROVAL invocations:
     * AUTO_GRANT / HARD_BLOCK / FORCE_HUMAN:&lt;pattern&gt; / SEVERITY_CRITICAL /
     * SEVERITY_CEILING:&lt;ceiling&gt;&lt;&lt;actual&gt; / UNKNOWN_WORKSPACE / NO_GRANT.
     * NULL when the invocation never reached the auto-grant decision layer.
     */
    private String autoApproveOutcome;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
