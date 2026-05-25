package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 内置工具：本地命令执行（跨平台）
 * <p>
 * 安全边界说明：
 * <ul>
 *   <li>所有调用在执行前必须经过 ToolGuard 审批（DefaultToolGuard 对 shell 工具默认返回 NEEDS_APPROVAL）</li>
 *   <li>超时控制：默认 60 秒，超时后强制终止进程</li>
 *   <li>输出长度限制：stdout/stderr 各最多 10000 字节，防止大输出撑爆内存</li>
 *   <li>平台适配：Windows 使用 cmd.exe /D /S /C，Linux/macOS 使用 /bin/sh -c。
 *       风险已通过 ToolGuard 审批机制控制——每次调用都需要用户明确批准。</li>
 *   <li>输出重定向到临时文件而非管道，确保 timeout 不被管道阻塞失效。
 *       参考 MateClaw _execute_subprocess_sync 和 claude-code-haha file-mode 思路。</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class ShellExecuteTool {

    private final vip.mate.i18n.I18nService i18n;

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_BYTES = 10_000;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    @vip.mate.tool.ConcurrencyUnsafe("shell command execution can mutate global state in ways the executor can't reason about")
    @Tool(description = "Execute a shell command on the local server. For running system commands, viewing files, running scripts. "
            + "Uses cmd.exe on Windows, /bin/sh on Linux/macOS. "
            + "Dangerous operations trigger security approval. Returns structured result with exitCode, stdout, stderr, timedOut.")
    public String execute_shell_command(
            @ToolParam(description = "Shell command to execute") String command,
            @ToolParam(description = "Timeout in seconds, default 60", required = false) Integer timeoutSeconds,
            // RFC-063r §2.5: hidden from LLM by JsonSchemaGenerator. Carries the
            // ChatOrigin so the workspace boundary check honors per-agent basePath.
            @Nullable ToolContext ctx) {

        int timeout = (timeoutSeconds != null && timeoutSeconds > 0) ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        // 硬上限：不允许超过 300 秒
        timeout = Math.min(timeout, 300);

        log.info("[ShellExecute] Executing command (os={}): {}, timeout={}s",
                IS_WINDOWS ? "windows" : "unix", truncateForLog(command), timeout);

        JSONObject result = new JSONObject();
        result.set("command", command);

        // Enforce the workspace boundary on the command string itself before
        // the process starts. The pb.directory() set later only constrains
        // the CWD — absolute paths in the command would still reach anywhere.
        try {
            vip.mate.tool.guard.WorkspacePathGuard.validateShellCommand(command, ctx);
        } catch (IllegalArgumentException e) {
            log.warn("[ShellExecute] Sandbox rejected command: {}", e.getMessage());
            result.set("exitCode", -1);
            result.set("stdout", "");
            result.set("stderr", e.getMessage());
            result.set("timedOut", false);
            result.set("error", e.getMessage());
            return JSONUtil.toJsonPrettyStr(result);
        }

        Path stdoutFile = null;
        Path stderrFile = null;

        try {
            // 处理命令中的嵌入换行符（LLM 生成的 JSON 解码后可能包含真实换行）
            // Windows cmd.exe 会在第一个换行处截断命令，Unix sh 也可能误解
            String sanitizedCommand = collapseEmbeddedNewlines(command);

            ProcessBuilder pb = buildShellProcess(sanitizedCommand, ctx);
            // 不继承环境变量中的敏感信息
            pb.environment().keySet().removeIf(key ->
                    key.contains("KEY") || key.contains("SECRET") || key.contains("TOKEN")
                            || key.contains("PASSWORD") || key.contains("CREDENTIAL"));

            // 将 stdout/stderr 重定向到临时文件，而非通过管道读取。
            // 这样 waitFor(timeout) 不会被管道阻塞：
            //   旧方式：readStream(pipe) 阻塞 → waitFor 根本走不到 → timeout 失效
            //   新方式：子进程直接写文件 → waitFor 立即生效 → 超时后读文件取已有输出
            // 同时避免了 Windows 上子进程继承 pipe handle 导致的挂死问题。
            stdoutFile = Files.createTempFile("mc_out_", ".tmp");
            stderrFile = Files.createTempFile("mc_err_", ".tmp");
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());

            Process process = pb.start();

            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);

            if (!completed) {
                // 超时：强制终止进程（树）
                killProcessTree(process);
                log.warn("[ShellExecute] Command timed out after {}s: {}", timeout, truncateForLog(command));
                result.set("exitCode", -1);
                result.set("stdout", readFileTruncated(stdoutFile, MAX_OUTPUT_BYTES));
                result.set("stderr", readFileTruncated(stderrFile, MAX_OUTPUT_BYTES));
                result.set("timedOut", true);
                result.set("message", i18n.msg("tool.shell.error.timeout", timeout));
            } else {
                int exitCode = process.exitValue();
                String stdout = readFileTruncated(stdoutFile, MAX_OUTPUT_BYTES);
                String stderr = readFileTruncated(stderrFile, MAX_OUTPUT_BYTES);
                log.info("[ShellExecute] Command completed: exitCode={}, stdout={}chars, stderr={}chars",
                        exitCode, stdout.length(), stderr.length());
                result.set("exitCode", exitCode);
                result.set("stdout", stdout);
                result.set("stderr", stderr);
                result.set("timedOut", false);
            }

        } catch (Exception e) {
            log.error("[ShellExecute] Command execution failed: {}", e.getMessage(), e);
            result.set("exitCode", -1);
            result.set("stdout", "");
            result.set("stderr", i18n.msg("tool.shell.error.exception", e.getMessage()));
            result.set("timedOut", false);
            result.set("error", e.getMessage());
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    /**
     * 根据当前操作系统构建 shell 进程。
     * Windows: cmd.exe /D /S /C "command"
     *   /D 禁用 AutoRun 注册表项，避免副作用
     *   /S 保留引号原样传递给命令
     * Unix: $SHELL -c command (honors the user's interactive shell)
     *   honors the user's interactive shell so alias resolution / PATH
     *   from the calling environment still apply; falls back to /bin/sh
     *   when $SHELL is unset or points at a non-executable path.
     */
    private static ProcessBuilder buildShellProcess(String command, @Nullable ToolContext ctx) {
        ProcessBuilder pb;
        if (IS_WINDOWS) {
            String winCommand = sanitizeWindowsCommand(command);
            pb = new ProcessBuilder("cmd.exe", "/D", "/S", "/C", winCommand);
        } else {
            String shell = selectPosixShell(System.getenv("SHELL"));
            pb = new ProcessBuilder(shell, "-c", command);
        }

        // Pin the process cwd to the same workspace basePath the validator
        // checked against. Using getWorkingDirectory(ctx) (not the no-arg
        // ThreadLocal-only overload) keeps validation and execution on a
        // single source of truth — otherwise a caller that only sets
        // ToolContext could validate against one basePath and run with the
        // ThreadLocal fallback's basePath.
        java.nio.file.Path workingDir = vip.mate.tool.guard.WorkspacePathGuard.getWorkingDirectory(ctx);
        if (workingDir != null && java.nio.file.Files.isDirectory(workingDir)) {
            pb.directory(workingDir.toFile());
            log.info("[ShellExecute] Working directory set to: {}", workingDir);
        }

        return pb;
    }

    /**
     * Collapse embedded newlines for Windows cmd.exe (where they break parsing),
     * but **leave them alone on Unix**.
     * <p>
     * The original implementation collapsed on every platform under the worry
     * that a stray newline could be misread as a command separator on POSIX
     * shells. In practice that worry is wrong for two common idioms the LLM
     * actually uses to write files: heredocs (`cat &lt;&lt;EOF\nbody\nEOF`) and
     * `python &lt;&lt;EOF` invocations. Both depend on real line breaks to
     * delimit the body from the closing tag — collapsing newlines turns
     * `cat &lt;&lt;EOF\nbody\nEOF` into `cat &lt;&lt;EOF body EOF`, which the
     * shell reads as "open heredoc, immediately close, write 0 bytes." The
     * symptom: every chapter file produced by the agent ends up 0-byte.
     * <p>
     * Unix shell already separates commands with `;` or `&amp;&amp;`, not
     * unquoted newlines, so leaving newlines in is actually safer — and
     * heredocs / multi-line commands now behave as the LLM expects. Windows
     * cmd.exe still gets the collapse because there it really does break.
     */
    private static String collapseEmbeddedNewlines(String command) {
        if (command == null || !command.contains("\n")) {
            return command;
        }
        if (!IS_WINDOWS) {
            // POSIX shell handles newlines correctly within heredocs / scripts
            return command;
        }
        return command.replace("\r\n", " ").replace("\n", " ");
    }

    /**
     * Pick the POSIX shell binary to invoke for a non-Windows tool call.
     *
     * <p>Returns {@code userShellEnv} verbatim when:
     * <ul>
     *   <li>the value is non-blank,</li>
     *   <li>parses as a valid path,</li>
     *   <li>and the resolved binary is executable.</li>
     * </ul>
     *
     * <p>Otherwise falls back to {@code /bin/sh} — the legacy hardcoded
     * default. Important: {@code /bin/sh} on Debian/Ubuntu is dash, which
     * does NOT honor users' bash-isms; the whole point of this method is
     * to prefer the user's actual interactive shell when one is configured.
     */
    static String selectPosixShell(String userShellEnv) {
        return selectPosixShell(userShellEnv, Files::isExecutable);
    }

    /**
     * Test seam — same logic as {@link #selectPosixShell(String)} but with
     * an injectable executable check so unit tests can drive every branch
     * without depending on which shells actually exist on the test runner
     * (Windows CI has no {@code /bin/sh}, POSIX dev hosts have varying
     * shells installed). Production callers go through the single-arg
     * overload above.
     */
    static String selectPosixShell(String userShellEnv, Predicate<Path> executableCheck) {
        if (userShellEnv == null || userShellEnv.isBlank()) {
            return "/bin/sh";
        }
        try {
            Path candidate = Path.of(userShellEnv);
            if (executableCheck.test(candidate)) {
                return userShellEnv;
            }
        } catch (InvalidPathException ignored) {
            // Some exotic $SHELL value that isn't a path — fall through to default.
        }
        return "/bin/sh";
    }

    /**
     * 修复 LLM 常见的 Windows 命令转义问题。
     * LLM 有时会产生 bash 风格的反斜杠转义引号 (\"），
     * 如果命令中所有双引号都被反斜杠转义，则认为是 JSON/bash 伪影并去除反斜杠。
     */
    private static String sanitizeWindowsCommand(String command) {
        if (command.contains("\\\"") && !command.replace("\\\"", "").contains("\"")) {
            return command.replace("\\\"", "\"");
        }
        return command;
    }

    /**
     * 尽力终止进程树。
     * Windows: 使用 taskkill /F /T 终止整个进程树（包括子进程）。
     * Unix: destroyForcibly() 发送 SIGKILL，对于 /bin/sh 启动的子进程基本够用。
     * 注意：Windows 上如果 taskkill 失败，仍回退到 destroyForcibly()，
     * 极端情况下可能有子进程残留（如后台 detached 进程）。
     */
    private static void killProcessTree(Process process) {
        if (IS_WINDOWS) {
            try {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(process.pid()))
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                process.destroyForcibly();
            }
        } else {
            process.destroyForcibly();
        }
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 从临时文件中读取输出，截断到 maxBytes 字节。
     * 进程退出或被杀死后调用，读取子进程已写入文件的内容。
     */
    private static String readFileTruncated(Path file, int maxBytes) {
        try {
            if (file == null || !Files.exists(file)) return "";
            long size = Files.size(file);
            if (size == 0) return "";

            boolean truncated = size > maxBytes;
            try (InputStream is = Files.newInputStream(file)) {
                byte[] data = is.readNBytes(maxBytes);
                String content = new String(data, StandardCharsets.UTF_8);
                if (truncated) {
                    content += "\n... [output truncated, exceeds " + maxBytes + " byte limit]";
                }
                return content;
            }
        } catch (IOException e) {
            return "[read output failed: " + e.getMessage() + "]";
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
    }

    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
