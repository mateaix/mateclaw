package vip.mate.workspace.core.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceAccessVO;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.model.WorkspaceWithRoleVO;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.List;
import java.util.Map;

/**
 * 工作区管理接口
 *
 * @author MateClaw Team
 */
@Tag(name = "工作区管理")
@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final AuthService authService;

    // ==================== 工作区 CRUD ====================

    @Operation(summary = "获取当前用户的工作区列表（含 memberRole 与 effectiveRole）")
    @GetMapping
    public R<List<WorkspaceWithRoleVO>> list(Authentication auth) {
        UserEntity user = resolveUser(auth);
        boolean isGlobalAdmin = isGlobalAdmin(user);
        return R.ok(workspaceService.listWithRoleByUserId(user.getId(), isGlobalAdmin));
    }

    @Operation(summary = "获取工作区详情")
    @GetMapping("/{id}")
    public R<WorkspaceEntity> get(@PathVariable Long id) {
        return R.ok(workspaceService.getById(id));
    }

    @Operation(summary = "获取当前用户在指定工作区的访问能力（路由守卫消费）")
    @GetMapping("/{id}/access")
    public R<WorkspaceAccessVO> getAccess(@PathVariable Long id, Authentication auth) {
        UserEntity user = resolveUser(auth);
        boolean isGlobalAdmin = isGlobalAdmin(user);
        return R.ok(workspaceService.getAccess(id, user.getId(), isGlobalAdmin));
    }

    @Operation(summary = "创建工作区")
    @PostMapping
    public R<WorkspaceEntity> create(@RequestBody WorkspaceEntity entity, Authentication auth) {
        Long userId = resolveUserId(auth);
        return R.ok(workspaceService.create(entity, userId));
    }

    @Operation(summary = "更新工作区")
    @PutMapping("/{id}")
    public R<WorkspaceEntity> update(@PathVariable Long id, @RequestBody WorkspaceEntity entity, Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "admin");
        entity.setId(id);
        return R.ok(workspaceService.update(entity));
    }

    @Operation(summary = "删除工作区")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "owner");
        workspaceService.delete(id);
        return R.ok();
    }

    // ==================== 成员管理 ====================

    @Operation(summary = "获取工作区成员列表")
    @GetMapping("/{id}/members")
    public R<List<WorkspaceMemberEntity>> listMembers(@PathVariable Long id) {
        List<WorkspaceMemberEntity> members = workspaceService.listMembers(id);
        // 填充用户名/昵称
        for (WorkspaceMemberEntity m : members) {
            UserEntity user = authService.findById(m.getUserId());
            if (user != null) {
                m.setUsername(user.getUsername());
                m.setNickname(user.getNickname());
            }
        }
        return R.ok(members);
    }

    @Operation(summary = "添加工作区成员")
    @PostMapping("/{id}/members")
    public R<WorkspaceMemberEntity> addMember(@PathVariable Long id,
                                               @RequestBody Map<String, Object> body,
                                               Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "admin");

        Long targetUserId;
        if (body.containsKey("username")) {
            String username = body.get("username").toString().trim();
            String password = body.containsKey("password") && body.get("password") != null
                    ? body.get("password").toString().trim() : null;
            UserEntity target = authService.findByUsername(username);
            if (target == null) {
                // User does not exist — create account (password required)
                if (password == null || password.isBlank()) {
                    throw new MateClawException("err.workspace.user_not_found",
                            "User not found: " + username + ". Provide a password to create the account.");
                }
                UserEntity newUser = new UserEntity();
                newUser.setUsername(username);
                newUser.setPassword(password);
                newUser.setNickname(body.containsKey("nickname")
                        ? body.get("nickname").toString() : username);
                target = authService.createUser(newUser);
            } else if (password != null && !password.isBlank()) {
                // User exists AND admin provided a password — reset it.
                // This fixes the case where an admin removes a member, re-adds
                // them with a new password, but the stale password blocks login.
                authService.resetPassword(target.getId(), password);
            }
            targetUserId = target.getId();
        } else {
            targetUserId = Long.valueOf(body.get("userId").toString());
        }
        String role = body.containsKey("role") ? body.get("role").toString() : "member";
        return R.ok(workspaceService.addMember(id, targetUserId, role));
    }

    @Operation(summary = "更新成员角色")
    @PutMapping("/{id}/members/{memberId}")
    public R<WorkspaceMemberEntity> updateMemberRole(@PathVariable Long id,
                                                      @PathVariable Long memberId,
                                                      @RequestBody Map<String, String> body,
                                                      Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "admin");
        return R.ok(workspaceService.updateMemberRole(id, memberId, body.get("role")));
    }

    @Operation(summary = "移除工作区成员")
    @DeleteMapping("/{id}/members/{memberId}")
    public R<Void> removeMember(@PathVariable Long id, @PathVariable Long memberId, Authentication auth) {
        Long userId = resolveUserId(auth);
        workspaceService.requirePermission(id, userId, "admin");
        workspaceService.removeMember(id, memberId);
        return R.ok();
    }

    // ==================== 工具方法 ====================

    private Long resolveUserId(Authentication auth) {
        return resolveUser(auth).getId();
    }

    private UserEntity resolveUser(Authentication auth) {
        String username = auth.getName();
        UserEntity user = authService.findByUsername(username);
        if (user == null) {
            throw new MateClawException("用户不存在: " + username);
        }
        return user;
    }

    private boolean isGlobalAdmin(UserEntity user) {
        return user != null && "admin".equalsIgnoreCase(user.getRole());
    }
}
