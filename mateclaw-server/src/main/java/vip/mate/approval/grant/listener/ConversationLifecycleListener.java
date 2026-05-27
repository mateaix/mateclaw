package vip.mate.approval.grant.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.approval.grant.WorkspaceLookupCache;
import vip.mate.approval.grant.service.ApprovalGrantService;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;

/**
 * Tears down conversation-scoped state in the auto-grant subsystem when a
 * conversation is deleted.
 * <p>
 * Two actions, run in order:
 * <ol>
 *   <li>Soft-revoke every {@code UNTIL_CONVERSATION_END} grant whose
 *       {@code scope_type = CONVERSATION} and {@code scope_id = conversationId}.
 *       Without this, the grant would linger as an apparently-active row that
 *       can never match again (its scope no longer exists), but still shows up
 *       in the management page and the chip {@code (N)} counter.</li>
 *   <li>Drop the {@code conversationId → workspaceId} entry from
 *       {@link WorkspaceLookupCache}. A re-created conversation with the same
 *       id (rare but possible across a backup restore) would otherwise inherit
 *       the stale mapping for up to five minutes.</li>
 * </ol>
 *
 * <p>{@link ConversationDeletedEvent} is published <i>after</i> the delete tx
 * commits, so this listener runs in a clean tx and the soft-revoke either
 * succeeds or fails in isolation — it cannot poison the delete itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationLifecycleListener {

    private final ApprovalGrantService grantService;
    private final WorkspaceLookupCache workspaceLookupCache;

    @EventListener
    public void onConversationDeleted(ConversationDeletedEvent event) {
        String conversationId = event.conversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        try {
            int revoked = grantService.revokeConversationScopedGrants(conversationId);
            if (revoked > 0) {
                log.info("[APPROVAL] ConversationLifecycleListener: revoked {} UNTIL_CONVERSATION_END grant(s) for {}",
                        revoked, conversationId);
            }
        } catch (Exception e) {
            log.warn("[APPROVAL] ConversationLifecycleListener: failed to revoke grants for {}: {}",
                    conversationId, e.getMessage());
        } finally {
            // Always invalidate the cache, even if grant revocation threw: a stale
            // workspace mapping is more dangerous than a missed revoke (the grant
            // can no longer match its conversation anyway).
            workspaceLookupCache.invalidate(conversationId);
        }
    }
}
