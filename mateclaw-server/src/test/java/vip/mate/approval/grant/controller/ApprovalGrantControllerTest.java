package vip.mate.approval.grant.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.approval.grant.entity.ApprovalGrant;
import vip.mate.approval.grant.repository.ApprovalGrantMapper;
import vip.mate.approval.grant.repository.ApprovalResolutionLogMapper;
import vip.mate.approval.grant.service.ApprovalGrantService;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.service.WorkspaceService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain controller tests for {@link ApprovalGrantController} — matches the
 * mateclaw house style (see {@code WikiHotCacheControllerTest},
 * {@code WorkflowControllerTest}): no MockMvc, no Spring boot context, just
 * direct method calls with mocked dependencies.
 * <p>
 * The {@code @RequireWorkspaceRole("member")} HTTP gate is enforced by the
 * shared {@code WorkspaceAccessInterceptor} and is exercised by its own tests;
 * here we cover the in-method §2.4.5 6-cell matrix and the password second
 * factor.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalGrantControllerTest {

    private static final long WORKSPACE_ID = 100L;
    private static final long MEMBER_ID = 1001L;
    private static final long ADMIN_ID = 2002L;
    private static final long AGENT_ID = 3003L;

    @Mock ApprovalGrantService grantService;
    @Mock AgentMapper agentMapper;
    @Mock ApprovalGrantMapper grantMapper;
    @Mock ApprovalResolutionLogMapper resolutionMapper;
    @Mock AuthService authService;
    @Mock WorkspaceService workspaceService;

    @InjectMocks
    ApprovalGrantController controller;

    private Authentication memberAuth;
    private Authentication adminAuth;

    @BeforeEach
    void setUp() {
        memberAuth = new UsernamePasswordAuthenticationToken("member-user", null);
        adminAuth = new UsernamePasswordAuthenticationToken("admin-user", null);

        UserEntity member = new UserEntity();
        member.setId(MEMBER_ID);
        member.setUsername("member-user");
        UserEntity admin = new UserEntity();
        admin.setId(ADMIN_ID);
        admin.setUsername("admin-user");
        // Lenient: each test only uses one of the two users; strict mode would
        // flag the unused one. Using lenient here keeps setUp shared.
        lenient().when(authService.findByUsername("member-user")).thenReturn(member);
        lenient().when(authService.findByUsername("admin-user")).thenReturn(admin);

        // AGENT-scope creation now verifies the target agent exists; most tests
        // use AGENT_ID as a valid reference. Lenient: non-AGENT tests never hit it.
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        lenient().when(agentMapper.selectById(AGENT_ID)).thenReturn(agent);
    }

    @Nested
    class CreateAuthorizationMatrix {

        @Test
        void conversation_scope_any_member_can_create() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("CONVERSATION", "conv-1", "read_file");

            controller.create(body, WORKSPACE_ID, memberAuth);

            // No admin check, no password check; grant inserted.
            verify(workspaceService, never()).requirePermission(anyLong(), anyLong(), anyString());
            verify(authService, never()).verifyCurrentUserPassword(anyLong(), anyString());
            verify(grantMapper).insert(any(ApprovalGrant.class));
        }

        @Test
        void user_scope_only_targets_self() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("USER", "9999", "read_file");

            assertThatThrownBy(() -> controller.create(body, WORKSPACE_ID, memberAuth))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("USER-scope");
            verify(grantMapper, never()).insert(any(ApprovalGrant.class));
        }

        @Test
        void user_scope_self_succeeds() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("USER",
                    String.valueOf(MEMBER_ID), "read_file");

            controller.create(body, WORKSPACE_ID, memberAuth);

            verify(grantMapper).insert(any(ApprovalGrant.class));
        }

        @Test
        void agent_scope_explicit_tool_requires_admin() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("AGENT", String.valueOf(AGENT_ID), "read_file");
            doThrow(new MateClawException("err.workspace.insufficient_permission", 403, "admin required"))
                    .when(workspaceService).requirePermission(WORKSPACE_ID, MEMBER_ID, "admin");

            assertThatThrownBy(() -> controller.create(body, WORKSPACE_ID, memberAuth))
                    .isInstanceOf(MateClawException.class);
        }

        @Test
        void agent_scope_null_tool_requires_admin_plus_password() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("AGENT", String.valueOf(AGENT_ID), null);
            // admin true; missing password → 403
            when(workspaceService.hasPermission(WORKSPACE_ID, ADMIN_ID, "admin")).thenReturn(true);
            body.password = null;

            assertThatThrownBy(() -> controller.create(body, WORKSPACE_ID, adminAuth))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("password");
        }

        @Test
        void agent_scope_null_tool_admin_with_password_passes() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("AGENT", String.valueOf(AGENT_ID), null);
            body.password = "correct-password";
            when(workspaceService.hasPermission(WORKSPACE_ID, ADMIN_ID, "admin")).thenReturn(true);
            // verifyCurrentUserPassword passes silently when correct.

            controller.create(body, WORKSPACE_ID, adminAuth);

            verify(authService).verifyCurrentUserPassword(ADMIN_ID, "correct-password");
            verify(grantMapper).insert(any(ApprovalGrant.class));
        }

        @Test
        void workspace_scope_explicit_tool_requires_admin_only() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("WORKSPACE",
                    String.valueOf(WORKSPACE_ID), "read_file");
            doThrow(new MateClawException("err.workspace.insufficient_permission", 403, "admin required"))
                    .when(workspaceService).requirePermission(WORKSPACE_ID, MEMBER_ID, "admin");

            assertThatThrownBy(() -> controller.create(body, WORKSPACE_ID, memberAuth))
                    .isInstanceOf(MateClawException.class);
        }

        @Test
        void workspace_scope_null_tool_requires_admin_plus_password_red_button() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("WORKSPACE",
                    String.valueOf(WORKSPACE_ID), null);
            body.password = "correct-password";
            when(workspaceService.hasPermission(WORKSPACE_ID, ADMIN_ID, "admin")).thenReturn(true);

            controller.create(body, WORKSPACE_ID, adminAuth);

            verify(workspaceService).requirePermission(WORKSPACE_ID, ADMIN_ID, "admin");
            verify(authService).verifyCurrentUserPassword(ADMIN_ID, "correct-password");
            verify(grantMapper).insert(any(ApprovalGrant.class));
        }

        @Test
        void critical_severity_is_rejected() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("CONVERSATION", "conv-1", "read_file");
            body.maxSeverity = "CRITICAL";

            assertThatThrownBy(() -> controller.create(body, WORKSPACE_ID, memberAuth))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("CRITICAL is not auto-approvable");
        }

        @Test
        void workspace_scope_id_must_match_current_workspace() {
            // A WORKSPACE-scope grant whose scopeId is a different workspace can
            // never match at runtime (tenant column + scope match both fail) —
            // rejected at creation as a dead configuration.
            ApprovalGrantController.CreateGrantRequest body = baseBody("WORKSPACE",
                    "2079860736482390017", "read_file");

            assertThatThrownBy(() -> controller.create(body, WORKSPACE_ID, adminAuth))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("must equal the current workspace id");
            verify(grantMapper, never()).insert(any(ApprovalGrant.class));
        }

        @Test
        void agent_scope_nonexistent_agent_is_rejected() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("AGENT", "424242", "read_file");
            when(agentMapper.selectById(424242L)).thenReturn(null);

            assertThatThrownBy(() -> controller.create(body, WORKSPACE_ID, adminAuth))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("does not reference an existing agent");
            verify(grantMapper, never()).insert(any(ApprovalGrant.class));
        }

        @Test
        void agent_scope_non_numeric_id_is_rejected() {
            // e.g. a conversation id or workspace name pasted into the AGENT scope.
            ApprovalGrantController.CreateGrantRequest body = baseBody("AGENT", "wecom:xxx", "read_file");

            assertThatThrownBy(() -> controller.create(body, WORKSPACE_ID, adminAuth))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("must be a numeric agent id");
            verify(grantMapper, never()).insert(any(ApprovalGrant.class));
        }

        @Test
        void until_conversation_end_requires_conversation_scope() {
            ApprovalGrantController.CreateGrantRequest body = baseBody("AGENT", String.valueOf(AGENT_ID), "read_file");
            body.grantKind = "UNTIL_CONVERSATION_END";

            assertThatThrownBy(() -> controller.create(body, WORKSPACE_ID, memberAuth))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("UNTIL_CONVERSATION_END");
        }
    }

    @Nested
    class ListRevoke {

        @Test
        void list_mine_does_not_require_admin() {
            // selectPage returns a Page object; the test only cares about the auth
            // path, so the mapper stub just needs to not NPE.
            when(grantMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

            controller.list(null, null, null, /*mine*/ true, 1L, 20L, WORKSPACE_ID, memberAuth);

            verify(workspaceService, never()).requirePermission(anyLong(), anyLong(), anyString());
        }

        @Test
        void list_all_requires_admin() {
            doThrow(new MateClawException("err.workspace.insufficient_permission", 403, ""))
                    .when(workspaceService).requirePermission(WORKSPACE_ID, MEMBER_ID, "admin");

            assertThatThrownBy(() ->
                    controller.list(null, null, null, /*mine*/ false, 1L, 20L, WORKSPACE_ID, memberAuth))
                    .isInstanceOf(MateClawException.class);
        }

        @Test
        void revoke_owner_succeeds_without_admin() {
            ApprovalGrant g = newGrant(123L, MEMBER_ID);
            when(grantMapper.selectById(123L)).thenReturn(g);
            when(workspaceService.hasPermission(anyLong(), anyLong(), anyString())).thenReturn(false);
            when(grantService.revoke(eq(123L), eq(MEMBER_ID))).thenReturn(true);

            controller.revoke(123L, WORKSPACE_ID, memberAuth);

            verify(grantService).revoke(123L, MEMBER_ID);
        }

        @Test
        void revoke_non_owner_non_admin_forbidden() {
            ApprovalGrant g = newGrant(123L, /* grantedBy */ 5555L);
            when(grantMapper.selectById(123L)).thenReturn(g);
            when(workspaceService.hasPermission(WORKSPACE_ID, MEMBER_ID, "admin")).thenReturn(false);

            assertThatThrownBy(() -> controller.revoke(123L, WORKSPACE_ID, memberAuth))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("only the grant owner or a workspace admin");
            verify(grantService, never()).revoke(anyLong(), anyLong());
        }

        @Test
        void revoke_admin_succeeds_for_other_users_grant() {
            ApprovalGrant g = newGrant(123L, /* grantedBy */ 5555L);
            when(grantMapper.selectById(123L)).thenReturn(g);
            when(workspaceService.hasPermission(WORKSPACE_ID, ADMIN_ID, "admin")).thenReturn(true);
            when(grantService.revoke(eq(123L), eq(ADMIN_ID))).thenReturn(true);

            controller.revoke(123L, WORKSPACE_ID, adminAuth);

            verify(grantService).revoke(123L, ADMIN_ID);
        }

        @Test
        void revoke_cross_workspace_returns_not_found() {
            ApprovalGrant g = newGrant(123L, MEMBER_ID);
            g.setWorkspaceId(999L); // different workspace
            when(grantMapper.selectById(123L)).thenReturn(g);

            assertThatThrownBy(() -> controller.revoke(123L, WORKSPACE_ID, memberAuth))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("grant not found");
        }
    }

    @Nested
    class Resolutions {

        @Test
        void grant_id_query_requires_admin() {
            doThrow(new MateClawException("err.workspace.insufficient_permission", 403, ""))
                    .when(workspaceService).requirePermission(WORKSPACE_ID, MEMBER_ID, "admin");

            assertThatThrownBy(() ->
                    controller.listResolutions(7777L, null, 100, WORKSPACE_ID, memberAuth))
                    .isInstanceOf(MateClawException.class);
        }

        @Test
        void conversation_query_does_not_require_admin() {
            when(resolutionMapper.selectList(any())).thenReturn(List.of());

            controller.listResolutions(null, "conv-1", 100, WORKSPACE_ID, memberAuth);

            verify(workspaceService, never()).requirePermission(anyLong(), anyLong(), anyString());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static ApprovalGrantController.CreateGrantRequest baseBody(
            String scopeType, String scopeId, String toolName) {
        ApprovalGrantController.CreateGrantRequest b = new ApprovalGrantController.CreateGrantRequest();
        b.scopeType = scopeType;
        b.scopeId = scopeId;
        b.toolName = toolName;
        b.ruleId = null;
        b.maxSeverity = "MEDIUM";
        b.grantKind = "ALWAYS";
        b.note = "test";
        return b;
    }

    private static ApprovalGrant newGrant(long id, long grantedBy) {
        ApprovalGrant g = new ApprovalGrant();
        g.setId(id);
        g.setWorkspaceId(WORKSPACE_ID);
        g.setScopeType("CONVERSATION");
        g.setScopeId("conv-1");
        g.setToolName("read_file");
        g.setMaxSeverity("MEDIUM");
        g.setGrantKind("ALWAYS");
        g.setGrantedBy(grantedBy);
        g.setGrantedAt(LocalDateTime.now());
        g.setRevoked(0);
        g.setDeleted(0);
        return g;
    }
}
