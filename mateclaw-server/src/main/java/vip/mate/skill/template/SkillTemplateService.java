package vip.mate.skill.template;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.secret.SkillSecretService;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.skill.workspace.bundle.ClasspathBundleSource;
import vip.mate.skill.workspace.bundle.MaterializeOptions;
import vip.mate.skill.workspace.bundle.SkillBundleMaterializer;
import vip.mate.skill.workspace.bundle.SkillBundleSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC-091 — instantiate a {@link SkillTemplate} into a real
 * {@code mate_skill} row.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Validate every required field has a non-blank value.</li>
 *   <li>Compute auxiliary placeholders (e.g. {@code citation_string}
 *       from a boolean toggle) so the SKILL.md doesn't need conditional
 *       logic.</li>
 *   <li>Substitute {@code {{key}}} occurrences in the template body.</li>
 *   <li>Build a {@link SkillEntity} and hand it to
 *       {@link SkillService#createSkill}, which persists the row,
 *       initializes the workspace directory, and refreshes the runtime
 *       cache. The resolver will then pick up the manifest.</li>
 * </ol>
 *
 * <p>This intentionally goes through {@code SkillService.createSkill}
 * rather than the install task pipeline — the wizard produces a local
 * skill from in-process content, no bundle download involved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final SkillTemplateRegistry registry;
    private final SkillService skillService;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillBundleMaterializer bundleMaterializer;
    private final SkillSecretService skillSecretService;
    /** Stateless and shared — Spring's classpath resolver is thread-safe. */
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    /**
     * Instantiate the template by id, substituting fields, and create
     * the skill. Returns the created {@link SkillEntity}.
     *
     * @param templateId  id from the registry (e.g. {@code tcm-qa})
     * @param values      user-supplied field values; missing required
     *                    fields throw a translatable exception
     * @param workspaceId owning workspace for the created skill
     */
    public SkillEntity instantiate(String templateId, Map<String, Object> values, Long workspaceId) {
        SkillTemplate template = registry.find(templateId);
        if (template == null) {
            throw new MateClawException("err.skill_template.not_found",
                    "Skill template not found: " + templateId);
        }
        if (values == null) values = Map.of();

        // 1. validate + collect into a single substitution map
        Map<String, String> substitutions = collectSubstitutions(template, values);

        // 2. render SKILL.md
        String skillMd = render(template.getSkillMd(), substitutions);

        // 3. build entity and create via SkillService (which already
        //    handles uniqueness, defaults, workspace init, runtime
        //    cache refresh).
        SkillEntity entity = new SkillEntity();
        entity.setName(substitutions.get("skill_name"));
        String displayZh = substitutions.getOrDefault("display_name_zh", "");
        String displayEn = substitutions.getOrDefault("display_name_en",
                substitutions.getOrDefault("display_name", ""));
        entity.setNameZh(displayZh.isBlank() ? null : displayZh);
        entity.setNameEn(displayEn.isBlank() ? null : displayEn);
        entity.setDescription(template.getDescription());
        entity.setSkillType(mapType(template.getType()));
        entity.setIcon(template.getIcon());
        entity.setVersion("1.0.0");
        entity.setAuthor("skill-template-wizard");
        entity.setSkillContent(skillMd);
        entity.setEnabled(true);
        entity.setWorkspaceId(workspaceId);

        SkillEntity created = skillService.createSkill(entity);

        // 4. (optional) overlay supporting files shipped under
        //    classpath:{bundlePath}/** — scripts, references, fonts, etc.
        //    Top-level SKILL.md in the bundle is skipped automatically so
        //    the rendered manifest from step 2 stays authoritative.
        overlayBundle(template, created.getName());

        // 5. Persist any `secret` field values into mate_skill_secret so
        //    the runtime can decrypt + inject them as env vars at exec
        //    time. Substitution into SKILL.md is deliberately skipped
        //    for secrets (see render() filtering) — baking a credential
        //    into the manifest would leak it to logs / preview / export.
        persistSecrets(template, created.getId(), values);

        return created;
    }

    private void persistSecrets(SkillTemplate template, Long skillId,
                                 Map<String, Object> values) {
        for (SkillTemplate.TemplateField field : template.getFields()) {
            if (!"secret".equals(field.getType())) continue;
            Object raw = values.get(field.getKey());
            if (raw == null) continue;
            String plain = raw.toString();
            if (plain.isBlank()) continue;
            try {
                skillSecretService.put(skillId, field.getKey(), plain);
            } catch (Exception e) {
                // Don't fail the whole instantiation — the skill is
                // already created; surface the error so the user can
                // re-set the secret via the settings UI.
                log.warn("Failed to persist secret '{}' for skill_id={}: {}",
                        field.getKey(), skillId, e.getMessage());
            }
        }
    }

    private void overlayBundle(SkillTemplate template, String skillName) {
        String bundlePath = template.getBundlePath();
        if (bundlePath == null || bundlePath.isBlank()) {
            return;
        }
        SkillBundleSource source = new ClasspathBundleSource(resourceResolver, bundlePath);
        Path workspaceDir = workspaceManager.resolveConventionPath(skillName);
        try {
            SkillBundleMaterializer.Result result = bundleMaterializer.materialize(
                    source, workspaceDir, MaterializeOptions.templateOverlay());
            if (result.copied() == 0) {
                log.warn("Template '{}' declared bundlePath {} but no files were materialized",
                        template.getId(), source.origin());
            } else {
                log.info("Template '{}' overlaid {} bundled file(s) from {} → skill '{}'",
                        template.getId(), result.copied(), source.origin(), skillName);
            }
        } catch (IOException e) {
            // Don't fail the whole instantiation — the rendered SKILL.md
            // is already on disk, the skill is functional, the bundle
            // is supplemental. Surface the error in logs so admins notice.
            log.warn("Failed to overlay bundle {} for skill '{}': {}",
                    source.origin(), skillName, e.getMessage());
        }
    }

    private String mapType(String type) {
        if (type == null) return "dynamic";
        // RFC-090 §5.1 introduced more types, but mate_skill.skill_type
        // historically only knows builtin / mcp / dynamic. Map knowledge /
        // prompt back to dynamic for legacy callers; the manifest_json
        // column carries the real v3 type.
        return switch (type) {
            case "mcp" -> "mcp";
            case "builtin" -> "builtin";
            default -> "dynamic";
        };
    }

    private Map<String, String> collectSubstitutions(SkillTemplate template,
                                                      Map<String, Object> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SkillTemplate.TemplateField field : template.getFields()) {
            Object raw = values.get(field.getKey());
            String resolved = raw == null ? null : raw.toString().trim();
            if ((resolved == null || resolved.isBlank())) {
                if (field.getDefaultValue() != null) {
                    resolved = field.getDefaultValue().toString();
                } else if (field.isRequired()) {
                    throw new MateClawException("err.skill_template.missing_field",
                            "Required field missing: " + field.getKey());
                } else {
                    resolved = "";
                }
            }
            // Secrets never enter the rendered SKILL.md verbatim — they
            // get persisted to mate_skill_secret and surfaced to the
            // runtime as env vars. Inside SKILL.md, replace the
            // placeholder with the bash env-var reference so authors can
            // write `{{AIRTABLE_API_KEY}}` and end up with
            // `$AIRTABLE_API_KEY` in the manifest. Convention: secret
            // field keys should be uppercase env-var-shaped names so the
            // stored row, the rendered placeholder, and the runtime env
            // var all line up.
            if ("secret".equals(field.getType())) {
                out.put(field.getKey(), "$" + field.getKey());
            } else {
                out.put(field.getKey(), resolved);
            }
        }
        // Auxiliary derived placeholders so SKILL.md templates stay simple.
        if (out.containsKey("citation_required")) {
            boolean req = Boolean.parseBoolean(out.get("citation_required"));
            out.put("citation_string", req ? "required" : "optional");
            out.put("citation_instruction", req
                    ? "**每条建议必须标明引用的 KB 出处** ({{citation}} 自动注入)。"
                    : "如有 KB 引用，按 {{citation}} 标注；否则可省略。");
        }
        if (out.containsKey("output_language")) {
            String lang = out.get("output_language");
            out.put("output_language_label", "zh".equalsIgnoreCase(lang) ? "中文" : "English");
        }
        return out;
    }

    private String render(String template, Map<String, String> values) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = values.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
