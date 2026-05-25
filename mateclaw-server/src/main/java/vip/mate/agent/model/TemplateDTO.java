package vip.mate.agent.model;

import lombok.Data;

import java.util.List;

/**
 * Agent 模板 DTO
 *
 * @author MateClaw Team
 */
@Data
public class TemplateDTO {

    private String id;
    private String name;
    private String nameZh;
    private String description;
    private String descriptionZh;
    private String icon;
    private String agentType;
    private String tags;
    private Integer maxIterations;
    /**
     * Optional pre-rendered system prompt seeded into the new agent. Templates
     * use H2 sections (## Role / ## Goal / ## Backstory / ## Additional
     * Instructions) so the editor UI can split the prompt into structured
     * fields and derive a one-line tagline for the agent card.
     */
    private String systemPrompt;
    private List<WorkspaceFileTemplate> workspaceFiles;

    /**
     * Skill slugs (matching {@code mate_skill.name}) to pre-bind to the newly
     * hired agent. Resolved against the target workspace at apply time; any
     * slug whose row is missing in that workspace is logged and skipped so a
     * partially-installed environment can still hire the agent. Templates ship
     * with classpath-stable slugs, not numeric IDs, because skill ids vary per
     * install.
     */
    private List<String> defaultSkillSlugs;

    /**
     * Tool names to pre-bind directly (bypassing the skill layer). Filtered
     * against {@code AvailableToolService.listAvailable()} at apply time —
     * names the picker can't resolve are dropped with a warning rather than
     * aborting the hire. Use for capabilities that aren't owned by any skill,
     * not for system-level tools that are already universally available.
     */
    private List<String> defaultToolNames;

    @Data
    public static class WorkspaceFileTemplate {
        private String filename;
        private String content;
        private Boolean enabled;
        private Integer sortOrder;
    }
}
