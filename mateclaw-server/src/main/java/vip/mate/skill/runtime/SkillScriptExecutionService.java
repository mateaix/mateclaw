package vip.mate.skill.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 技能脚本执行服务
 * 安全执行 scripts/ 目录下的脚本
 * <p>
 * 输出重定向到临时文件，确保 timeout 不被管道阻塞失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillScriptExecutionService {

    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_BYTES = 50_000;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    /** Supported inline-code languages mapped to the temp-file extension. */
    private static final Map<String, String> LANGUAGE_EXTENSIONS = Map.of(
            "python", ".py",
            "py", ".py",
            "bash", ".sh",
            "sh", ".sh",
            "shell", ".sh",
            "node", ".js",
            "javascript", ".js",
            "js", ".js");

    /**
     * 执行脚本（兼容签名 — 不注入额外 env vars）
     *
     * @param scriptPath 脚本绝对路径（已验证安全）
     * @param args 脚本参数
     * @return 执行结果
     */
    public ScriptResult execute(Path scriptPath, List<String> args) {
        return execute(scriptPath, args, Collections.emptyMap());
    }

    /**
     * 执行脚本，附加 env vars 到子进程环境（RFC-091 settings bridge）。
     * 用于把 skill 在 mate_skill_secret 里存的解密 secret 注入到脚本运行
     * 时环境，让 SKILL.md 里 {@code $AIRTABLE_API_KEY} 等引用自然解析。
     *
     * <p>{@code envVars} 中的键值会 OVERRIDE 父进程同名环境变量；空值跳过。
     *
     * @param scriptPath 脚本绝对路径（已验证安全）
     * @param args 脚本参数
     * @param envVars 要注入子进程环境的额外键值对，可为空
     * @return 执行结果
     */
    public ScriptResult execute(Path scriptPath, List<String> args, Map<String, String> envVars) {
        return executeResolved(scriptPath, args, envVars, DEFAULT_TIMEOUT_SECONDS, false);
    }

    /**
     * Execute LLM-generated source code inline, without a pre-existing script file.
     * <p>
     * Materializes {@code code} into a temporary file (extension chosen from
     * {@code language}) inside {@code workingDir}, runs it through the same
     * interpreter-selection + timeout + output-capping + env-injection path as
     * {@link #execute(Path, List, Map)}, then deletes the temp file.
     *
     * <p>This is what makes a documentation-only skill (a SKILL.md with no
     * {@code scripts:} entries) runnable: the agent reads the instructions,
     * generates code, and runs it here.
     *
     * @param language       one of python / bash / node (and aliases); selects the interpreter
     * @param code           the source code to run; must be non-blank
     * @param workingDir     directory the temp file is written to and the process cwd. When {@code null}
     *                       a private temp scratch directory is created and removed afterward; when
     *                       non-null it must be an existing directory (e.g. a skill or workspace dir)
     * @param args           optional positional arguments passed to the program
     * @param envVars        optional env vars injected into the subprocess (e.g. decrypted skill secrets)
     * @param timeoutSeconds optional timeout override; clamped to (0, {@value #MAX_TIMEOUT_SECONDS}], defaults to {@value #DEFAULT_TIMEOUT_SECONDS}
     * @return execution result
     */
    public ScriptResult executeCode(String language, String code, Path workingDir,
                                    List<String> args, Map<String, String> envVars, Long timeoutSeconds) {
        if (code == null || code.isBlank()) {
            return ScriptResult.error(-1, "No code supplied");
        }
        String ext = language == null ? null
                : LANGUAGE_EXTENSIONS.get(language.trim().toLowerCase(Locale.ROOT));
        if (ext == null) {
            return ScriptResult.error(-1, "Unsupported language: " + language
                    + ". Supported: python, bash, node");
        }
        if (workingDir != null && !Files.isDirectory(workingDir)) {
            return ScriptResult.error(-1, "Working directory does not exist: " + workingDir);
        }

        long timeout = DEFAULT_TIMEOUT_SECONDS;
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            timeout = Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
        }

        // No caller-supplied directory (e.g. an agent with no workspace base path):
        // run in a private scratch directory and remove it afterward. Mirrors the
        // shell tool tolerating a null working directory rather than failing.
        Path scratchDir = null;
        Path codeFile = null;
        try {
            Path dir = workingDir;
            if (dir == null) {
                scratchDir = Files.createTempDirectory("mc_code_ws_");
                dir = scratchDir;
            }
            // Write the code into the working dir so the process cwd matches the
            // file location — relative paths in the generated code resolve as the
            // author expects, and skill-scoped runs stay inside the skill dir.
            codeFile = Files.createTempFile(dir, "mc_code_", ext);
            Files.writeString(codeFile, code, StandardCharsets.UTF_8);
            if (!IS_WINDOWS && ext.equals(".sh")) {
                codeFile.toFile().setExecutable(true);
            }
            // Scrub sensitive host env vars: the code is LLM-authored, so it must
            // not inherit the server's API keys / tokens. Skill secrets, when
            // supplied via envVars, are re-added on top.
            return executeResolved(codeFile, args, envVars, timeout, true);
        } catch (IOException e) {
            log.error("Failed to materialize inline code: {}", e.getMessage());
            return ScriptResult.error(-1, "Failed to write code file: " + e.getMessage());
        } finally {
            deleteQuietly(codeFile);
            deleteDirQuietly(scratchDir);
        }
    }

    private ScriptResult executeResolved(Path scriptPath, List<String> args, Map<String, String> envVars,
                                         long timeoutSeconds, boolean scrubSensitiveEnv) {
        if (!Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            return ScriptResult.error(-1, "Script not found: " + scriptPath);
        }

        Path stdoutFile = null;
        Path stderrFile = null;

        try {
            // 构建命令（结构化参数，避免 shell 注入）
            List<String> command = new ArrayList<>();

            // 根据文件扩展名选择解释器（跨平台适配）
            String fileName = scriptPath.getFileName().toString();
            if (fileName.endsWith(".py")) {
                // Windows 通常只有 python，没有 python3
                command.add(IS_WINDOWS ? "python" : "python3");
            } else if (fileName.endsWith(".sh")) {
                if (IS_WINDOWS) {
                    return ScriptResult.error(-1,
                            "Shell scripts (.sh) are not supported on Windows. " +
                            "Consider providing a .bat or .ps1 alternative.");
                }
                command.add("bash");
            } else if (fileName.endsWith(".bat") || fileName.endsWith(".cmd")) {
                if (!IS_WINDOWS) {
                    return ScriptResult.error(-1,
                            "Batch scripts (.bat/.cmd) are only supported on Windows.");
                }
                command.add("cmd.exe");
                command.add("/D");
                command.add("/C");
            } else if (fileName.endsWith(".ps1")) {
                command.add("powershell");
                command.add("-ExecutionPolicy");
                command.add("Bypass");
                command.add("-File");
            } else if (fileName.endsWith(".js")) {
                command.add("node");
            } else {
                if (!IS_WINDOWS && !Files.isExecutable(scriptPath)) {
                    return ScriptResult.error(-1, "Script not executable: " + fileName);
                }
            }

            command.add(scriptPath.toString());
            if (args != null) {
                command.addAll(args);
            }

            // 重定向到临时文件，使 waitFor(timeout) 不被管道阻塞
            stdoutFile = Files.createTempFile("mc_script_out_", ".tmp");
            stderrFile = Files.createTempFile("mc_script_err_", ".tmp");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(scriptPath.getParent().toFile());
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());
            // Strip secrets from the inherited environment before injecting the
            // caller's own env. Used for LLM-authored inline code so it never
            // sees the server's API keys / tokens via process inheritance.
            if (scrubSensitiveEnv) {
                pb.environment().keySet().removeIf(key ->
                        key.contains("KEY") || key.contains("SECRET") || key.contains("TOKEN")
                                || key.contains("PASSWORD") || key.contains("CREDENTIAL"));
            }
            // Inject per-skill secrets / settings as env vars.
            // pb.environment() inherits the parent process env; putAll
            // OVERRIDES same-named entries with the supplied values.
            // Null / blank values are skipped to avoid clearing
            // legitimate parent env vars.
            if (envVars != null && !envVars.isEmpty()) {
                Map<String, String> processEnv = pb.environment();
                for (Map.Entry<String, String> e : envVars.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) continue;
                    if (e.getValue() == null) continue;
                    processEnv.put(e.getKey(), e.getValue());
                }
            }

            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                killProcess(process);
                String stdout = readFileTruncated(stdoutFile, MAX_OUTPUT_BYTES);
                String stderr = readFileTruncated(stderrFile, MAX_OUTPUT_BYTES);
                String timeoutMsg = "[timeout after " + timeoutSeconds + "s]";
                stderr = stderr.isEmpty() ? timeoutMsg : stderr + "\n" + timeoutMsg;
                return new ScriptResult(-1, stdout, stderr);
            }

            int exitCode = process.exitValue();
            String stdout = readFileTruncated(stdoutFile, MAX_OUTPUT_BYTES);
            String stderr = readFileTruncated(stderrFile, MAX_OUTPUT_BYTES);
            return new ScriptResult(exitCode, stdout, stderr);

        } catch (Exception e) {
            log.error("Failed to execute script {}: {}", scriptPath, e.getMessage());
            return ScriptResult.error(-1, "Execution error: " + e.getMessage());
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }
    }

    private static void killProcess(Process process) {
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
                    content += "\n... [输出已截断，超过 " + maxBytes + " 字节限制]";
                }
                return content;
            }
        } catch (IOException e) {
            return "[读取输出失败: " + e.getMessage() + "]";
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
    }

    /** Recursively remove a scratch directory created for an inline-code run. */
    private static void deleteDirQuietly(Path dir) {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ScriptResult {
        private int exitCode;
        private String stdout;
        private String stderr;

        public static ScriptResult error(int code, String message) {
            return new ScriptResult(code, "", message);
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
