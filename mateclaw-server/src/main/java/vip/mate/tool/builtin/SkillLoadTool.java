package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

/**
 * Explicit skill-load entry point.
 * <p>
 * Pulls a skill package's SKILL.md (or a named sub-file) into the conversation
 * as a tool observation. Naming it {@code load_skill} — rather than reusing the
 * lower-level {@code readSkillFile} — gives the model a clear "load this skill"
 * verb that matches the catalog guidance, and the call is detected by the
 * action node to pin the skill at the top of the runtime catalog so the model
 * does not reload it on later iterations.
 * <p>
 * The full content is returned as a normal tool result; it flows into message
 * history via the standard tool-response path and never mutates the system
 * prompt, so the prompt-cache prefix stays stable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillLoadTool {

    private final SkillRuntimeService runtimeService;
    private final SkillFileTool skillFileTool;

    @Tool(name = "load_skill", description = """
        Load a skill package's SKILL.md into the conversation.
        Call this when a skill in the catalog matches the task.

        Parameters:
        - skillName: Skill name exactly as shown in the catalog.
        - filePath: Optional sub-file inside the skill (e.g. "references/api.md").
                    Omit to load SKILL.md.

        The full content is returned as a tool observation; later turns see it in
        message history, so do NOT load the same skill again once it is loaded.
        Skills are documentation packages — calling a skill name directly as a
        tool will fail; load it first, then follow its instructions.
        """)
    public String loadSkill(
            @ToolParam(description = "Skill name as shown in the catalog")
            String skillName,

            @ToolParam(description = "Optional sub-file path inside the skill (e.g. references/api.md)",
                    required = false)
            String filePath,

            @Nullable ToolContext ctx
    ) {
        if (skillName == null || skillName.isBlank()) {
            return "Error: skillName is required. Call listAvailableSkills() to see loadable skills.";
        }
        ResolvedSkill skill = runtimeService.findActiveSkill(skillName);
        if (skill == null) {
            log.info("load_skill: skill '{}' not found or not enabled", skillName);
            return "Error: Skill '" + skillName + "' not found or not enabled. "
                    + "Call listAvailableSkills(keyword=\"" + skillName + "\") to find the correct name.";
        }
        String path = (filePath == null || filePath.isBlank()) ? "SKILL.md" : filePath;
        log.info("load_skill: loading skill='{}', path='{}'", skillName, path);
        // Delegate to the shared reader: it resolves the skill, paginates large
        // sub-files, and records usage. SKILL.md is returned in full by default.
        return skillFileTool.readSkillFile(skillName, path, null, null, ctx);
    }
}
