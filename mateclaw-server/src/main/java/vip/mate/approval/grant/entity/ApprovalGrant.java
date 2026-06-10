package vip.mate.approval.grant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Auto-approve grant entity.
 * <p>
 * Each row authorizes {@code ApprovalGrantResolver} to skip the manual approval
 * step for tool calls matching {@code (scope_type, scope_id, tool_name?, rule_id?)}
 * up to a {@code max_severity} ceiling. Hard-floor patterns still block irrespective
 * of any grant.
 */
@Data
@TableName("mate_approval_grant")
public class ApprovalGrant {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    /** USER | AGENT | CONVERSATION | WORKSPACE — see {@link ScopeType}. */
    private String scopeType;

    /** Snowflake string per CLAUDE.md precision convention. */
    private String scopeId;

    /** Null = any tool (only valid when granted by workspace admin with password confirmation). */
    private String toolName;

    /**
     * Matches the {@code String ruleId} on {@code GuardFinding}. Null = any rule
     * (grant applies to all findings under the severity ceiling).
     */
    private String ruleId;

    /** LOW | MEDIUM | HIGH. CRITICAL is rejected at API/UI; resolver never reaches a grant for it. */
    private String maxSeverity;

    /** ALWAYS | UNTIL_TIMESTAMP | UNTIL_CONVERSATION_END — see {@link GrantKind}. */
    private String grantKind;

    /** Only meaningful when {@code grantKind = UNTIL_TIMESTAMP}. */
    private LocalDateTime expireAt;

    private Long grantedBy;

    /**
     * Display name of the granter (nickname → username). Not persisted; the
     * controller fills it in after {@code selectPage} by batch-loading the
     * touched user ids so the UI doesn't need a separate /users call for a
     * snowflake → name lookup. Null when the user no longer exists.
     */
    @TableField(exist = false)
    private String grantedByName;

    private LocalDateTime grantedAt;

    private Integer revoked;

    private Long revokedBy;

    private LocalDateTime revokedAt;

    private String note;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;

    /** Allowed values for {@link #scopeType}; kept as constants to avoid string typos. */
    public static final class ScopeType {
        public static final String USER = "USER";
        public static final String AGENT = "AGENT";
        public static final String CONVERSATION = "CONVERSATION";
        public static final String WORKSPACE = "WORKSPACE";
        private ScopeType() {}
    }

    /** Allowed values for {@link #grantKind}. */
    public static final class GrantKind {
        public static final String ALWAYS = "ALWAYS";
        public static final String UNTIL_TIMESTAMP = "UNTIL_TIMESTAMP";
        public static final String UNTIL_CONVERSATION_END = "UNTIL_CONVERSATION_END";
        private GrantKind() {}
    }
}
