package vip.mate.channel.webchat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent registry of visitors whose {@code visitorToken} HMAC is no longer
 * accepted on management endpoints (list/messages/title/delete/stop/upload/
 * regenerate). Created by V148. The unique constraint on
 * {@code (channel_id, visitor_id, deleted)} makes re-revoke idempotent;
 * setting {@code deleted = 1} un-revokes.
 * <p>
 * {@code POST /stream} is intentionally NOT bound by this — a revoked visitor
 * can still start a fresh {@code /stream}, which mints a new token; the
 * revocation applies to the old token presented on management endpoints.
 *
 * @author MateClaw Team
 */
@Data
@TableName("webchat_revoked_visitor")
public class WebChatRevokedVisitorEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long channelId;

    /** Visitor identifier in the same charset as {@code WebChatController.normalizeVisitorId}. */
    private String visitorId;

    private LocalDateTime revokedAt;

    /** Free-form reason (admin-supplied). Nullable. */
    private String reason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 0 = active revocation; 1 = un-revoked (tombstoned). */
    private Integer deleted;
}
