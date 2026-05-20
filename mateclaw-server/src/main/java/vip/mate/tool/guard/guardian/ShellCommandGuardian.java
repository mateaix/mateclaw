package vip.mate.tool.guard.guardian;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell 命令安全守卫
 * <p>
 * 检测 shell 工具调用中的危险命令模式。
 * 优先从 ToolGuardRuleRegistry 加载 DB 规则；若无规则则回退到内置硬编码规则。
 */
@Slf4j
@Component
public class ShellCommandGuardian implements ToolGuardGuardian {

    private static final Set<String> SHELL_TOOL_NAMES = Set.of(
            "execute_shell_command", "shell_execute", "run_command"
    );

    private static final Map<String, Pattern> COMPILED_CACHE = new ConcurrentHashMap<>();

    private final ToolGuardRuleRegistry ruleRegistry;
    private final List<ShellRule> builtinRules;

    public ShellCommandGuardian(ToolGuardRuleRegistry ruleRegistry) {
        this.ruleRegistry = ruleRegistry;
        this.builtinRules = loadBuiltinRules();
    }

    @Override
    public boolean supports(ToolInvocationContext context) {
        if (context.toolName() == null || !SHELL_TOOL_NAMES.contains(context.toolName())) {
            return false;
        }
        // Mutual-exclusion gate with DbRuleGuardian: when DB rules
        // exist for this shell tool, DbRuleGuardian evaluates them
        // and we skip — keeping the two paths strictly disjoint.
        // Empty DB rules → we own this invocation and fall through to
        // the hard-coded built-in shell rules below.
        return ruleRegistry.getRulesForTool(context.toolName()).isEmpty();
    }

    @Override
    public int priority() {
        return 200; // 高优先级
    }

