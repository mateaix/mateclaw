package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 内置工具：Git 操作
 * <p>
 * 封装常用 git 命令：status / diff / log / branch / add / commit / push / pull。
 * 只读操作（status/diff/log/branch）安全；写操作（add/commit/push/pull）
 * 标记 {@code @ConcurrencyUnsafe} 并走 ToolGuard 审批。
 * 路径边界由 {@code WorkspacePathGuard} 强制，仅允许在 workspace 内执行。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class GitTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_BYTES = 20_000;

    @Tool(description = """
            Run a git command in the workspace repository. Supports read-only \
            operations (status, diff, log, branch, show) and write operations \
            (add, commit, push, pull, checkout). Write operations require user \
            approval. Returns structured JSON with stdout, stderr, exitCode. \
            The repo path is resolved against the workspace boundary.""")
    public String git(
            @ToolParam(description = "Git subcommand and args, e.g. 'status', 'diff --stat', 'log --oneline -10', 'add .', 'commit -m \"msg\"'") String gitCommand,
            @ToolParam(description = "Repository directory (absolute or relative). Defaults to workspace root", required = false) String repoPath,
            @ToolParam(description = "Timeout in seconds, default 60", required = false) Integer timeoutSeconds) {

        JSONObject result = new JSONObject();

        try {
            if (gitCommand == null || gitCommand.isBlank()) {
                result.set("error", true);
                result.set("message", "Git command is required");
                return JSONUtil.toJsonPrettyStr(result);
            }

            // Only allow known-safe subcommands
            String trimmed = gitCommand.trim();
            String subcommand = trimmed.split("\\s+")[0].toLowerCase();
            if (!isAllowedSubcommand(subcommand)) {
                result.set("error", true);
                result.set("message", "Disallowed git subcommand: " + subcommand
                        + ". Allowed: status, diff, log, branch, show, add, commit, push, pull, checkout, restore, stash, fetch, reset, rebase, merge, blame");
                return JSONUtil.toJsonPrettyStr(result);
            }

            boolean isWrite = isWriteSubcommand(subcommand);

            Path repo;
            try {
                String dir = (repoPath == null || repoPath.isBlank()) ? "." : repoPath;
                repo = vip.mate.tool.guard.WorkspacePathGuard.validatePath(dir);
            } catch (IllegalArgumentException e) {
                result.set("error", true);
                result.set("message", e.getMessage());
                return JSONUtil.toJsonPrettyStr(result);
            }

            if (!Files.exists(repo)) {
                result.set("error", true);
                result.set("message", "Repository path does not exist: " + repo);
                return JSONUtil.toJsonPrettyStr(result);
            }

            int timeout = (timeoutSeconds != null && timeoutSeconds > 0)
                    ? Math.min(timeoutSeconds, 300)
                    : DEFAULT_TIMEOUT_SECONDS;

            String gitExe = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "git.exe" : "git";

            // Split the command into subcommand + args for ProcessBuilder.
            // ProcessBuilder does NOT do shell parsing — passing "commit -m msg"
            // as a single arg would make git treat the whole string as the
            // subcommand name. We must split on whitespace and pass each token
            // as a separate argument.
            // NOTE: this is safe because the subcommand has already been
            // validated against the allowlist, and we use structured args
            // (not shell concatenation) so shell metacharacters are inert.
            List<String> cmdArgs = splitGitCommand(trimmed);
            List<String> fullCmd = new ArrayList<>();
            fullCmd.add(gitExe);
            fullCmd.add("-C");
            fullCmd.add(repo.toString());
            fullCmd.addAll(cmdArgs);

            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.redirectErrorStream(false);
            pb.directory(repo.toFile());

            Process process = pb.start();

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.set("error", true);
                result.set("message", "Git command timed out after " + timeout + "s");
                result.set("stdout", truncate(stdout, MAX_OUTPUT_BYTES));
                result.set("stderr", truncate(stderr, MAX_OUTPUT_BYTES));
                return JSONUtil.toJsonPrettyStr(result);
            }

            int exitCode = process.exitValue();
            result.set("command", "git " + trimmed);
            result.set("repo", repo.toString().replace('\\', '/'));
            result.set("exitCode", exitCode);
            result.set("stdout", truncate(stdout, MAX_OUTPUT_BYTES));
            result.set("stderr", truncate(stderr, MAX_OUTPUT_BYTES));
            result.set("write", isWrite);
            result.set("message", exitCode == 0 ? "Success" : "Git exited with code " + exitCode);

            if (exitCode != 0 && !stderr.isBlank()) {
                result.set("error", true);
            }

        } catch (Exception e) {
            log.error("[Git] Failed: {}", e.getMessage(), e);
            result.set("error", true);
            result.set("message", e.getMessage());
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    @vip.mate.tool.ConcurrencyUnsafe("git write operations mutate the repository state")
    @Tool(description = """
            Stage and commit changes in one step. Equivalent to \
            'git add <paths>' followed by 'git commit -m <message>'. \
            Requires user approval. Use git() for other write operations.""")
    public String git_commit(
            @ToolParam(description = "Commit message") String message,
            @ToolParam(description = "Files to stage (space-separated paths), or '.' for all changes") String paths,
            @ToolParam(description = "Repository directory, defaults to workspace root", required = false) String repoPath) {

        JSONObject result = new JSONObject();

        try {
            if (message == null || message.isBlank()) {
                result.set("error", true);
                result.set("message", "Commit message is required");
                return JSONUtil.toJsonPrettyStr(result);
            }
            if (paths == null || paths.isBlank()) {
                paths = ".";
            }

            // Run git add — pass paths as structured args, not string concatenation,
            // to prevent command injection via malicious path values.
            // Sanitize paths: reject shell metacharacters that could break out
            // of the git argument list.
            String safePaths = sanitizeGitPaths(paths);
            if (safePaths == null) {
                result.set("error", true);
                result.set("message", "Invalid paths: contains forbidden characters. Only alphanumeric, /, \\, ., -, _, *, and spaces are allowed.");
                return JSONUtil.toJsonPrettyStr(result);
            }
            String addResult = git("add " + safePaths, repoPath, 30);
            JSONObject addJson = JSONUtil.parseObj(addResult);
            if (addJson.getBool("error", false)) {
                return addResult;
            }

            // Run git commit — pass message as a single structured arg to
            // prevent command injection. The message is NOT shell-escaped;
            // instead we use ProcessBuilder's structured args so the message
            // is passed to git as-is, with no shell interpretation.
            // We execute git commit directly (not via git()) so the message
            // is never shell-parsed.
            String safeMessage = sanitizeCommitMessage(message);
            if (safeMessage == null) {
                result.set("error", true);
                result.set("message", "Invalid commit message: contains forbidden characters (newlines, quotes, backticks, $, ;, |, &, >, <).");
                return JSONUtil.toJsonPrettyStr(result);
            }

            String repoDir = (repoPath == null || repoPath.isBlank()) ? null : repoPath;
            String commitResult = executeGitDirect(repoDir, 60, "commit", "-m", safeMessage);
            return commitResult;

        } catch (Exception e) {
            result.set("error", true);
            result.set("message", e.getMessage());
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    private boolean isAllowedSubcommand(String sub) {
        return sub.matches("status|diff|log|branch|show|add|commit|push|pull|checkout|restore|stash|fetch|reset|rebase|merge|blame|rev-parse|config|remote|tag");
    }

    private boolean isWriteSubcommand(String sub) {
        return sub.matches("add|commit|push|pull|checkout|restore|stash|fetch|reset|rebase|merge|tag|config");
    }

    private String readStream(java.io.InputStream is) {
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
    }

    /**
     * Split a git command string into tokens, respecting double-quoted substrings.
     * e.g. {@code commit -m "hello world"} → {@code ["commit", "-m", "hello world"]}
     * <p>
     * ProcessBuilder does NOT do shell parsing, so we must tokenize here.
     * Quotes are stripped from the resulting tokens.
     */
    private List<String> splitGitCommand(String cmd) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * Sanitize git paths — only allow safe path characters.
     *
     * @return the sanitized path string, or {@code null} if forbidden characters are found
     */
    private String sanitizeGitPaths(String paths) {
        if (paths == null || paths.isBlank()) return ".";
        for (char c : paths.toCharArray()) {
            if (!(Character.isLetterOrDigit(c) || c == '/' || c == '\\' || c == '.'
                    || c == '-' || c == '_' || c == '*' || c == ' ' || c == ':')) {
                return null;
            }
        }
        return paths.trim();
    }

    /**
     * Sanitize commit message — reject characters that could cause issues
     * (log injection, newline splitting) even though ProcessBuilder uses
     * structured args (no shell).
     *
     * @return the sanitized message, or {@code null} if forbidden characters are found
     */
    private String sanitizeCommitMessage(String message) {
        if (message == null || message.isBlank()) return null;
        for (char c : message.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '`' || c == '$' || c == ';'
                    || c == '|' || c == '&' || c == '>' || c == '<') {
                return null;
            }
        }
        return message;
    }

    /**
     * Execute git directly with structured args — bypasses {@link #git}'s
     * string-based interface to avoid any shell parsing of user-provided
     * content (e.g. commit messages that may contain quotes or spaces).
     *
     * @param repoPath       repository directory (relative or absolute), or {@code null} for workspace root
     * @param timeoutSeconds timeout in seconds
     * @param gitArgs        git subcommand and arguments as separate tokens
     * @return structured JSON result
     */
    private String executeGitDirect(String repoPath, int timeoutSeconds, String... gitArgs) {
        JSONObject result = new JSONObject();
        try {
            Path repo;
            try {
                String dir = (repoPath == null || repoPath.isBlank()) ? "." : repoPath;
                repo = vip.mate.tool.guard.WorkspacePathGuard.validatePath(dir);
            } catch (IllegalArgumentException e) {
                result.set("error", true);
                result.set("message", e.getMessage());
                return JSONUtil.toJsonPrettyStr(result);
            }

            if (!Files.exists(repo)) {
                result.set("error", true);
                result.set("message", "Repository path does not exist: " + repo);
                return JSONUtil.toJsonPrettyStr(result);
            }

            int timeout = (timeoutSeconds > 0) ? Math.min(timeoutSeconds, 300) : DEFAULT_TIMEOUT_SECONDS;

            String gitExe = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "git.exe" : "git";

            List<String> fullCmd = new ArrayList<>();
            fullCmd.add(gitExe);
            fullCmd.add("-C");
            fullCmd.add(repo.toString());
            for (String arg : gitArgs) {
                fullCmd.add(arg);
            }

            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.redirectErrorStream(false);
            pb.directory(repo.toFile());

            Process process = pb.start();
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.set("error", true);
                result.set("message", "Git command timed out after " + timeout + "s");
                result.set("stdout", truncate(stdout, MAX_OUTPUT_BYTES));
                result.set("stderr", truncate(stderr, MAX_OUTPUT_BYTES));
                return JSONUtil.toJsonPrettyStr(result);
            }

            int exitCode = process.exitValue();
            result.set("command", "git " + String.join(" ", gitArgs));
            result.set("repo", repo.toString().replace('\\', '/'));
            result.set("exitCode", exitCode);
            result.set("stdout", truncate(stdout, MAX_OUTPUT_BYTES));
            result.set("stderr", truncate(stderr, MAX_OUTPUT_BYTES));
            result.set("write", true);
            result.set("message", exitCode == 0 ? "Success" : "Git exited with code " + exitCode);

            if (exitCode != 0 && !stderr.isBlank()) {
                result.set("error", true);
            }

        } catch (Exception e) {
            log.error("[Git] executeGitDirect failed: {}", e.getMessage(), e);
            result.set("error", true);
            result.set("message", e.getMessage());
        }

        return JSONUtil.toJsonPrettyStr(result);
    }
}
