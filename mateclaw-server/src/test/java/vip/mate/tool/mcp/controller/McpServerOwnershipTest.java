package vip.mate.tool.mcp.controller;

import org.junit.jupiter.api.Test;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.tool.disclosure.ToolDisclosureService;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.service.McpServerService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpServerOwnershipTest {
    final McpServerService service = mock(McpServerService.class);
    final AuthService auth = mock(AuthService.class);
    final McpServerController controller = new McpServerController(service, mock(ToolDisclosureService.class), auth);
    final UsernamePasswordAuthenticationToken user = new UsernamePasswordAuthenticationToken("member", "");

    McpServerEntity server(Long workspace) {
        McpServerEntity entity = new McpServerEntity();
        entity.setId(7L);
        entity.setWorkspaceId(workspace);
        return entity;
    }

    @Test void foreignDetailAndToolsAreDeniedBeforeSecretsOrToolsAreRead() {
        when(service.getById(7L)).thenReturn(server(2L));
        assertThrows(MateClawException.class, () -> controller.get(7L, 1L));
        assertThrows(MateClawException.class, () -> controller.listTools(7L, 1L));
        verify(service, never()).listToolsByServer(anyLong());
        verify(service, never()).sanitize(any());
    }

    @Test void foreignMutationAndConnectionProbeAreDenied() {
        when(service.getById(7L)).thenReturn(server(2L));
        assertThrows(MateClawException.class, () -> controller.update(7L, server(1L), 1L, null, user));
        assertThrows(MateClawException.class, () -> controller.delete(7L, 1L, user));
        assertThrows(MateClawException.class, () -> controller.toggle(7L, false, 1L, user));
        assertThrows(MateClawException.class, () -> controller.test(7L, 1L, user));
        assertThrows(MateClawException.class, () -> controller.setDisclosureTier(7L, Map.of("tier", "core"), 1L, user));
        verify(service, never()).testConnectionById(anyLong());
        verify(service, never()).toggle(anyLong(), anyBoolean());
    }

    @Test void forgedOwnershipOnUpdateRequiresExplicitMigration() {
        when(service.getById(7L)).thenReturn(server(1L));
        assertThrows(MateClawException.class, () -> controller.update(7L, server(2L), 1L, null, user));
        verify(service, never()).update(anyLong(), any(), any());
    }

    @Test void createUsesRequestWorkspaceInsteadOfForgedBody() {
        McpServerEntity body = server(2L);
        controller.create(body, 1L, null, user);
        assertEquals(1L, body.getWorkspaceId());
        verify(service).create(body);
    }

    @Test void workspaceAdminCannotMutateGlobalOrMigrateOwnership() {
        UserEntity member = new UserEntity(); member.setRole("user");
        when(auth.findByUsername("member")).thenReturn(member);
        when(service.getById(7L)).thenReturn(server(null));
        assertThrows(MateClawException.class, () -> controller.delete(7L, 1L, user));
        when(service.getById(7L)).thenReturn(server(1L));
        assertThrows(MateClawException.class, () -> controller.update(7L, server(null), 1L, true, user));
        verify(service, never()).delete(anyLong());
        verify(service, never()).update(anyLong(), any(), any());
    }

    @Test void omittedUpdateOwnershipPreservesGlobalAndSystemAdminCanMigrate() {
        UserEntity admin = new UserEntity(); admin.setRole("admin");
        when(auth.findByUsername("member")).thenReturn(admin);
        when(service.getById(7L)).thenReturn(server(null));
        McpServerEntity body = server(null);
        controller.update(7L, body, 1L, null, user);
        assertNull(body.getWorkspaceId());
        controller.update(7L, body, 1L, false, user);
        assertEquals(1L, body.getWorkspaceId());
    }
}
