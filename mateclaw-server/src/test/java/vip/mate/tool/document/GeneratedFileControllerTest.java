package vip.mate.tool.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.service.WorkspaceService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedFileControllerTest {

    @Test
    @DisplayName("download is forbidden when current workspace does not match file workspace")
    void forbiddenWhenWorkspaceDoesNotMatch(@TempDir Path dir) {
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        String id = cache.put("secret".getBytes(StandardCharsets.UTF_8), "b.txt", "text/plain",
                new GeneratedFileCache.Owner(20L, 30L, "conv-b"));
        AuthService authService = mock(AuthService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(authService.findByUsername("alice")).thenReturn(user(30L, "user"));

        GeneratedFileController controller = new GeneratedFileController(cache, authService, workspaceService);
        ResponseEntity<?> response = controller.download(id, 10L,
                new TestingAuthenticationToken("alice", "pw"));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    @DisplayName("download succeeds when current workspace matches and user can view it")
    void allowedWhenWorkspaceMatches(@TempDir Path dir) {
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        String id = cache.put("ok".getBytes(StandardCharsets.UTF_8), "b.txt", "text/plain",
                new GeneratedFileCache.Owner(20L, 30L, "conv-b"));
        AuthService authService = mock(AuthService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(authService.findByUsername("alice")).thenReturn(user(30L, "user"));
        when(workspaceService.hasPermissionCached(20L, 30L, "viewer")).thenReturn(true);

        GeneratedFileController controller = new GeneratedFileController(cache, authService, workspaceService);
        ResponseEntity<?> response = controller.download(id, 20L,
                new TestingAuthenticationToken("alice", "pw"));

        assertEquals(200, response.getStatusCode().value());
    }

    private static UserEntity user(Long id, String role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername("alice");
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }
}
