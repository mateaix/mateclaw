package vip.mate.approval.grant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.approval.grant.entity.ApprovalGrant;
import vip.mate.approval.grant.repository.ApprovalGrantMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service-layer operations on {@link ApprovalGrant}.
 * <p>
 * CRUD is handled via the {@link ApprovalGrantMapper} BaseMapper; this service
 * adds the small number of approval-domain operations that callers from outside
 * the controller need:
 *
 * <ul>
 *   <li>{@link #revokeConversationScopedGrants(String)} — used by the lifecycle
 *       listener (PR-2) on {@code ConversationDeletedEvent} to soft-revoke every
 *       {@code UNTIL_CONVERSATION_END} grant attached to that conversation.</li>
 *   <li>{@link #countActiveInWorkspace(Long)} — used by the {@code /api/v1/approval/grants/active}
 *       endpoint and the front-end pill / chip so they can show {@code (N)} without
 *       fetching every row.</li>
 *   <li>{@link #listActiveByScope(Long, String)} — generic listing for the
 *       management page, with the standard "not deleted, not revoked, not expired"
 *       filter applied uniformly.</li>
 * </ul>
 *
 * <p>CRUD validation (e.g. rejecting {@code max_severity=CRITICAL} or enforcing the
 * scope/tool_name authorization matrix in §2.4.5) lives in {@code ApprovalGrantController}
 * (PR-4), not here — the service stays low-policy so {@code ApprovalGrantResolver}
 * can call it without dragging REST concerns in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalGrantService {

    private final ApprovalGrantMapper grantMapper;

    /**
     * Soft-revokes every active {@code UNTIL_CONVERSATION_END} grant attached to the
     * conversation. Idempotent.
     */
    @Transactional
    public int revokeConversationScopedGrants(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return 0;
        }
        int revoked = grantMapper.revokeUntilConversationEnd(conversationId);
        if (revoked > 0) {
            log.info("[APPROVAL] Revoked {} UNTIL_CONVERSATION_END grant(s) on conversation delete: {}",
                    revoked, conversationId);
        }
        return revoked;
    }

    /** Counts active grants visible in the given workspace (drives the chip "(N)"). */
    public long countActiveInWorkspace(Long workspaceId) {
        if (workspaceId == null) return 0;
        return grantMapper.selectCount(
                Wrappers.<ApprovalGrant>lambdaQuery()
                        .eq(ApprovalGrant::getWorkspaceId, workspaceId)
                        .eq(ApprovalGrant::getRevoked, 0)
                        .eq(ApprovalGrant::getDeleted, 0)
                        .and(w -> w.isNull(ApprovalGrant::getExpireAt)
                                .or().gt(ApprovalGrant::getExpireAt, LocalDateTime.now()))
        );
    }

    /** Lists active grants in a workspace, optionally restricted to a scope type. */
    public List<ApprovalGrant> listActiveByScope(Long workspaceId, String scopeType) {
        if (workspaceId == null) return List.of();
        var wrapper = Wrappers.<ApprovalGrant>lambdaQuery()
                .eq(ApprovalGrant::getWorkspaceId, workspaceId)
                .eq(ApprovalGrant::getRevoked, 0)
                .eq(ApprovalGrant::getDeleted, 0)
                .and(w -> w.isNull(ApprovalGrant::getExpireAt)
                        .or().gt(ApprovalGrant::getExpireAt, LocalDateTime.now()))
                .orderByDesc(ApprovalGrant::getGrantedAt);
        if (scopeType != null && !scopeType.isEmpty()) {
            wrapper.eq(ApprovalGrant::getScopeType, scopeType);
        }
        return grantMapper.selectList(wrapper);
    }

    /** Soft-revokes a single grant. Caller must enforce ownership / admin (PR-4). */
    @Transactional
    public boolean revoke(Long grantId, Long revokedBy) {
        ApprovalGrant g = grantMapper.selectById(grantId);
        if (g == null || g.getRevoked() != null && g.getRevoked() == 1) {
            return false;
        }
        g.setRevoked(1);
        g.setRevokedBy(revokedBy);
        g.setRevokedAt(LocalDateTime.now());
        return grantMapper.updateById(g) > 0;
    }
}
