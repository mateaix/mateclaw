package vip.mate.agent.template;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import vip.mate.agent.model.AgentEntity;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/templates/custom")
@RequiredArgsConstructor
public class CustomTemplateController {
    private final CustomTemplateService templates;
    private final AuthService authService;
    private final WorkspaceService workspaceService;

    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<CustomTemplateService.Summary>> list(
            @RequestHeader(value = "X-Workspace-Id", defaultValue = "1") Long ws,
            Authentication auth) {
        UserEntity user = user(auth);
        boolean admin = admin(ws, user);
        return R.ok(
                templates.list(ws).stream()
                        .map(t -> t.withCanDelete(admin || user.getId().equals(t.creatorUserId())))
                        .toList());
    }

    @PostMapping
    @RequireWorkspaceRole("member")
    public R<CustomTemplateService.Summary> save(
            @RequestHeader(value = "X-Workspace-Id", defaultValue = "1") Long ws,
            @RequestBody CustomTemplateService.SaveRequest request,
            Authentication auth) {
        return R.ok(templates.save(ws, user(auth).getId(), request));
    }

    @PostMapping("/{id}/apply")
    @RequireWorkspaceRole("member")
    public R<AgentEntity> apply(
            @RequestHeader(value = "X-Workspace-Id", defaultValue = "1") Long ws,
            @PathVariable String id,
            @RequestBody CustomTemplateService.ApplyRequest request,
            Authentication auth) {
        return R.ok(templates.apply(ws, user(auth).getId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("viewer")
    public R<Void> delete(
            @RequestHeader(value = "X-Workspace-Id", defaultValue = "1") Long ws,
            @PathVariable String id,
            Authentication auth) {
        UserEntity user = user(auth);
        templates.delete(ws, user.getId(), id, admin(ws, user));
        return R.ok();
    }

    private boolean admin(Long ws, UserEntity user) {
        return "admin".equalsIgnoreCase(user.getRole())
                || workspaceService.hasPermission(ws, user.getId(), "admin");
    }

    private UserEntity user(Authentication auth) {
        if (auth == null)
            throw new MateClawException("err.auth.unauthenticated", 401, "Not authenticated");
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null)
            throw new MateClawException("err.auth.user_not_found", 401, "User not found");
        return user;
    }
}
