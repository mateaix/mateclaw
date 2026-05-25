package vip.mate.skill.template;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.skill.model.SkillEntity;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * RFC-091 — REST entry point for the wizard UI.
 *
 * <p>Three operations:
 * <ul>
 *   <li>{@code GET /api/v1/skill-templates} — list gallery</li>
 *   <li>{@code GET /api/v1/skill-templates/{id}} — single template
 *       definition (for the wizard form)</li>
 *   <li>{@code POST /api/v1/skill-templates/{id}/instantiate} —
 *       create a skill from filled-in values</li>
 * </ul>
 */
@Tag(name = "Skill Template Wizard")
@RestController
@RequestMapping("/api/v1/skill-templates")
@RequiredArgsConstructor
public class SkillTemplateController {

    private final SkillTemplateRegistry registry;
    private final SkillTemplateService templateService;

    @Operation(summary = "List skill templates (RFC-091)")
    @GetMapping
    @RequireWorkspaceRole("member")
    public R<List<SkillTemplate>> list() {
        return R.ok(registry.all());
    }

    @Operation(summary = "Get a single skill template")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<SkillTemplate> get(@PathVariable String id) {
        SkillTemplate t = registry.find(id);
        if (t == null) return R.fail("Template not found: " + id);
        return R.ok(t);
    }

    @Operation(summary = "Instantiate a template into a skill")
    @PostMapping("/{id}/instantiate")
    @RequireWorkspaceRole("admin")
    public R<SkillEntity> instantiate(
            @PathVariable String id,
            @RequestBody Map<String, Object> values,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(templateService.instantiate(id, values, workspaceId));
    }
}
