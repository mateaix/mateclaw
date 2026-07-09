package vip.mate.kbopen.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.kbopen.auth.KbApiKeyService;
import vip.mate.kbopen.auth.KbApiKeyService.CreatedKey;
import vip.mate.kbopen.auth.model.KbApiKeyEntity;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin CRUD for KB Open API Keys (JWT-authenticated, workspace-scoped).
 *
 * <p>Plaintext is returned exactly once on {@link #create}; subsequent lookups
 * expose only metadata (id, name, prefix, scopes, bound KBs, lastUsedAt).
 */
@Tag(name = "KB Open API Keys")
@RestController
@RequestMapping("/api/v1/open/keys")
@RequiredArgsConstructor
public class KbApiKeyAdminController {

    private final KbApiKeyService keyService;
    private final AuthService authService;

    @Operation(summary = "List API keys in the current workspace (metadata only)")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<KbApiKeyEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(keyService.listByWorkspace(wsId));
    }

    @Operation(summary = "Mint a new API key — plaintext shown once, cannot be recovered")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> create(
            @RequestBody CreateKeyRequest req,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {
        UserEntity user = requireUser(auth);
        long wsId = workspaceId != null ? workspaceId : 1L;
        CreatedKey created = keyService.create(
                wsId,
                user.getId(),
                req.name(),
                req.scopes(),
                req.kbIds(),
                req.expiresAt());
        return R.ok(Map.of(
                "id", created.id(),
                "plaintext", created.plaintext(),
                "name", req.name() == null ? "" : req.name(),
                "prefix", created.entity().getPrefix(),
                "scopes", created.entity().getScopes() == null ? "" : created.entity().getScopes(),
                "kbIds", req.kbIds(),
                "expiresAt", req.expiresAt() == null ? "" : req.expiresAt()));
    }

    @Operation(summary = "Get key details (metadata only, no plaintext)")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> detail(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        KbApiKeyEntity entity = keyService.getById(id);
        if (entity == null || (entity.getDeleted() != null && entity.getDeleted() == 1)
                || !entity.getWorkspaceId().equals(wsId)) {
            throw new MateClawException(404, "API key not found: " + id);
        }
        Set<Long> kbIds = keyService.loadBoundKbIds(id);
        return R.ok(Map.of(
                "id", entity.getId(),
                "name", entity.getName(),
                "prefix", entity.getPrefix(),
                "scopes", entity.getScopes() == null ? "" : entity.getScopes(),
                "kbIds", kbIds,
                "enabled", entity.getEnabled(),
                "rateLimitPerMin", entity.getRateLimitPerMin(),
                "lastUsedAt", entity.getLastUsedAt() == null ? "" : entity.getLastUsedAt(),
                "expiresAt", entity.getExpiresAt() == null ? "" : entity.getExpiresAt(),
                "createTime", entity.getCreateTime() == null ? "" : entity.getCreateTime()));
    }

    @Operation(summary = "Update key (name / scopes / kb-ids / expiresAt — cannot change plaintext)")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> update(
            @PathVariable Long id,
            @RequestBody UpdateKeyRequest req,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        keyService.update(id, wsId, req.name(), req.scopes(), req.kbIds(), req.expiresAt());
        return R.ok();
    }

    @Operation(summary = "Revoke (soft-delete) an API key")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> revoke(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        keyService.revoke(id, wsId);
        return R.ok();
    }

    private UserEntity requireUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new MateClawException("err.auth.unauthenticated", "Authentication required");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found",
                    "Authenticated user not found: " + auth.getName());
        }
        return user;
    }

    public record CreateKeyRequest(String name, String scopes, Set<Long> kbIds, LocalDateTime expiresAt) {}

    public record UpdateKeyRequest(String name, String scopes, Set<Long> kbIds, LocalDateTime expiresAt) {}
}
