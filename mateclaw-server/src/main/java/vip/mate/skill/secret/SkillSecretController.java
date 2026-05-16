package vip.mate.skill.secret;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * RFC-091 settings bridge — admin REST endpoints for managing
 * per-skill secrets independently of the wizard.
 *
 * <p>Lets users edit / delete / re-set credentials after a skill is
 * already installed (e.g. when an API key rotates) without having to
 * tear the skill down and re-run the wizard.
 *
 * <p>Listing returns masked previews only — full plaintext is never
 * shipped over HTTP, even to authenticated callers.
 */
@Tag(name = "Skill Secrets")
@RestController
@RequestMapping("/api/v1/skills/{skillId}/secrets")
@RequiredArgsConstructor
public class SkillSecretController {

    private final SkillSecretService skillSecretService;

    @Operation(summary = "List secret keys + masked previews for a skill")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<SkillSecretService.SecretSummary>> list(@PathVariable Long skillId) {
        return R.ok(skillSecretService.listSummaries(skillId));
    }

    @Operation(summary = "Upsert a secret value (empty value deletes it)")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<Void> put(@PathVariable Long skillId, @RequestBody Map<String, String> body) {
        skillSecretService.put(skillId, body.get("key"), body.get("value"));
        return R.ok();
    }

    @Operation(summary = "Delete a single secret by key")
    @DeleteMapping("/{key}")
    @RequireWorkspaceRole("admin")
    public R<Void> remove(@PathVariable Long skillId, @PathVariable String key) {
        skillSecretService.remove(skillId, key);
        return R.ok();
    }
}
