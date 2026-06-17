package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.channel.repository.ChannelSessionMapper;
import vip.mate.task.repository.AsyncTaskMapper;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.core.service.WorkspaceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pin the cross-workspace authorization guard added to
 * {@link ConversationService#isConversationOwner(String, String)} in response
 * to issue #344.
 *
 * <p>Pre-fix behavior: any logged-in user could reach a system / IM / webchat
 * -owned conversation by id, regardless of which workspace the conversation
 * lived in. List endpoints filtered by {@code workspaceId}, direct-access
 * endpoints did not — an asymmetry that becomes a cross-workspace breach once
 * workspaces are untrusted isolation boundaries.
 *
 * <p>Post-fix behavior: shared conversations are visible only to members of
 * their own workspace (plus global admins, plus the legacy escape hatches for
 * pre-workspace rows and anonymous permitAll reconnects).
 *
 * <p>Pure-Mockito (no Spring context) so the test stays fast and isolated.
 *
 * @author MateClaw Team
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceOwnershipWorkspaceTest {

    private static final String SYSTEM_CONV = "cron:daily-report";
    private static final String ALICE_CONV = "alice-uuid-1";
    private static final String WEBCHAT_CONV = "webchat:testkey1:vA";
    private static final long WS_TENANT_A = 10L;
    private static final long WS_TENANT_B = 20L;
    private static final long ALICE_USER_ID = 1001L;
    private static final long BOB_USER_ID = 1002L;
    private static final long ADMIN_USER_ID = 1003L;

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private AgentMapper agentMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private ToolApprovalMapper toolApprovalMapper;
    @Mock private AsyncTaskMapper asyncTaskMapper;
    @Mock private ChannelSessionMapper channelSessionMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuthService authService;
    @Mock private WorkspaceService workspaceService;

    @InjectMocks private ConversationService service;

    @BeforeEach
    void stubUsers() {
        // Lenient stubs — not every test needs both users (admin-only tests
        // would trip strict-stubbing otherwise).
        org.mockito.Mockito.lenient().when(authService.findByUsername("alice"))
                .thenReturn(user(ALICE_USER_ID, "user"));
        org.mockito.Mockito.lenient().when(authService.findByUsername("bob"))
                .thenReturn(user(BOB_USER_ID, "user"));
    }

    // ------------------------------------------------------------------
    // 1. Direct owner — no workspace check needed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("direct owner: always allowed, no membership lookup")
    void directOwnerShortCircuits() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(ALICE_CONV, "alice", WS_TENANT_A));

        assertThat(service.isConversationOwner(ALICE_CONV, "alice")).isTrue();

        // Workspace membership is NOT consulted — alice owns it, end of story.
        verify(workspaceService, never()).hasPermissionCached(anyLong(), anyLong(), anyString());
    }

    // ------------------------------------------------------------------
    // 2. System conv, same-workspace member → allowed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("system conv in requester's workspace: member passes")
    void systemConvSameWorkspaceMember() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(SYSTEM_CONV, "system", WS_TENANT_A));
        when(workspaceService.hasPermissionCached(WS_TENANT_A, ALICE_USER_ID, "viewer"))
                .thenReturn(true);

        assertThat(service.isConversationOwner(SYSTEM_CONV, "alice")).isTrue();
    }

    // ------------------------------------------------------------------
    // 3. System conv, cross-workspace user → DENIED (the #344 fix)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("system conv in another workspace: non-member rejected (#344)")
    void systemConvCrossWorkspaceRejected() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(SYSTEM_CONV, "system", WS_TENANT_A));
        // Bob is not a member of tenant A.
        when(workspaceService.hasPermissionCached(WS_TENANT_A, BOB_USER_ID, "viewer"))
                .thenReturn(false);

        assertThat(service.isConversationOwner(SYSTEM_CONV, "bob")).isFalse();
    }

    // ------------------------------------------------------------------
    // 4. Global admin bypass
    // ------------------------------------------------------------------

    @Test
    @DisplayName("system conv in another workspace: global admin bypasses")
    void systemConvAdminBypass() {
        when(authService.findByUsername("admin")).thenReturn(user(ADMIN_USER_ID, "admin"));
        when(conversationMapper.selectOne(any())).thenReturn(conv(SYSTEM_CONV, "system", WS_TENANT_A));

        // Admin skips the membership check entirely.
        assertThat(service.isConversationOwner(SYSTEM_CONV, "admin")).isTrue();
        verify(workspaceService, never()).hasPermissionCached(anyLong(), anyLong(), anyString());
    }

    // ------------------------------------------------------------------
    // 5. Anonymous / permitAll reconnect — user lookup returns null
    // ------------------------------------------------------------------

    @Test
    @DisplayName("system conv + null user record (anonymous reconnect): legacy behavior preserved")
    void anonymousReconnectLegacyFallback() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(SYSTEM_CONV, "system", WS_TENANT_A));
        when(authService.findByUsername("anonymous")).thenReturn(null);

        // Pre-fix: anonymous could see system convs. Preserve that.
        assertThat(service.isConversationOwner(SYSTEM_CONV, "anonymous")).isTrue();
        verify(workspaceService, never()).hasPermissionCached(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("anonymous reconnect to a non-system conv: still rejected")
    void anonymousReconnectNonSystemConv() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(ALICE_CONV, "alice", WS_TENANT_A));
        when(authService.findByUsername("anonymous")).thenReturn(null);

        assertThat(service.isConversationOwner(ALICE_CONV, "anonymous")).isFalse();
    }

    // ------------------------------------------------------------------
    // 6. Legacy rows without workspace_id — fall back to old logic
    // ------------------------------------------------------------------

    @Test
    @DisplayName("conv without workspace_id: legacy system-owner check, no membership lookup")
    void legacyConvWithoutWorkspace() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(SYSTEM_CONV, "system", null));

        assertThat(service.isConversationOwner(SYSTEM_CONV, "bob")).isTrue();
        verify(workspaceService, never()).hasPermissionCached(anyLong(), anyLong(), anyString());
    }

    // ------------------------------------------------------------------
    // 7. Webchat convs — already isolated; verify the fix doesn't open them
    // ------------------------------------------------------------------

    @Test
    @DisplayName("webchat conv: invisible to a JWT user even when they share the workspace")
    void webchatConvInvisibleToJwtUser() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(WEBCHAT_CONV, "webchat:vA", WS_TENANT_A));
        when(workspaceService.hasPermissionCached(WS_TENANT_A, ALICE_USER_ID, "viewer"))
                .thenReturn(true);

        // Alice is a member of the conv's workspace, but the conv is owned by
        // "webchat:vA" — not "system" — so the final OR-clause returns false.
        assertThat(service.isConversationOwner(WEBCHAT_CONV, "alice")).isFalse();
    }

    @Test
    @DisplayName("webchat conv: global admin can still reach it (consistent with system convs)")
    void webchatConvAdminBypass() {
        when(authService.findByUsername("admin")).thenReturn(user(ADMIN_USER_ID, "admin"));
        when(conversationMapper.selectOne(any())).thenReturn(conv(WEBCHAT_CONV, "webchat:vA", WS_TENANT_A));

        assertThat(service.isConversationOwner(WEBCHAT_CONV, "admin")).isTrue();
    }

    // ------------------------------------------------------------------
    // 8. Edge cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("conversation not found: false")
    void notFound() {
        when(conversationMapper.selectOne(any())).thenReturn(null);

        assertThat(service.isConversationOwner("missing", "alice")).isFalse();
    }

    @Test
    @DisplayName("system conv + same-workspace member that the workspace service lost track of: rejected")
    void systemConvMemberCacheMiss() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(SYSTEM_CONV, "system", WS_TENANT_A));
        // Membership cache returns false even though we'd expect this user to
        // be a member — defense in depth: when in doubt, deny.
        when(workspaceService.hasPermissionCached(eq(WS_TENANT_A), eq(ALICE_USER_ID), eq("viewer")))
                .thenReturn(false);

        assertThat(service.isConversationOwner(SYSTEM_CONV, "alice")).isFalse();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static UserEntity user(long id, String role) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setRole(role);
        return u;
    }

    private static ConversationEntity conv(String conversationId, String username, Long workspaceId) {
        ConversationEntity c = new ConversationEntity();
        c.setConversationId(conversationId);
        c.setUsername(username);
        c.setWorkspaceId(workspaceId);
        return c;
    }
}
