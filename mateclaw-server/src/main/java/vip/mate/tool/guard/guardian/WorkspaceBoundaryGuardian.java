package vip.mate.tool.guard.guardian;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.tool.guard.model.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Workspace filesystem-boundary guard.
 * <p>
 * Backstops {@link WorkspacePathGuard} at the policy layer: when a shell command
 * escapes the active workspace (absolute path, {@code ..} traversal, tilde/env
 * expansion) or deletes the workspace root itself, this guardian emits a
 * {@code CRITICAL} / {@code BLOCK} finding so the call is rejected
 * <em>before</em> the human-approval prompt — not merely refused at execution
 * time. A delete of the workspace root passes the reflexive boundary check
 * ({@code root startsWith root}) yet destroys the whole sandbox, so it is
 * treated as an escape. (Issue #313.)
 * <p>
 * The boundary is read from {@link ToolInvocationContext#workspaceBasePath()};
 * when unset, the global fallback sandbox root applies via
 * {@code WorkspacePathGuard}. When neither is configured, the guardian is a
 * no-op and the legacy unconstrained behaviour is preserved.
 */
@Slf4j
@Component
public class WorkspaceBoundaryGuardian implements ToolGuardGuardian {

    private static final Set<String> SHELL_TOOL_NAMES = Set.of(
            "execute_shell_command", "shell_execute", "run_command"
    );

    /**
     * Inline code-execution tool. Its shell content lives in the {@code code}
     * JSON parameter (selected by a sibling {@code language} parameter), not in
     * a {@code command} parameter, so it needs its own extraction path. Only
     * shell-language code is screened for boundary escapes — see
     * {@link #SHELL_LANGUAGES} and {@link #evaluate}.
     */
    private static final String CODE_TOOL_NAME = "execute_code";

    /**
     * {@code language} values whose {@code code} is shell script and can be
     * scanned with the shell-syntax boundary scanner. Mirrors the {@code .sh}
     * aliases accepted by the code-execution runtime. Python/Node code is not
     * scanned: the shell scanner would false-positive on absolute-path string
     * literals while missing interpreter-specific file access, so applying it
     * there is both noisy and incomplete.
     */
    private static final Set<String> SHELL_LANGUAGES = Set.of("bash", "sh", "shell");

    /** File tools and their JSON path-parameter name. */
    private static final Map<String, String> FILE_PATH_PARAMS = Map.of(
            "read_file", "filePath",
            "write_file", "filePath",
            "edit_file", "filePath"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(ToolInvocationContext context) {
        String tool = context.toolName();
        return tool != null && (SHELL_TOOL_NAMES.contains(tool)
                || CODE_TOOL_NAME.equals(tool)
                || FILE_PATH_PARAMS.containsKey(tool));
    }

    /** Run before the DB-rule guardians so a boundary escape blocks early. */
    @Override
    public int priority() {
        return 400;
    }

    @Override
    public List<GuardFinding> evaluate(ToolInvocationContext context) {
        String tool = context.toolName();
        String rawArgs = context.rawArguments();
        if (tool == null || rawArgs == null || rawArgs.isEmpty()) {
            return List.of();
        }
        String basePath = context.workspaceBasePath();

        if (SHELL_TOOL_NAMES.contains(tool)) {
            String command = extractJsonParam(rawArgs, "command");
            if (command == null) command = rawArgs;
            String violation = WorkspacePathGuard.findShellBoundaryViolation(command, basePath);
            if (violation != null) {
                return List.of(boundaryFinding(tool, "command", command, violation));
            }
            return List.of();
        }

        if (CODE_TOOL_NAME.equals(tool)) {
            // The shell content lives in the `code` param, gated by `language`.
            // Scan only shell-language code, and extract `code` explicitly
            // rather than scanning the whole rawArgs JSON — the latter would
            // false-positive on Python/Node source that merely contains an
            // absolute-path string literal.
            if (!isShellLanguage(extractJsonParam(rawArgs, "language"))) {
                return List.of();
            }
            String code = extractJsonParam(rawArgs, "code");
            if (code == null) {
                return List.of();
            }
            String violation = WorkspacePathGuard.findShellBoundaryViolation(code, basePath);
            if (violation != null) {
                return List.of(boundaryFinding(tool, "code", code, violation));
            }
            return List.of();
        }

        String paramName = FILE_PATH_PARAMS.get(tool);
        if (paramName != null) {
            String path = extractJsonParam(rawArgs, paramName);
            String violation = WorkspacePathGuard.findPathBoundaryViolation(path, basePath);
            if (violation != null) {
                return List.of(boundaryFinding(tool, "path", path, violation));
            }
        }
        return List.of();
    }

    private GuardFinding boundaryFinding(String toolName, String paramName, String matchValue, String reason) {
        return new GuardFinding(
                "WORKSPACE_BOUNDARY_ESCAPE",
                GuardSeverity.CRITICAL,
                GuardCategory.SENSITIVE_FILE_ACCESS,
                "工作区越界",
                reason,
                "仅在工作区目录内操作；不要访问上层目录或删除工作区根目录",
                toolName,
                paramName,
                "workspace_boundary",
                matchValue,
                GuardDecision.BLOCK);
    }

    /** True when {@code language} names a shell interpreter (bash/sh/shell). */
    private static boolean isShellLanguage(String language) {
        return language != null && SHELL_LANGUAGES.contains(language.trim().toLowerCase(Locale.ROOT));
    }

    private String extractJsonParam(String rawArgs, String paramName) {
        try {
            Map<String, Object> params = objectMapper.readValue(rawArgs, new TypeReference<>() {});
            Object val = params.get(paramName);
            return val instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }
}
