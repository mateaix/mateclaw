package vip.mate.skill.installer.model;

import lombok.Data;

/**
 * Skill 安装请求
 *
 * @author MateClaw Team
 */
@Data
public class InstallRequest {

    /** bundle URL（GitHub 仓库 URL 或 ClawHub skill URL） */
    private String bundleUrl;

    /** 版本（git ref / hub version，可选） */
    private String version;

    /** 安装后是否启用 */
    private Boolean enable = true;

    /** 指定 skill 名称（覆盖 SKILL.md 中的名称，可选） */
    private String targetName;

    /** 若同名 skill 已存在，是否覆盖 */
    private Boolean overwrite = false;

    /**
     * Bypass the empty-bundle prune guard. Default {@code false} keeps
     * existing scripts/references when the new bundle has zero entries
     * for that bucket — protects against malformed uploads. Set to
     * {@code true} only when you really want to clear out a bucket via
     * an intentionally empty bundle.
     */
    private Boolean forcePrune = false;

    /**
     * Owning workspace for the installed skill. Stamped by the controller
     * from the {@code X-Workspace-Id} header — never trusted from a raw
     * client body. {@code null} falls back to the default workspace.
     */
    private Long workspaceId;
}
