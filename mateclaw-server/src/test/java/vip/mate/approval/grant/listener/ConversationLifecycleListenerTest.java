package vip.mate.approval.grant.listener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.approval.grant.WorkspaceLookupCache;
import vip.mate.approval.grant.service.ApprovalGrantService;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConversationLifecycleListener}.
 */
@ExtendWith(MockitoExtension.class)
class ConversationLifecycleListenerTest {

    @Mock ApprovalGrantService grantService;
    @Mock WorkspaceLookupCache workspaceLookupCache;

    @InjectMocks
    ConversationLifecycleListener listener;

    @Test
    void delete_revokes_grants_and_invalidates_cache() {
        when(grantService.revokeConversationScopedGrants("conv-1")).thenReturn(2);

        listener.onConversationDeleted(new ConversationDeletedEvent("conv-1"));

        verify(grantService).revokeConversationScopedGrants("conv-1");
        verify(workspaceLookupCache).invalidate("conv-1");
    }

    @Test
    void delete_with_no_active_grants_still_invalidates_cache() {
        when(grantService.revokeConversationScopedGrants("conv-2")).thenReturn(0);

        listener.onConversationDeleted(new ConversationDeletedEvent("conv-2"));

        verify(workspaceLookupCache).invalidate("conv-2");
    }

    @Test
    void grant_service_failure_still_invalidates_cache() {
        // A stale workspace mapping is more dangerous than a missed revoke
        // (the grant can no longer match its conversation anyway), so the
        // finally-block invalidation runs even if revocation throws.
        when(grantService.revokeConversationScopedGrants("conv-3"))
                .thenThrow(new RuntimeException("DB down"));

        listener.onConversationDeleted(new ConversationDeletedEvent("conv-3"));

        verify(workspaceLookupCache).invalidate("conv-3");
    }

    @Test
    void blank_or_null_conversation_id_is_no_op() {
        listener.onConversationDeleted(new ConversationDeletedEvent(""));
        listener.onConversationDeleted(new ConversationDeletedEvent(null));

        verify(grantService, never()).revokeConversationScopedGrants(eq(""));
        verify(workspaceLookupCache, never()).invalidate(eq(""));
    }
}
