package vip.mate.workspace.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.service.WorkspaceService;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class WorkspaceControllerMembersAuthTest {

    @Test
    void listMembersRequiresViewerPermissionForNonGlobalAdmin() throws Exception {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService);
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setUsername("alice");
        user.setRole("user");
        when(authService.findByUsername("alice")).thenReturn(user);
        when(workspaceService.listMembers(7L)).thenReturn(List.of());

        invokeListMembers(controller, 7L, new TestingAuthenticationToken("alice", "pw"));

        verify(workspaceService).requirePermission(7L, 42L, "viewer");
        verify(workspaceService).listMembers(7L);
    }

    @Test
    void listMembersLetsGlobalAdminBypassWorkspaceMembership() throws Exception {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService);
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("admin");
        when(authService.findByUsername("admin")).thenReturn(user);
        when(workspaceService.listMembers(7L)).thenReturn(List.of());

        invokeListMembers(controller, 7L, new TestingAuthenticationToken("admin", "pw"));

        verify(workspaceService, never()).requirePermission(anyLong(), anyLong(), anyString());
        verify(workspaceService).listMembers(7L);
    }

    private void invokeListMembers(WorkspaceController controller, Long workspaceId,
                                   Authentication auth) throws Exception {
        Method method = WorkspaceController.class.getMethod("listMembers", Long.class, Authentication.class);
        assertNotNull(method);
        method.invoke(controller, workspaceId, auth);
    }
}
