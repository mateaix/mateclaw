package vip.mate.tool.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.service.WorkspaceService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
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

    @ParameterizedTest
    @CsvSource({"image/svg+xml,inline", "text/html,inline", "image/png,inline", "text/plain,attachment"})
    void generatedContentHasSandboxPolicyWithoutChangingBytesOrDisposition(String mime, String disposition,
                                                                          @TempDir Path dir) {
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        byte[] payload = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>window.generatedScriptRan=true</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        String id = cache.put(payload, "report", mime, new GeneratedFileCache.Owner(20L, 30L, "conv"));
        AuthService authService = mock(AuthService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(authService.findByUsername("alice")).thenReturn(user(30L, "user"));
        when(workspaceService.hasPermissionCached(20L, 30L, "viewer")).thenReturn(true);
        var response = new GeneratedFileController(cache, authService, workspaceService)
                .download(id, 20L, new TestingAuthenticationToken("alice", "pw"));
        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(payload, (byte[]) response.getBody());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").startsWith(disposition + ";"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        String policy = response.getHeaders().getFirst("Content-Security-Policy");
        assertNotNull(policy);
        assertTrue(policy.contains("sandbox allow-downloads;"));
        assertTrue(policy.contains("default-src 'none';"));
        assertTrue(policy.contains("form-action 'none'"));
        assertFalse(policy.contains("allow-scripts"));
        assertFalse(policy.contains("allow-same-origin"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coldDownloadAuthorizesBeforePopulatingContentCache(boolean allowed, @TempDir Path dir) {
        String id = new GeneratedFileCache(dir).put("body".getBytes(StandardCharsets.UTF_8), "report.txt", "text/plain",
                new GeneratedFileCache.Owner(20L, 30L, "conv"));
        GeneratedFileCache cold = new GeneratedFileCache(dir);
        Map<?, ?> entries = (Map<?, ?>) ReflectionTestUtils.getField(cold, "entries");
        AuthService authService = mock(AuthService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(authService.findByUsername("alice")).thenReturn(user(30L, "user"));
        when(workspaceService.hasPermissionCached(20L, 30L, "viewer")).thenAnswer(call -> {
            assertTrue(entries.isEmpty(), "content must not be loaded into cache before authorization");
            return allowed;
        });
        var response = new GeneratedFileController(cold, authService, workspaceService)
                .download(id, 20L, new TestingAuthenticationToken("alice", "pw"));
        assertEquals(allowed ? 200 : 403, response.getStatusCode().value());
        assertEquals(allowed, entries.containsKey(id));
    }

    @Test
    void coldDownloadRejectsOwnershipReplacementDuringPermissionCheck(@TempDir Path dir) {
        String id = new GeneratedFileCache(dir).put("body".getBytes(StandardCharsets.UTF_8), "report.txt", "text/plain",
                new GeneratedFileCache.Owner(20L, 30L, "conv"));
        GeneratedFileCache cold = new GeneratedFileCache(dir);
        AuthService authService = mock(AuthService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(authService.findByUsername("alice")).thenReturn(user(30L, "user"));
        when(workspaceService.hasPermissionCached(20L, 30L, "viewer")).thenAnswer(call -> {
            Path meta = dir.resolve(id + ".meta");
            String[] fields = Files.readString(meta).split("\t", -1);
            fields[3] = "40";
            Files.writeString(meta, String.join("\t", fields));
            Files.writeString(dir.resolve(id), "other workspace body");
            return true;
        });
        var response = new GeneratedFileController(cold, authService, workspaceService)
                .download(id, 20L, new TestingAuthenticationToken("alice", "pw"));
        assertEquals(404, response.getStatusCode().value());
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(cold, "entries")).isEmpty());
    }

    @Test
    void legacyUnscopedColdFileRemainsAccessibleToAuthenticatedCaller(@TempDir Path dir) {
        String id = new GeneratedFileCache(dir).put("legacy".getBytes(StandardCharsets.UTF_8), "report.txt", "text/plain");
        GeneratedFileCache cold = new GeneratedFileCache(dir);
        AuthService authService = mock(AuthService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(authService.findByUsername("alice")).thenReturn(user(30L, "user"));
        var controller = new GeneratedFileController(cold, authService, workspaceService);
        assertEquals(401, controller.download(id, null, null).getStatusCode().value());
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(cold, "entries")).isEmpty());
        assertEquals(200, controller.download(id, null, new TestingAuthenticationToken("alice", "pw")).getStatusCode().value());
        org.mockito.Mockito.verifyNoInteractions(workspaceService);
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
