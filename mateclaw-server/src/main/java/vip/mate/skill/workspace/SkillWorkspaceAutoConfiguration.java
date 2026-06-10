package vip.mate.skill.workspace;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import vip.mate.skill.installer.SkillHubProperties;
import vip.mate.tool.guard.WorkspacePathGuard;

/**
 * Skill 工作区与安装器自动配置
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties({SkillWorkspaceProperties.class, SkillHubProperties.class})
public class SkillWorkspaceAutoConfiguration {

    /**
     * Register the shared skill repository root with the workspace path sandbox.
     * Skills are shared across all workspaces and live outside any single
     * workspace directory, so the sandbox must trust their root in addition to
     * the active workspace — otherwise reading or running a skill's files from a
     * workspace configured elsewhere is rejected as a boundary violation.
     */
    public SkillWorkspaceAutoConfiguration(SkillWorkspaceProperties skillWorkspaceProperties) {
        WorkspacePathGuard.setSkillRoot(skillWorkspaceProperties.getRoot());
    }
}
