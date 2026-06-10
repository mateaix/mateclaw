package vip.mate.tool.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 默认工具安全守卫
 * 基于正则模式匹配检测危险操作，采用规则驱动的安全守卫模式
 * <p>
 * 对于本地命令执行工具（如 execute_shell_command），分为两级处理：
 * <ul>
 *   <li>BLOCK：极端破坏性模式（如 rm -rf /、dd if=xxx of=/dev/、mkfs）——直接拒绝，不允许审批覆盖</li>
 *   <li>NEEDS_APPROVAL：一般高风险命令（如 git push --force、chmod 777、DROP TABLE）——需要用户审批后才能执行</li>
 * </ul>
 * 非 shell 工具仍使用原 BLOCK 策略。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class DefaultToolGuard implements ToolGuard {

    /** 被视为本地命令执行工具的工具名集合 */
    private static final Set<String> SHELL_TOOL_NAMES = Set.of(
            "execute_shell_command",
            "shell_execute",
            "run_command",
            "execute_code"
    );

    /** 文件写入类工具 —— 默认需要用户审批 */
    private static final Set<String> FILE_WRITE_TOOL_NAMES = Set.of(
            "write_file",
            "edit_file"
    );

    /** 定时任务变更工具 —— 创建和删除需要用户审批 */
    private static final Set<String> CRON_APPROVAL_TOOL_NAMES = Set.of(
            "create_cron_job",
            "delete_cron_job"
    );

    /** 极端破坏性模式 —— 即使是 shell 工具也直接 BLOCK，不允许审批覆盖 */
    private final List<DangerousPattern> absoluteBlockPatterns;

    /** 一般高风险模式 —— 对 shell 工具走 NEEDS_APPROVAL，对其他工具走 BLOCK */
    private final List<DangerousPattern> highRiskPatterns;

    public DefaultToolGuard() {
        this.absoluteBlockPatterns = loadAbsoluteBlockPatterns();
        this.highRiskPatterns = loadHighRiskPatterns();
    }

    @Override
    public ToolGuardResult check(String toolName, String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return ToolGuardResult.allow();
        }

        String combined = (toolName != null ? toolName + " " : "") + arguments;
        boolean isShellTool = toolName != null && SHELL_TOOL_NAMES.contains(toolName);

        // 第一层：极端破坏性模式 —— 无论什么工具都直接 BLOCK
        for (DangerousPattern pattern : absoluteBlockPatterns) {
            if (pattern.matches(combined)) {
                log.warn("[ToolGuard] BLOCKED (absolute): tool={}, pattern={}, reason={}",
                        toolName, pattern.regex(), pattern.reason());
                return ToolGuardResult.block(pattern.reason(), pattern.regex());
            }
        }

        // 第二层：高风险模式
        for (DangerousPattern pattern : highRiskPatterns) {
            if (pattern.matches(combined)) {
                if (isShellTool) {
                    // Shell 工具命中高风险模式 → 需要用户审批
                    log.info("[ToolGuard] NEEDS_APPROVAL: tool={}, pattern={}, reason={}",
                            toolName, pattern.regex(), pattern.reason());
                    return ToolGuardResult.needsApproval(pattern.reason(), pattern.regex());
                } else {
                    // 非 shell 工具命中高风险模式 → 直接 BLOCK
                    log.warn("[ToolGuard] BLOCKED: tool={}, pattern={}, reason={}",
                            toolName, pattern.regex(), pattern.reason());
                    return ToolGuardResult.block(pattern.reason(), pattern.regex());
                }
            }
        }

        // Shell 工具即使未命中任何模式，也需要审批（任何本地命令执行都是敏感操作）
        if (isShellTool) {
            log.info("[ToolGuard] NEEDS_APPROVAL (shell tool default): tool={}", toolName);
            return ToolGuardResult.needsApproval("Shell command execution requires user approval", "shell_tool_default");
        }

        // 文件写入/编辑工具需要审批
        if (toolName != null && FILE_WRITE_TOOL_NAMES.contains(toolName)) {
            log.info("[ToolGuard] NEEDS_APPROVAL (file write tool): tool={}", toolName);
            return ToolGuardResult.needsApproval("File write/edit operation requires user approval", "file_write_tool_default");
        }

        // 定时任务创建/删除需要审批
        if (toolName != null && CRON_APPROVAL_TOOL_NAMES.contains(toolName)) {
            log.info("[ToolGuard] NEEDS_APPROVAL (cron job tool): tool={}", toolName);
            return ToolGuardResult.needsApproval("Cron job create/delete requires user approval", "cron_tool_default");
        }

        return ToolGuardResult.allow();
    }

    /**
     * 极端破坏性模式 —— 直接 BLOCK，不允许审批覆盖
     * 这些命令一旦执行可能造成不可逆的系统级损坏
     */
    private List<DangerousPattern> loadAbsoluteBlockPatterns() {
        return List.of(
                new DangerousPattern(
                        "rm\\s+-(rf|fr)\\s+/\\s*$",
                        "filesystem_destroy",
                        "Recursive force delete root directory"),
                new DangerousPattern(
                        "mkfs\\b",
                        "filesystem_destroy",
                        "Filesystem formatting command"),
                new DangerousPattern(
                        "dd\\s+if=.+of=/dev/",
                        "filesystem_destroy",
                        "Direct disk write operation"),
                new DangerousPattern(
                        "\\bkill\\s+-9\\s+1\\b",
                        "system_danger",
                        "Kill init/systemd process"),
                new DangerousPattern(
                        "curl.*\\|\\s*(sh|bash|zsh)",
                        "code_injection",
                        "Pipe download to shell execution"),
                new DangerousPattern(
                        "wget.*\\|\\s*(sh|bash|zsh)",
                        "code_injection",
                        "Pipe download to shell execution")
        );
    }

    /**
     * 一般高风险模式 —— shell 工具走 NEEDS_APPROVAL，其他工具走 BLOCK
     */
    private List<DangerousPattern> loadHighRiskPatterns() {
        return List.of(
                // 文件系统
                new DangerousPattern(
                        "rm\\s+-(rf|fr)",
                        "filesystem_destroy",
                        "Recursive force delete"),
                new DangerousPattern(
                        "rm\\s+/",
                        "filesystem_destroy",
                        "Delete from root path"),
                new DangerousPattern(
                        "rmdir\\s+/",
                        "filesystem_destroy",
                        "Delete directory from root path"),

                // SQL
                new DangerousPattern(
                        "DROP\\s+(TABLE|DATABASE|INDEX|VIEW|SCHEMA)",
                        "sql_destroy",
                        "SQL DROP statement"),
                new DangerousPattern(
                        "TRUNCATE\\s+TABLE",
                        "sql_destroy",
                        "SQL TRUNCATE TABLE statement"),
                new DangerousPattern(
                        "DELETE\\s+FROM\\s+\\w+\\s*;",
                        "sql_destroy",
                        "Unconditional DELETE (missing WHERE clause)"),
                new DangerousPattern(
                        "ALTER\\s+TABLE\\s+\\w+\\s+DROP",
                        "sql_destroy",
                        "ALTER TABLE DROP operation"),

                // System
                new DangerousPattern(
                        "\\bshutdown\\b",
                        "system_danger",
                        "System shutdown command"),
                new DangerousPattern(
                        "\\breboot\\b",
                        "system_danger",
                        "System reboot command"),
                new DangerousPattern(
                        "chmod\\s+777",
                        "system_danger",
                        "Overly permissive file permissions"),

                // Code injection
                new DangerousPattern(
                        "eval\\s*\\(",
                        "code_injection",
                        "Dynamic code execution (eval)"),

                // Credentials
                new DangerousPattern(
                        "(password|secret|api[_-]?key|token)\\s*=\\s*['\"]?\\S{8,}",
                        "credential_exposure",
                        "Potential credential exposure"),

                // Git
                new DangerousPattern(
                        "git\\s+push\\s+.*--force",
                        "git_danger",
                        "Git force push"),
                new DangerousPattern(
                        "git\\s+reset\\s+--hard",
                        "git_danger",
                        "Git hard reset")
        );
    }
}
