package vip.mate.skill.runtime;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import vip.mate.skill.knowledge.SkillScopedToolCallback;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.secret.SkillSecretService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper tool factory for skill script entrypoints declared in the
 * {@code scripts} manifest block.
 *
 * <p>A directory-backed skill ships executable scripts under {@code scripts/}.
 * The generic {@code runSkillScript} tool can run any of them, but it forces
 * the model to hand-assemble the argument list — brittle whenever a script
 * consumes a structured JSON payload. This factory turns each declared
 * entrypoint into its own typed tool: the model fills schema-described
 * fields, and the runtime serializes them into process arguments. The model
 * never crafts a JSON string by hand.
 *
 * <p>One wrapper per entrypoint, named {@code skill_<skill>_<entrypoint>}.
 * Each wrapper closes over the resolved skill directory and id, so a call
 * always targets the declaring skill's own script and decrypted secrets and
 * cannot be redirected elsewhere.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptSkillWrapperToolFactory {

    private final SkillScriptExecutionService executionService;
    private final SkillFileAccessPolicy accessPolicy;
    private final SkillSecretService skillSecretService;
    private final ObjectMapper objectMapper;

    /**
     * Build one wrapper callback per declared script entrypoint. Returns an
     * empty list when the skill declares no entrypoints or has no directory
     * (a database-only skill cannot expose runnable scripts).
     */
    public List<ToolCallback> buildWrappers(ResolvedSkill resolved, SkillManifest manifest) {
        if (resolved == null || manifest == null
                || manifest.getScripts() == null || manifest.getScripts().isEmpty()
                || resolved.getSkillDir() == null) {
            return List.of();
        }
        String skillSlug = sanitize(manifest.getName());
        if (skillSlug.isBlank()) {
            return List.of();
        }
        Path skillDir = resolved.getSkillDir();
        Long skillId = resolved.getId();

        List<ToolCallback> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SkillManifest.ScriptDef def : manifest.getScripts()) {
            if (!isUsable(def)) {
                continue;
            }
            String name = "skill_" + skillSlug + "_" + sanitize(def.getId());
            if (!seen.add(name)) {
                log.warn("Skill '{}' script entrypoint id '{}' collides on tool name '{}'; skipping duplicate",
                        manifest.getName(), def.getId(), name);
                continue;
            }
            out.add(new SkillScopedToolCallback(
                    name,
                    buildDescription(manifest, def),
                    buildInputSchema(def),
                    input -> invoke(skillDir, skillId, def, input)));
        }
        return out;
    }

    /**
     * Names the wrappers this manifest would produce, without building them.
     * Used by the resolver to merge entrypoint names into
     * {@code manifest.allowedTools} so {@code getEffectiveAllowedTools()}
     * surfaces them like any other declared tool.
     */
    public List<String> wrapperNames(SkillManifest manifest) {
        if (manifest == null || manifest.getName() == null
                || manifest.getScripts() == null || manifest.getScripts().isEmpty()) {
            return List.of();
        }
        String skillSlug = sanitize(manifest.getName());
        if (skillSlug.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SkillManifest.ScriptDef def : manifest.getScripts()) {
            if (!isUsable(def)) {
                continue;
            }
            String name = "skill_" + skillSlug + "_" + sanitize(def.getId());
            if (seen.add(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /** An entrypoint is usable only when it has both an id and a script path. */
    private static boolean isUsable(SkillManifest.ScriptDef def) {
        return def != null
                && def.getId() != null && !def.getId().isBlank()
                && def.getPath() != null && !def.getPath().isBlank();
    }

    // ==================== invocation ====================

    private String invoke(Path skillDir, Long skillId, SkillManifest.ScriptDef def, String input) {
        try {
            JsonNode args = (input == null || input.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(input);

            // Path traversal is blocked here — only scripts under the
            // skill's own scripts/ directory can be reached.
            Path scriptPath = accessPolicy.validateScriptPath(skillDir, def.getPath());
            if (scriptPath == null) {
                return errorJson("invalid or unsafe script path: " + def.getPath());
            }

            List<String> argv = buildArgv(def.getFixedArgs(), def.getArgStyle(), args);

            // Inject this skill's stored secrets as subprocess env vars,
            // mirroring the generic runSkillScript path.
            Map<String, String> envVars = skillId != null
                    ? skillSecretService.getDecrypted(skillId)
                    : Map.of();

            SkillScriptExecutionService.ScriptResult result =
                    executionService.execute(scriptPath, argv, envVars);
            return JSONUtil.createObj()
                    .set("exitCode", result.getExitCode())
                    .set("stdout", result.getStdout())
                    .set("stderr", result.getStderr())
                    .toString();
        } catch (Exception e) {
            log.warn("script wrapper for entrypoint '{}' failed: {}", def.getId(), e.getMessage());
            return errorJson(e.getMessage() == null ? "script invocation failed" : e.getMessage());
        }
    }

    /**
     * Translate the typed argument object into a process argument list.
     *
     * <p>{@code fixedArgs} are emitted first, verbatim — they let one
     * dispatcher script back several entrypoints (e.g. a fixed method name
     * as {@code argv[1]}). The typed arguments follow, shaped by
     * {@code argStyle}:
     *
     * <ul>
     *   <li>{@code json} (default) — append the whole object as one compact
     *       JSON argument, the shape a script reading its last argv with a
     *       JSON parser expects.</li>
     *   <li>{@code flags} — append each property as {@code --key value};
     *       a {@code true} boolean becomes a bare {@code --key}, while a
     *       {@code false} or null property is dropped.</li>
     * </ul>
     *
     * <p>Package-private and static for direct unit testing.
     *
     * @return the argument list, or {@code null} when it would be empty
     */
    static List<String> buildArgv(List<String> fixedArgs, String argStyle, JsonNode args) {
        List<String> out = new ArrayList<>();
        if (fixedArgs != null) {
            for (String fixed : fixedArgs) {
                if (fixed != null) {
                    out.add(fixed);
                }
            }
        }
        if ("flags".equalsIgnoreCase(argStyle)) {
            if (args != null && args.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = args.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    JsonNode value = field.getValue();
                    if (value == null || value.isNull()) {
                        continue;
                    }
                    if (value.isBoolean()) {
                        if (value.asBoolean()) {
                            out.add("--" + field.getKey());
                        }
                        continue;
                    }
                    out.add("--" + field.getKey());
                    out.add(value.isValueNode() ? value.asText() : value.toString());
                }
            }
        } else {
            // Default: json — append one compact JSON argument, unless the
            // object is empty / absent (an entrypoint with no typed input).
            if (args != null && !args.isNull() && !args.isMissingNode()
                    && !(args.isObject() && args.isEmpty())) {
                out.add(args.toString());
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ==================== helpers ====================

    private String buildDescription(SkillManifest manifest, SkillManifest.ScriptDef def) {
        String base;
        if (def.getDescription() != null && !def.getDescription().isBlank()) {
            base = def.getDescription().trim();
        } else if (def.getLabel() != null && !def.getLabel().isBlank()) {
            base = def.getLabel().trim();
        } else {
            base = "Run the '" + def.getId() + "' script";
        }
        return base + " (skill: " + manifest.getName() + "). "
                + "Fill the described fields; the arguments are forwarded to the script for you.";
    }

    private String buildInputSchema(SkillManifest.ScriptDef def) {
        Map<String, Object> params = def.getParameters();
        if (params == null || params.isEmpty()) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            log.warn("script entrypoint '{}' has an unserializable parameter schema: {}",
                    def.getId(), e.getMessage());
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    /** Tool-name slug rule shared with the knowledge / acp wrapper factories. */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private static String errorJson(String message) {
        return JSONUtil.createObj().set("error", message).toString();
    }
}
