package vip.mate.wiki.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;

import java.util.Set;

/**
 * Pipeline step executor that resolves a registered skill and contributes its
 * declarative content to the chain.
 *
 * <p><b>Scope (MVP, security-restricted)</b>: the skill must be installed,
 * enabled and not have a failed/blocked security scan. This executor injects
 * the skill's instructions/content as the step output; it deliberately does
 * <b>not</b> execute skill scripts — arbitrary script execution from a
 * system-triggered pipeline requires a real sandbox and a separate security
 * review (the same constraint that keeps a Python executor out of the MVP).
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class WikiSkillStepExecutor implements WikiStepExecutor {

    private static final Set<String> BLOCKED_SCAN_STATUSES = Set.of("FAILED", "BLOCKED", "REJECTED");

    private final SkillService skillService;

    public WikiSkillStepExecutor(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public String type() {
        return "skill";
    }

    @Override
    public String execute(WikiStepContext context) {
        String skillName = context.stepConfig() == null ? null
                : (String) (context.stepConfig().get("skill") instanceof String s ? s : null);
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skill step '" + context.stepId() + "' has no skill name");
        }
        SkillEntity skill = skillService.findByName(skillName);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + skillName);
        }
        if (Boolean.FALSE.equals(skill.getEnabled())) {
            throw new IllegalStateException("Skill is disabled: " + skillName);
        }
        String scan = skill.getSecurityScanStatus();
        if (scan != null && BLOCKED_SCAN_STATUSES.contains(scan.trim().toUpperCase())) {
            throw new IllegalStateException("Skill failed its security scan and cannot run in a pipeline: "
                    + skillName + " (" + scan + ")");
        }
        // Reject any attempt to run the skill's script from a pipeline — not in MVP.
        Object runScript = context.stepConfig().get("run_script");
        if (Boolean.TRUE.equals(runScript) || "true".equalsIgnoreCase(String.valueOf(runScript))) {
            throw new IllegalStateException("Script execution is not permitted for pipeline skill steps "
                    + "(requires a sandbox + approval); skill: " + skillName);
        }
        String content = skill.getSkillContent();
        if (content == null || content.isBlank()) {
            content = skill.getDescription();
        }
        log.info("[WikiPipeline] skill step '{}' contributed content from skill '{}'",
                context.stepId(), skillName);
        return content == null ? "" : content;
    }
}
