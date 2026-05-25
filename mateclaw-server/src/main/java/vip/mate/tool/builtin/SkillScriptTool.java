package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import vip.mate.skill.runtime.SkillFileAccessPolicy;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.SkillScriptExecutionService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.secret.SkillSecretService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 技能脚本执行工具
 * 允许 Agent 在运行时执行 skill 内部脚本
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillScriptTool {

    private final SkillRuntimeService runtimeService;
    private final SkillFileAccessPolicy accessPolicy;
    private final SkillScriptExecutionService executionService;
    private final SkillSecretService skillSecretService;
    private final ObjectMapper objectMapper;

    @vip.mate.tool.ConcurrencyUnsafe("script execution can have arbitrary side effects on the host process and filesystem")
    @Tool(description = """
        Execute a script from a skill's scripts/ directory.
        Use this when you need to run skill-provided automation or utilities.

        Parameters:
        - skillName: Name of the skill
        - scriptPath: Relative path to script under scripts/ directory (e.g., "scripts/run.py")
        - args: Optional script arguments, given as ONE JSON-encoded string:
                * a JSON array for multiple positional arguments, e.g. ["--verbose","input.txt"];
                * a JSON object when the script expects a single JSON payload — it is
                  forwarded as one argument, e.g. {"date":"2026-05-19","topic":"meeting"};
                * any other plain text is forwarded verbatim as a single argument.
                Pass the JSON object directly — do not wrap it in an array or escape it.

        Returns: JSON with exitCode, stdout, stderr

        Security: Only scripts under scripts/ directory can be executed. Path traversal is blocked.
        Timeout: 30 seconds per script execution.
        """)
    public String runSkillScript(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Skill name")
        String skillName,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Script path relative to skill directory (e.g., 'scripts/run.py')")
        String scriptPath,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional script arguments as ONE JSON-encoded string: a JSON array for multiple positional args, a JSON object for a single JSON payload, or plain text for one literal argument.")
        String args
    ) {
        log.info("Executing skill script: skill={}, script={}, args={}", skillName, scriptPath, args);

        // Look up active skill.
        ResolvedSkill skill = runtimeService.findActiveSkill(skillName);
        if (skill == null) {
            return formatError("Skill '" + skillName + "' not found or not enabled");
        }

        // Must be a directory-backed skill.
        if (skill.getSkillDir() == null) {
            return formatError("Skill '" + skillName + "' is database-based, no script execution available");
        }

        // Validate script path (must live under scripts/).
        Path resolvedPath = accessPolicy.validateScriptPath(skill.getSkillDir(), scriptPath);
        if (resolvedPath == null) {
            return formatError("Invalid or unsafe script path: " + scriptPath);
        }

        // Normalize the JSON-encoded args into a positional argument list.
        // Taking one JSON string (rather than a raw array) keeps the model
        // out of nested-array-of-escaped-JSON territory — the failure mode
        // where a JSON payload arrived shattered across array elements or
        // type-mismatched, and the receiving script then rejected it as
        // malformed JSON.
        List<String> argList = normalizeArgs(args);

        // RFC-091 settings bridge — pull this skill's stored secrets
        // (e.g. AIRTABLE_API_KEY) and inject them as env vars for the
        // subprocess. Decryption happens here, on the way to the child
        // process; the plaintext never lives in the rendered SKILL.md.
        Map<String, String> envVars = skill.getId() != null
                ? skillSecretService.getDecrypted(skill.getId())
                : Collections.emptyMap();

        // 执行脚本
        try {
            SkillScriptExecutionService.ScriptResult result = executionService.execute(resolvedPath, argList, envVars);
            return formatResult(result);

        } catch (Exception e) {
            log.error("Failed to execute skill script /{}: {}", skillName, scriptPath, e.getMessage());
            return formatError("Execution failed: " + e.getMessage());
        }
    }

    /**
     * Decode the JSON-encoded {@code args} string into a positional argument
     * list for the subprocess.
     *
     * <ul>
     *   <li>A JSON array becomes one CLI argument per element. Non-string
     *       elements are re-serialized to compact JSON, so an object the
     *       model wrapped in a single-element array still reaches the script
     *       as a JSON payload.</li>
     *   <li>A JSON object is forwarded as a single argument — its compact
     *       JSON text — which is what a script reading {@code json.loads(argv[1])}
     *       expects.</li>
     *   <li>Anything else (a bare date, topic, number, or malformed JSON) is
     *       forwarded verbatim as one literal argument. A bare scalar is never
     *       JSON-decoded: that would mangle e.g. {@code 2026-05-19} into
     *       {@code 2026}.</li>
     * </ul>
     *
     * <p>Package-private for direct unit testing of the decode rules.
     *
     * @param args the raw {@code args} tool parameter, may be {@code null}
     * @return the positional argument list, or {@code null} when empty
     */
    List<String> normalizeArgs(String args) {
        if (args == null) {
            return null;
        }
        String trimmed = args.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Only decode when the text clearly intends JSON structure. The
        // lead-char gate keeps a plain argument that merely looks numeric
        // (a date, a version string) from being parsed and truncated.
        char lead = trimmed.charAt(0);
        if (lead == '[' || lead == '{') {
            try {
                JsonNode node = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(trimmed);
                if (node != null && node.isArray()) {
                    List<String> out = new ArrayList<>(node.size());
                    for (JsonNode el : node) {
                        out.add(el.isTextual() ? el.asText() : el.toString());
                    }
                    return out.isEmpty() ? null : out;
                }
                if (node != null && node.isObject()) {
                    return List.of(node.toString());
                }
            } catch (Exception e) {
                // Looked like JSON but didn't parse — forward it unchanged so
                // the script reports its own input error rather than us
                // silently reshaping a malformed payload.
                log.debug("runSkillScript: args not valid JSON, forwarding verbatim: {}", e.getMessage());
            }
        }
        return List.of(trimmed);
    }

    private String formatResult(SkillScriptExecutionService.ScriptResult result) {
        return String.format(
            "{\n  \"exitCode\": %d,\n  \"stdout\": %s,\n  \"stderr\": %s\n}",
            result.getExitCode(),
            jsonEscape(result.getStdout()),
            jsonEscape(result.getStderr())
        );
    }

    private String formatError(String message) {
        return String.format(
            "{\n  \"exitCode\": -1,\n  \"stdout\": \"\",\n  \"stderr\": %s\n}",
            jsonEscape(message)
        );
    }

    private String jsonEscape(String str) {
        if (str == null || str.isEmpty()) {
            return "\"\"";
        }
        return "\"" + str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }
}
