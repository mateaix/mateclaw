package vip.mate.tool.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.service.WorkspaceService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Serves bytes produced by tools and stashed in {@link GeneratedFileCache}.
 *
 * <p>Entries expire after {@link GeneratedFileCache#TTL}. Web downloads require
 * an authenticated caller in the same current workspace as the generated file.
 */
@Tag(name = "Generated Files")
@RestController
@RequestMapping("/api/v1/files/generated")
@RequiredArgsConstructor
public class GeneratedFileController {

    private final GeneratedFileCache cache;
    private final AuthService authService;
    private final WorkspaceService workspaceService;

    @Operation(summary = "Download a tool-generated file by its one-time id")
    @GetMapping("/{id}")
    public ResponseEntity<?> download(@PathVariable String id,
                                      @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
                                      Authentication authentication) {
        UserEntity user = resolveUser(authentication);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        var access = cache.getAuthorized(id, owner -> canDownload(owner.workspaceId(), workspaceId, user));
        if (access.status() == GeneratedFileCache.AccessStatus.FORBIDDEN) {
            return ResponseEntity.status(403).body(Map.of("error", "Workspace permission denied"));
        }
        if (access.status() == GeneratedFileCache.AccessStatus.MISSING) {
            return ResponseEntity.status(404).body(Map.of("error", "File not found or expired"));
        }
        var entry = access.entry();
        String encodedName = URLEncoder.encode(entry.filename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        String mime = entry.mimeType() == null || entry.mimeType().isBlank()
                ? "application/octet-stream"
                : entry.mimeType();
        headers.setContentType(MediaType.parseMediaType(mime));
        // RFC 5987 filename* lets non-ASCII names round-trip in browsers.
        // Images and HTML previews render inline; everything else downloads.
        boolean isImage = mime != null && mime.startsWith("image/");
        boolean isHtml = mime != null && mime.toLowerCase().startsWith("text/html");
        String disposition = (isImage || isHtml) ? "inline" : "attachment";
        // Every generated document is untrusted, including SVG served
        // inline as an image. Isolate document origins and active content;
        // retain static styles/media and explicit downloads for previews.
        headers.add("Content-Security-Policy",
                "sandbox allow-downloads; default-src 'none'; img-src * data:; "
                        + "style-src 'unsafe-inline'; font-src * data:; media-src *; "
                        + "base-uri 'none'; form-action 'none'");
        headers.add("X-Content-Type-Options", "nosniff");
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                disposition + "; filename=\"" + sanitizeAscii(entry.filename())
                        + "\"; filename*=UTF-8''" + encodedName);
        byte[] content = entry.bytes();
        headers.setContentLength(content.length);
        return ResponseEntity.ok().headers(headers).body(content);

    }

    private UserEntity resolveUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        return authService.findByUsername(authentication.getName());
    }

    private boolean canDownload(Long ownerWorkspaceId, Long currentWorkspaceId, UserEntity user) {
        if (ownerWorkspaceId == null) {
            return true;
        }
        if (currentWorkspaceId == null || !ownerWorkspaceId.equals(currentWorkspaceId)) {
            return false;
        }
        if ("admin".equalsIgnoreCase(user.getRole())) {
            return true;
        }
        return workspaceService.hasPermissionCached(ownerWorkspaceId, user.getId(), "viewer");
    }

    private String sanitizeAscii(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            sb.append(c < 0x20 || c >= 0x7F || c == '"' || c == '\\' ? '_' : c);
        }
        return sb.toString();
    }
}
