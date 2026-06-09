package vip.mate.tool.builtin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.llm.routing.AgentBindingResolver;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.SkillScriptExecutionService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.secret.SkillSecretService;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in tool: execute LLM-generated source code inline.
 * <p>
 * Unlike {@code runSkillScript}, which runs a pre-existing file under a skill's
 * {@code scripts/} directory, this tool accepts the code as text and runs it on
 * the fly. It lets a documentation-only skill (a SKILL.md with no {@code scripts:}
 * entries) be acted on: the agent reads the instructions, writes the code those
 * instructions describe, and runs it here.
 *
 * <p>Safety:
 * <ul>
 *   <li>Dangerous patterns in the code trigger ToolGuard approval/blocking — the
 *       tool name is registered as a shell-equivalent guarded tool.</li>
 *   <li>The subprocess does not inherit the server's secret env vars; only a
 *       bound skill's own declared secrets are injected.</li>
 *   <li>When {@code skillName} is given, the calling agent must be bound to that
 *       skill, and execution is scoped to the skill directory.</li>
 *   <li>Timeout defaults to 30s, hard-capped at 300s; output is truncated.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeExecuteTool {

    private final SkillRuntimeService runtimeService;
    private final SkillScriptExecutionService executionService;
    private final SkillSecretService skillSecretService;
    private final ObjectMapper objectMapper;

    @Lazy
    @Autowired
    private AgentBindingResolver agentBindingResolver;

    @vip.mate.tool.ConcurrencyUnsafe("code execution can have arbitrary side effects on the host process and filesystem")
    @Tool(name = "execute_code", description = """
        Execute a snippet of code you write, in python, bash, or node.
        Use this to act on a skill whose SKILL.md describes steps but ships no runnable script:
        read the instructions, write the code they describe, and run it here.

        Parameters:
        - language: one of "python", "bash", "node"
        - code: the full source code to run
        - skillName: optional. When set, the code runs inside that skill's directory
                     (so it can read the skill's reference/template files by relative path)
                     and the skill's stored secrets are injected as environment variables.
        - args: optional positional arguments, given as ONE JSON-encoded string:
                a JSON array for multiple args, or plain text for a single argument.
        - timeoutSeconds: optional, default 30, max 300.

        Returns: JSON with exitCode, stdout, stderr.

        Security: dangerous operations trigger security approval. The server's own
        secret environment variables are not exposed to the code.
        """)
    public String execute_code(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Language: python, bash, or node")
        String language,

        @JsonProperty(required = true)
        @JsonPropertyDescription("The full source code to run")
        String code,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional skill name to scope execution to and inject secrets from")
        String skillName,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional positional arguments as ONE JSON-encoded string: a JSON array for multiple args, or plain text for one literal argument.")
        String args,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Timeout in seconds, default 30, max 300")
        Integer timeoutSeconds,

        @Nullable ToolContext ctx
    ) {
        log.info("[CodeExecute] language={}, skill={}, codeChars={}",
                language, skillName, code == null ? 0 : code.length());

        Path workingDir;
        Map<String, String> envVars = Collections.emptyMap();

        if (skillName != null && !skillName.isBlank()) {
            // Skill-scoped run: validate binding + resolve the skill directory.
            ResolvedSkill skill = runtimeService.findActiveSkill(skillName);
            if (skill == null) {
                return formatError("Skill '" + skillName + "' not found or not enabled");
            }
            Long agentId = ChatOrigin.from(ctx).agentId();
            if (agentId != null) {
                Set<Long> boundSkillIds = agentBindingResolver.getBoundSkillIds(agentId);
                if (boundSkillIds != null && (skill.getId() == null || !boundSkillIds.contains(skill.getId()))) {
                    return formatError("Skill '" + skillName + "' is not available for this agent.");
                }
            }
            // Directory-backed skills run inside their own directory so the code
            // can read the skill's reference/template files by relative path. A
            // database-backed skill (no directory) is still runnable: fall through
            // with a null working dir so executeCode uses a private scratch dir.
            // Either way the skill's stored secrets are injected.
            workingDir = skill.getSkillDir();
            if (skill.getId() != null) {
                envVars = skillSecretService.getDecrypted(skill.getId());
            }
        } else {
            // Workspace-scoped run: prefer the agent's workspace base path so any
            // files the code produces land where the user expects. When the agent
            // has no workspace dir, pass null — executeCode then runs in a private
            // temp scratch dir (same tolerance as the shell tool).
            workingDir = WorkspacePathGuard.getWorkingDirectory(ctx);
            if (workingDir != null && !Files.isDirectory(workingDir)) {
                workingDir = null;
            }
        }

        Long timeout = timeoutSeconds != null ? timeoutSeconds.longValue() : null;
        List<String> argList = normalizeArgs(args);

        try {
            SkillScriptExecutionService.ScriptResult result =
                    executionService.executeCode(language, code, workingDir, argList, envVars, timeout);
            return formatResult(result);
        } catch (Exception e) {
            log.error("[CodeExecute] Execution failed: {}", e.getMessage());
            return formatError("Execution failed: " + e.getMessage());
        }
    }

    /**
     * Decode the JSON-encoded {@code args} string into a positional argument list,
     * mirroring {@code SkillScriptTool.normalizeArgs}: a JSON array becomes one
     * argument per element, anything else is forwarded verbatim as a single
     * argument (so a bare date / version string is never mangled by JSON parsing).
     */
    List<String> normalizeArgs(String args) {
        if (args == null) {
            return null;
        }
        String trimmed = args.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        char lead = trimmed.charAt(0);
        if (lead == '[') {
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
            } catch (Exception e) {
                log.debug("execute_code: args not valid JSON array, forwarding verbatim: {}", e.getMessage());
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
