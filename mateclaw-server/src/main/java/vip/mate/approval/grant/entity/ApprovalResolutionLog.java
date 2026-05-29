package vip.mate.approval.grant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Final decision log written by the approval layer (one row per resolved invocation).
 * <p>
 * Decoupled from {@code mate_tool_guard_audit_log} (which records guard evaluation
 * facts). Dashboard decision-source percentages read from this table only, so the
 * counts stay clean even when an invocation produces both an evaluation row and a
 * resolution row.
 */
@Data
@TableName("mate_approval_resolution_log")
public class ApprovalResolutionLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * Nullable: a {@code HARD_BLOCK} event can be recorded before the workspace
     * has been resolved (missing/deleted conversation, malformed context). Other
     * decision sources ({@code USER_MANUAL}, {@code AUTO_GRANT}, {@code TIMEOUT})
     * always have a known workspace by the time they reach this table.
     */
    private Long workspaceId;

    private String conversationId;

    private String agentId;

    private String userId;

    /** Correlates to {@code AssistantMessage.ToolCall.id} when available; nullable. */
    private String toolCallId;

    private String toolName;

    private String maxSeverity;

    /** Comma-joined list of GuardFinding ruleIds present at decision time. */
    private String ruleIds;

    /** USER_MANUAL | AUTO_GRANT | HARD_BLOCK | TIMEOUT — see {@link DecisionSource}. */
    private String decisionSource;

    /** Non-null when {@code decisionSource = AUTO_GRANT}. */
    private Long grantId;

    /** Non-null when the path went through {@code ApprovalWorkflowService.createPending()}. */
    private String pendingId;

    /** First 500 chars of rawArguments. WARN log prints 200; this stores more for the detail page. */
    private String argsPreview;

    private String note;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    private Integer deleted;

    /** Allowed values for {@link #decisionSource}. */
    public static final class DecisionSource {
        public static final String USER_MANUAL = "USER_MANUAL";
        public static final String AUTO_GRANT = "AUTO_GRANT";
        public static final String HARD_BLOCK = "HARD_BLOCK";
        public static final String TIMEOUT = "TIMEOUT";
        private DecisionSource() {}
    }
}
