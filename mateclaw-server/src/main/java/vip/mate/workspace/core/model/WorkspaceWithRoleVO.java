package vip.mate.workspace.core.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Workspace list item enriched with the current user's membership info.
 * <p>
 * Returned by {@code GET /api/v1/workspaces} so the frontend can render the
 * workspace switcher and derive route visibility without an extra round-trip.
 * <ul>
 *   <li>{@code memberRole}: real membership role; {@code null} for non-members</li>
 *   <li>{@code roleLevel}: numeric (owner=4..viewer=1, 0 for non-member)</li>
 *   <li>{@code isGlobalAdmin}: {@code mate_user.role='admin'} — bypasses workspace gates</li>
 *   <li>{@code effectiveRole}: {@code owner} for global admin, otherwise = {@code memberRole}</li>
 * </ul>
 * The display name in the UI uses {@code memberRole} so a global admin is shown as
 * "global admin (non-member)" rather than impersonated as workspace owner. Access
 * decisions use {@code effectiveRole} or the {@code /access} capability set.
 */
@Data
public class WorkspaceWithRoleVO {

    private Long id;
    private String name;
    private String slug;
    private String description;
    private String basePath;
    private Long ownerId;
    private String settingsJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private String memberRole;
    private Integer roleLevel;
    private Boolean isGlobalAdmin;
    private String effectiveRole;

    public static WorkspaceWithRoleVO from(WorkspaceEntity entity,
                                           String memberRole,
                                           boolean isGlobalAdmin) {
        WorkspaceWithRoleVO vo = new WorkspaceWithRoleVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setSlug(entity.getSlug());
        vo.setDescription(entity.getDescription());
        vo.setBasePath(entity.getBasePath());
        vo.setOwnerId(entity.getOwnerId());
        vo.setSettingsJson(entity.getSettingsJson());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setMemberRole(memberRole);
        vo.setIsGlobalAdmin(isGlobalAdmin);

        String effective = isGlobalAdmin ? "owner" : memberRole;
        vo.setEffectiveRole(effective);
        vo.setRoleLevel(memberRole == null && !isGlobalAdmin
                ? 0
                : vip.mate.workspace.core.security.RoleCapabilities.roleLevel(effective));
        return vo;
    }
}