    @Override
    public List<GuardFinding> evaluate(ToolInvocationContext context) {
        String combined = buildMatchInput(context);
        if (combined == null || combined.isEmpty()) {
            return List.of();
        }

        List<GuardFinding> findings = new ArrayList<>();

        // 优先使用 DB 规则
        List<ToolGuardRuleEntity> dbRules = ruleRegistry.getRulesForTool(context.toolName());
        if (!dbRules.isEmpty()) {
            for (ToolGuardRuleEntity rule : dbRules) {
                Pattern pattern = ruleRegistry.getCompiledPattern(rule.getPattern());
                Matcher matcher = pattern.matcher(combined);
                if (matcher.find()) {
                    // 检查排除模式
                    if (rule.getExcludePattern() != null && !rule.getExcludePattern().isBlank()) {
                        Pattern exclude = ruleRegistry.getCompiledExcludePattern(rule.getExcludePattern());
                        if (exclude.matcher(combined).find()) continue;
                    }
                    String snippet = extractSnippet(combined, matcher.start(), 40);
                    findings.add(new GuardFinding(
                            rule.getRuleId(),
                            GuardSeverity.valueOf(rule.getSeverity()),
                            GuardCategory.valueOf(rule.getCategory()),
                            rule.getName(),
                            rule.getDescription(),
                            rule.getRemediation(),
                            context.toolName(),
                            rule.getParamName() != null ? rule.getParamName() : "command",
                            rule.getPattern(),
                            snippet,
                            parseDecision(rule.getDecision())
                    ));
                }
            }
        } else {
            // 回退到硬编码内置规则
            for (ShellRule rule : builtinRules) {
                Pattern pattern = COMPILED_CACHE.computeIfAbsent(rule.pattern,
                        r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE));
                Matcher matcher = pattern.matcher(combined);
                if (matcher.find()) {
                    String snippet = extractSnippet(combined, matcher.start(), 40);
                    findings.add(new GuardFinding(
                            rule.ruleId,
                            rule.severity,
                            rule.category,
                            rule.title,
                            rule.description,
                            rule.remediation,
                            context.toolName(),
                            "command",
                            rule.pattern,
                            snippet
                    ));
                }
            }
        }
        return findings;
    }

    private String buildMatchInput(ToolInvocationContext context) {
        String raw = context.rawArguments();
        if (raw == null || raw.isEmpty()) return null;
        return (context.toolName() != null ? context.toolName() + " " : "") + raw;
    }

    private GuardDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return GuardDecision.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String extractSnippet(String input, int matchStart, int contextLen) {
        int start = Math.max(0, matchStart - contextLen / 2);
        int end = Math.min(input.length(), matchStart + contextLen / 2);
        return input.substring(start, end);
    }

    // ==================== 内置规则 ====================

    private List<ShellRule> loadBuiltinRules() {
        List<ShellRule> list = new ArrayList<>();

        // === 极端破坏性（CRITICAL）===
        list.add(new ShellRule("SHELL_RM_RF_ROOT",
                "rm\\s+-(rf|fr)\\s+/\\s*$",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION,
                "递归强制删除根目录",
                "检测到 rm -rf / 命令，将销毁整个文件系统",
                "请指定具体目录路径而非根目录"));

        list.add(new ShellRule("SHELL_MKFS",
                "mkfs\\b",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION,
                "文件系统格式化",
                "检测到 mkfs 命令，将格式化文件系统",
                "确认目标设备后手动执行"));

        list.add(new ShellRule("SHELL_DD_DEV",
                "dd\\s+if=.+of=/dev/",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION,
                "直接磁盘写入",
                "检测到 dd 写入 /dev/ 设备，可能损坏磁盘",
                "确认目标设备后手动执行"));

        list.add(new ShellRule("SHELL_KILL_INIT",
                "\\bkill\\s+-9\\s+1\\b",
                GuardSeverity.CRITICAL, GuardCategory.RESOURCE_ABUSE,
                "杀死 init/systemd",
                "检测到 kill -9 1，将导致系统崩溃",
                "使用 systemctl 管理服务"));

        list.add(new ShellRule("SHELL_CURL_PIPE_SH",
                "curl.*\\|\\s*(sh|bash|zsh)",
                GuardSeverity.CRITICAL, GuardCategory.CODE_EXECUTION,
                "管道下载执行",
                "检测到 curl | sh 模式，远程代码将被直接执行",
                "先下载文件审查内容，再手动执行"));

        list.add(new ShellRule("SHELL_WGET_PIPE_SH",
                "wget.*\\|\\s*(sh|bash|zsh)",
                GuardSeverity.CRITICAL, GuardCategory.CODE_EXECUTION,
                "管道下载执行",
                "检测到 wget | sh 模式，远程代码将被直接执行",
                "先下载文件审查内容，再手动执行"));

        // 额外 CRITICAL：fork bomb
        list.add(new ShellRule("SHELL_FORK_BOMB",
                ":\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\}\\s*;\\s*:",
                GuardSeverity.CRITICAL, GuardCategory.RESOURCE_ABUSE,
                "Fork Bomb",
                "检测到 fork bomb 模式，将耗尽系统资源",
                "此命令无正当用途，请勿执行"));

        // 额外 CRITICAL：reverse shell
        list.add(new ShellRule("SHELL_REVERSE_SHELL",
                "(/dev/tcp|\\bnc\\s+-e\\b|\\bncat\\s+-e\\b|\\bsocat\\s+EXEC:)",
                GuardSeverity.CRITICAL, GuardCategory.NETWORK_ABUSE,
                "反向 Shell",
                "检测到反向 Shell 连接模式",
                "此命令可能被用于远程控制，请勿执行"));

        // === 高风险（HIGH）===
        list.add(new ShellRule("SHELL_RM",
                "(^|[;&|]|\\s)rm\\s",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "rm 删除命令",
                "检测到 rm 删除操作，可能导致文件永久丢失",
                "请确认要删除的文件列表，考虑使用 trash 替代 rm"));

        list.add(new ShellRule("SHELL_FIND_DELETE",
                "find\\s.*\\s-delete\\b",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "find -delete 删除命令",
                "检测到 find -delete 操作，可能导致文件永久丢失",
                "请确认要删除的文件列表，考虑使用 trash 替代 -delete"));

        list.add(new ShellRule("SHELL_RM_RF",
                "rm\\s+-(rf|fr)",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "递归强制删除",
                "检测到 rm -rf 操作，可能删除重要文件",
                "使用交互式删除 rm -ri 或指定具体文件"));

        list.add(new ShellRule("SHELL_RM_ROOT",
                "rm\\s+/",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "从根路径删除",
                "检测到从根路径开始的删除操作",
                "请指定具体路径"));

        list.add(new ShellRule("SHELL_RMDIR_ROOT",
                "rmdir\\s+/",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "从根路径删除目录",
                "检测到从根路径开始的目录删除",
                "请指定具体路径"));

        list.add(new ShellRule("SHELL_SQL_DROP",
                "DROP\\s+(TABLE|DATABASE|INDEX|VIEW|SCHEMA)",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "SQL DROP 操作",
                "检测到 SQL DROP 语句，将永久删除数据",
                "请先备份数据再执行"));

        list.add(new ShellRule("SHELL_SQL_TRUNCATE",
                "TRUNCATE\\s+TABLE",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "SQL TRUNCATE 操作",
                "检测到 SQL TRUNCATE TABLE 语句，将清空表数据",
                "请先备份数据再执行"));

        list.add(new ShellRule("SHELL_SQL_DELETE_ALL",
                "DELETE\\s+FROM\\s+\\w+\\s*;",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "SQL 无条件 DELETE",
                "检测到缺少 WHERE 子句的 DELETE 语句",
                "请添加 WHERE 条件限制删除范围"));

        list.add(new ShellRule("SHELL_SQL_ALTER_DROP",
                "ALTER\\s+TABLE\\s+\\w+\\s+DROP",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "SQL ALTER TABLE DROP",
                "检测到 ALTER TABLE DROP 操作",
                "请先备份数据再执行"));

        list.add(new ShellRule("SHELL_SHUTDOWN",
                "\\bshutdown\\b",
                GuardSeverity.HIGH, GuardCategory.RESOURCE_ABUSE,
                "系统关机",
                "检测到 shutdown 命令",
                "请确认是否需要关机"));

        list.add(new ShellRule("SHELL_REBOOT",
                "\\breboot\\b",
                GuardSeverity.HIGH, GuardCategory.RESOURCE_ABUSE,
                "系统重启",
                "检测到 reboot 命令",
                "请确认是否需要重启"));

        list.add(new ShellRule("SHELL_CHMOD_777",
                "chmod\\s+777",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION,
                "过度宽松权限",
                "检测到 chmod 777，将授予所有人完全权限",
                "使用最小必要权限，如 chmod 755 或 chmod 644"));

        list.add(new ShellRule("SHELL_EVAL",
                "eval\\s*\\(",
                GuardSeverity.HIGH, GuardCategory.CODE_EXECUTION,
                "动态代码执行",
                "检测到 eval() 调用，可能执行不可信代码",
                "避免使用 eval，使用更安全的替代方案"));

        list.add(new ShellRule("SHELL_GIT_FORCE_PUSH",
                "git\\s+push\\s+.*--force",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "Git 强制推送",
                "检测到 git push --force，可能覆盖远程历史",
                "使用 --force-with-lease 替代"));

        list.add(new ShellRule("SHELL_GIT_RESET_HARD",
                "git\\s+reset\\s+--hard",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION,
                "Git 硬重置",
                "检测到 git reset --hard，将丢失未提交的更改",
                "先用 git stash 保存更改"));

        // 额外 HIGH: crontab, authorized_keys, sudoers
        list.add(new ShellRule("SHELL_CRONTAB",
                "\\bcrontab\\b",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION,
                "定时任务修改",
                "检测到 crontab 操作，可能添加持久化后门",
                "请确认定时任务内容"));

        list.add(new ShellRule("SHELL_AUTHORIZED_KEYS",
                "authorized_keys",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION,
                "SSH 密钥修改",
                "检测到对 authorized_keys 文件的操作",
                "请确认 SSH 密钥变更"));

        list.add(new ShellRule("SHELL_SUDOERS",
                "/etc/sudoers",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION,
                "sudo 权限修改",
                "检测到对 /etc/sudoers 的操作",
                "请使用 visudo 命令修改"));

        // 额外 HIGH: base64 decode + exec
        list.add(new ShellRule("SHELL_OBFUSCATED_EXEC",
                "base64\\s+-d.*\\|\\s*(bash|sh)",
                GuardSeverity.HIGH, GuardCategory.CODE_EXECUTION,
                "混淆代码执行",
                "检测到 base64 解码后管道执行",
                "先解码查看内容再手动执行"));

        return List.copyOf(list);
    }

    record ShellRule(
            String ruleId,
            String pattern,
            GuardSeverity severity,
            GuardCategory category,
            String title,
            String description,
            String remediation
    ) {}
}
