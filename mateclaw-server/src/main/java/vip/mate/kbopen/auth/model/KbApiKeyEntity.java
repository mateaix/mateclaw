package vip.mate.kbopen.auth.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Knowledge Base Open API Key entity.
 *
 * <p>Plaintext keys are never persisted; only the SHA-256 hash lives in
 * {@link #tokenHash}. The {@code prefix} column stores the first 4 chars of
 * the plaintext purely for UI display ({@code "mck_ab****"}) — it does not
 * compromise security because the hash is not reversible.
 */
@Data
@TableName("mate_kb_api_key")
public class KbApiKeyEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** SHA-256 hex of the plaintext, lowercase. UNIQUE indexed for O(1) auth. */
    @JsonIgnore
    private String tokenHash;

    /** First 4 chars of plaintext, for UI display only. */
    private String prefix;

    /** Owning workspace — the isolation boundary. */
    private Long workspaceId;

    /** User who created the key. */
    private Long createdBy;

    /** Comma-separated scope tokens, e.g. "kb:search,kb:read". Null/empty = kb:*. */
    private String scopes;

    private Boolean enabled;

    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

    /** Per-key rate limit, enforced by the auth filter (R2). */
    private Integer rateLimitPerMin;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
