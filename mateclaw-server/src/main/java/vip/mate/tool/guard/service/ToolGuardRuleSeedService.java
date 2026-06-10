package vip.mate.tool.guard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.model.GuardCategory;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolGuardConfigEntity;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.repository.ToolGuardConfigMapper;
import vip.mate.tool.guard.repository.ToolGuardRuleMapper;

import vip.mate.i18n.I18nService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 规则种子服务
 * <p>
 * 启动时完成三项工作：
 * <ol>
 *   <li>迁移旧版工具名（类名 → @Tool 方法名）+ 清理旧 legacy 规则</li>
 *   <li>按 rule_id 逐条 upsert 内置规则（不存在则插入，已存在则同步更新）</li>
 *   <li>迁移 guardedToolsJson 中的旧工具名</li>
 * </ol>
 */
@Slf4j
@Component
@Order(110) // Schema 由 Flyway 管理，在 Flyway 迁移完成后执行
@RequiredArgsConstructor
public class ToolGuardRuleSeedService implements ApplicationRunner {

    private final ToolGuardRuleMapper ruleMapper;
    private final ToolGuardConfigMapper configMapper;
    private final I18nService i18n;

    /** 旧类名 → 新 @Tool 方法名 */
    private static final Map<String, String> TOOL_NAME_RENAMES = Map.of(
            "ShellExecuteTool", "execute_shell_command",
            "WriteFileTool", "write_file",
            "EditFileTool", "edit_file"
    );

    /** 旧 SQL 种子中的 legacy rule_id，已被 Java 种子的新规则完全覆盖 */
    private static final Set<String> LEGACY_RULE_IDS = Set.of(
            "write_file_any",
            "edit_file_any",
            "shell_rm_approval",
            "shell_rm_rf_block",
            "shell_write_system_file",
            "shell_chmod_777"
    );

    @Override
    public void run(ApplicationArguments args) {
        migrateOldData();
        seedBuiltinRules();
    }

    // ==================== 旧数据迁移 ====================

    /**
     * 将 DB 中旧版数据统一迁移：
     * <ul>
     *   <li>rule 表旧工具名（类名 → @Tool 方法名）</li>
     *   <li>清理 legacy rule_id（旧 SQL 种子残留）</li>
     *   <li>config 表 guardedToolsJson 中的旧工具名</li>
     * </ul>
     */
    private void migrateOldData() {
        try {
            // 1. 迁移 rule 表旧工具名
            for (var entry : TOOL_NAME_RENAMES.entrySet()) {
                int updated = ruleMapper.update(null,
                        new LambdaUpdateWrapper<ToolGuardRuleEntity>()
                                .eq(ToolGuardRuleEntity::getToolName, entry.getKey())
                                .set(ToolGuardRuleEntity::getToolName, entry.getValue()));
                if (updated > 0) {
                    log.info("[RuleSeed] Migrated {} rules: {} -> {}", updated, entry.getKey(), entry.getValue());
                }
            }

            // 2. 清理旧 SQL 种子残留的 legacy 规则
            cleanupLegacyRules();

            // 3. 迁移 config 表 guardedToolsJson
            migrateGuardedToolsJson();
        } catch (Exception e) {
            log.warn("[RuleSeed] Migration failed (table may not exist): {}", e.getMessage());
        }
    }

    /**
     * 删除旧 SQL 种子中残留的 legacy builtin 规则。
     * 这些规则的 rule_id 与新 Java 种子不重叠，增量升级后会形成冗余重复。
     */
    private void cleanupLegacyRules() {
        for (String legacyId : LEGACY_RULE_IDS) {
            int deleted = ruleMapper.delete(
                    new LambdaQueryWrapper<ToolGuardRuleEntity>()
                            .eq(ToolGuardRuleEntity::getRuleId, legacyId)
                            .eq(ToolGuardRuleEntity::getBuiltin, true));
            if (deleted > 0) {
                log.info("[RuleSeed] Removed legacy rule: {}", legacyId);
            }
        }
    }

    private void migrateGuardedToolsJson() {
        try {
            List<ToolGuardConfigEntity> configs = configMapper.selectList(null);
            for (ToolGuardConfigEntity config : configs) {
                String json = config.getGuardedToolsJson();
                if (json == null || json.isBlank()) continue;

                String updated = json;
                for (var entry : TOOL_NAME_RENAMES.entrySet()) {
                    updated = updated.replace("\"" + entry.getKey() + "\"", "\"" + entry.getValue() + "\"");
                }
                if (!updated.equals(json)) {
                    config.setGuardedToolsJson(updated);
                    configMapper.updateById(config);
                    log.info("[RuleSeed] Migrated guardedToolsJson: {} -> {}", json, updated);
                }
            }
        } catch (Exception e) {
            log.debug("[RuleSeed] guardedToolsJson migration skipped: {}", e.getMessage());
        }
    }

    // ==================== 规则种子 ====================

    /**
     * 按 rule_id 逐条 upsert 内置规则：
     * <ul>
     *   <li>不存在 → 插入</li>
     *   <li>已存在 → 同步更新 pattern / severity / decision / priority / toolName 等字段</li>
     * </ul>
     * 这样后续版本修正了 regex 或 severity，已有部署也能在重启时自动拿到更新。
     */
    void seedBuiltinRules() {
        try {
            // 加载已有 builtin 规则（按 rule_id 索引）
            List<ToolGuardRuleEntity> existingList = ruleMapper.selectList(
                    new LambdaQueryWrapper<ToolGuardRuleEntity>()
                            .eq(ToolGuardRuleEntity::getBuiltin, true));
            Map<String, ToolGuardRuleEntity> existingMap = existingList.stream()
                    .collect(Collectors.toMap(ToolGuardRuleEntity::getRuleId, e -> e, (a, b) -> a));

            List<ToolGuardRuleEntity> rules = buildBuiltinRules();
            int inserted = 0;
            int updated = 0;
            int unchanged = 0;

            for (ToolGuardRuleEntity rule : rules) {
                ToolGuardRuleEntity existing = existingMap.get(rule.getRuleId());
                if (existing == null) {
                    // 新规则 → 插入
                    try {
                        ruleMapper.insert(rule);
                        inserted++;
                    } catch (Exception e) {
                        log.debug("[RuleSeed] Rule {} insert failed: {}", rule.getRuleId(), e.getMessage());
                    }
                } else if (needsUpdate(existing, rule)) {
                    // 已存在但内容字段有变化 → 同步代码侧拥有的字段（content fields）。
                    // 严格保留用户侧拥有的策略字段（severity / decision / priority / enabled
                    // / excludePattern）—— 这些一旦用户在 UI 上调整，重启后不应被覆盖。
                    ruleMapper.update(null,
                            new LambdaUpdateWrapper<ToolGuardRuleEntity>()
                                    .eq(ToolGuardRuleEntity::getRuleId, rule.getRuleId())
                                    .set(ToolGuardRuleEntity::getName, rule.getName())
                                    .set(ToolGuardRuleEntity::getDescription, rule.getDescription())
                                    .set(ToolGuardRuleEntity::getPattern, rule.getPattern())
                                    .set(ToolGuardRuleEntity::getCategory, rule.getCategory())
                                    .set(ToolGuardRuleEntity::getToolName, rule.getToolName())
                                    .set(ToolGuardRuleEntity::getRemediation, rule.getRemediation()));
                    updated++;
                } else {
                    unchanged++;
                }
            }
            log.info("[RuleSeed] Builtin rules: {} inserted, {} updated, {} unchanged",
                    inserted, updated, unchanged);
        } catch (Exception e) {
            log.warn("[RuleSeed] Failed to seed rules (table may not exist): {}", e.getMessage());
        }
    }

    /**
     * 判断已有 builtin 规则是否需要更新。
     * <p>
     * 只比较"内容字段"（代码侧拥有，应当随版本升级同步）：
     * name / description / pattern / category / toolName / remediation。
     * <p>
     * 故意不比较"策略字段"（用户侧拥有，UI 可调）：severity / decision / priority / enabled / excludePattern。
     * 这样用户把某条 builtin 规则的 decision 从 NEEDS_APPROVAL 改成 BLOCK、或者关闭某条规则，
     * 重启不会把改动覆盖回种子初值。
     */
    private boolean needsUpdate(ToolGuardRuleEntity existing, ToolGuardRuleEntity expected) {
        return !Objects.equals(existing.getName(), expected.getName())
                || !Objects.equals(existing.getDescription(), expected.getDescription())
                || !Objects.equals(existing.getPattern(), expected.getPattern())
                || !Objects.equals(existing.getCategory(), expected.getCategory())
                || !Objects.equals(existing.getToolName(), expected.getToolName())
                || !Objects.equals(existing.getRemediation(), expected.getRemediation());
    }

    private List<ToolGuardRuleEntity> buildBuiltinRules() {
        List<ToolGuardRuleEntity> rules = new ArrayList<>();

        // === CRITICAL Shell Rules ===
        rules.add(rule("SHELL_RM_RF_ROOT", gn("SHELL_RM_RF_ROOT"), "rm\\s+-(rf|fr)\\s+/\\s*$",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION, "BLOCK",
                "execute_shell_command", gf("SHELL_RM_RF_ROOT"), 200));

        rules.add(rule("SHELL_MKFS", gn("SHELL_MKFS"), "mkfs\\b",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION, "BLOCK",
                "execute_shell_command", gf("SHELL_MKFS"), 200));

        rules.add(rule("SHELL_DD_DEV", gn("SHELL_DD_DEV"), "dd\\s+if=.+of=/dev/",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION, "BLOCK",
                "execute_shell_command", gf("SHELL_DD_DEV"), 200));

        rules.add(rule("SHELL_KILL_INIT", gn("SHELL_KILL_INIT"), "\\bkill\\s+-9\\s+1\\b",
                GuardSeverity.CRITICAL, GuardCategory.RESOURCE_ABUSE, "BLOCK",
                "execute_shell_command", gf("SHELL_KILL_INIT"), 200));

        rules.add(rule("SHELL_CURL_PIPE_SH", gn("SHELL_CURL_PIPE_SH"), "curl.*\\|\\s*(sh|bash|zsh)",
                GuardSeverity.CRITICAL, GuardCategory.CODE_EXECUTION, "BLOCK",
                "execute_shell_command", gf("SHELL_CURL_PIPE_SH"), 200));

        rules.add(rule("SHELL_WGET_PIPE_SH", gn("SHELL_WGET_PIPE_SH"), "wget.*\\|\\s*(sh|bash|zsh)",
                GuardSeverity.CRITICAL, GuardCategory.CODE_EXECUTION, "BLOCK",
                "execute_shell_command", gf("SHELL_WGET_PIPE_SH"), 200));

        rules.add(rule("SHELL_FORK_BOMB", gn("SHELL_FORK_BOMB"), ":\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\}\\s*;\\s*:",
                GuardSeverity.CRITICAL, GuardCategory.RESOURCE_ABUSE, "BLOCK",
                "execute_shell_command", gf("SHELL_FORK_BOMB"), 200));

        rules.add(rule("SHELL_REVERSE_SHELL", gn("SHELL_REVERSE_SHELL"), "(/dev/tcp|\\bnc\\s+-e\\b|\\bncat\\s+-e\\b|\\bsocat\\s+EXEC:)",
                GuardSeverity.CRITICAL, GuardCategory.NETWORK_ABUSE, "BLOCK",
                "execute_shell_command", gf("SHELL_REVERSE_SHELL"), 200));

        // === HIGH Shell Rules ===
        rules.add(rule("SHELL_RM", gn("SHELL_RM"), "(^|[;&|]|\\s)rm\\s",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_RM"), 150));

        rules.add(rule("SHELL_RM_RF", gn("SHELL_RM_RF"), "rm\\s+-(rf|fr)",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_RM_RF"), 150));

        rules.add(rule("SHELL_RM_ROOT", gn("SHELL_RM_ROOT"), "rm\\s+/",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_RM_ROOT"), 150));

        rules.add(rule("SHELL_RMDIR_ROOT", gn("SHELL_RMDIR_ROOT"), "rmdir\\s+/",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_RMDIR_ROOT"), 150));

        rules.add(rule("SHELL_SQL_DROP", gn("SHELL_SQL_DROP"), "DROP\\s+(TABLE|DATABASE|INDEX|VIEW|SCHEMA)",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                null, gf("SHELL_SQL_DROP"), 150));

        rules.add(rule("SHELL_SQL_TRUNCATE", gn("SHELL_SQL_TRUNCATE"), "TRUNCATE\\s+TABLE",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                null, gf("SHELL_SQL_TRUNCATE"), 150));

        rules.add(rule("SHELL_SQL_DELETE_ALL", gn("SHELL_SQL_DELETE_ALL"), "DELETE\\s+FROM\\s+\\w+\\s*;",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                null, gf("SHELL_SQL_DELETE_ALL"), 150));

        rules.add(rule("SHELL_SQL_ALTER_DROP", gn("SHELL_SQL_ALTER_DROP"), "ALTER\\s+TABLE\\s+\\w+\\s+DROP",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                null, gf("SHELL_SQL_ALTER_DROP"), 150));

        rules.add(rule("SHELL_SHUTDOWN", gn("SHELL_SHUTDOWN"), "\\bshutdown\\b",
                GuardSeverity.HIGH, GuardCategory.RESOURCE_ABUSE, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_SHUTDOWN"), 150));

        rules.add(rule("SHELL_REBOOT", gn("SHELL_REBOOT"), "\\breboot\\b",
                GuardSeverity.HIGH, GuardCategory.RESOURCE_ABUSE, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_REBOOT"), 150));

        rules.add(rule("SHELL_CHMOD_777", gn("SHELL_CHMOD_777"), "chmod\\s+777",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_CHMOD_777"), 150));

        rules.add(rule("SHELL_EVAL", gn("SHELL_EVAL"), "eval\\s*\\(",
                GuardSeverity.HIGH, GuardCategory.CODE_EXECUTION, "NEEDS_APPROVAL",
                null, gf("SHELL_EVAL"), 150));

        rules.add(rule("SHELL_GIT_FORCE_PUSH", gn("SHELL_GIT_FORCE_PUSH"), "git\\s+push\\s+.*--force",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_GIT_FORCE_PUSH"), 150));

        rules.add(rule("SHELL_GIT_RESET_HARD", gn("SHELL_GIT_RESET_HARD"), "git\\s+reset\\s+--hard",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_GIT_RESET_HARD"), 150));

        rules.add(rule("SHELL_CRONTAB", gn("SHELL_CRONTAB"), "\\bcrontab\\b",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_CRONTAB"), 150));

        rules.add(rule("SHELL_AUTHORIZED_KEYS", gn("SHELL_AUTHORIZED_KEYS"), "authorized_keys",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION, "NEEDS_APPROVAL",
                null, gf("SHELL_AUTHORIZED_KEYS"), 150));

        rules.add(rule("SHELL_SUDOERS", gn("SHELL_SUDOERS"), "/etc/sudoers",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION, "NEEDS_APPROVAL",
                null, gf("SHELL_SUDOERS"), 150));

        rules.add(rule("SHELL_OBFUSCATED_EXEC", gn("SHELL_OBFUSCATED_EXEC"), "base64\\s+-d.*\\|\\s*(bash|sh)",
                GuardSeverity.HIGH, GuardCategory.CODE_EXECUTION, "NEEDS_APPROVAL",
                "execute_shell_command", gf("SHELL_OBFUSCATED_EXEC"), 150));

        // === Credential Rules ===
        rules.add(rule("CRED_PASSWORD_ASSIGN", gn("CRED_PASSWORD_ASSIGN"), "(password|secret|api[_-]?key|token)\\s*=\\s*['\"]?\\S{8,}",
                GuardSeverity.HIGH, GuardCategory.CREDENTIAL_EXPOSURE, "NEEDS_APPROVAL",
                null, gf("CRED_PASSWORD_ASSIGN"), 140));

        rules.add(rule("CRED_AWS_KEY", gn("CRED_AWS_KEY"), "AKIA[0-9A-Z]{16}",
                GuardSeverity.HIGH, GuardCategory.CREDENTIAL_EXPOSURE, "NEEDS_APPROVAL",
                null, gf("CRED_AWS_KEY"), 140));

        rules.add(rule("CRED_PRIVATE_KEY", gn("CRED_PRIVATE_KEY"), "-----BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY-----",
                GuardSeverity.HIGH, GuardCategory.CREDENTIAL_EXPOSURE, "BLOCK",
                null, gf("CRED_PRIVATE_KEY"), 140));

        rules.add(rule("CRED_JWT_TOKEN", gn("CRED_JWT_TOKEN"),
                "eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+",
                GuardSeverity.HIGH, GuardCategory.CREDENTIAL_EXPOSURE, "NEEDS_APPROVAL",
                null, gf("CRED_JWT_TOKEN"), 140));

        rules.add(rule("CRED_GITHUB_TOKEN", gn("CRED_GITHUB_TOKEN"),
                "gh[pousr]_[A-Za-z0-9_]{36,}",
                GuardSeverity.HIGH, GuardCategory.CREDENTIAL_EXPOSURE, "NEEDS_APPROVAL",
                null, gf("CRED_GITHUB_TOKEN"), 140));

        // === Inline code execution (execute_code) ===
        // DbRuleGuardian only matches rules whose toolName equals the invoked
        // tool (or is global). The destructive shell rules above are scoped to
        // execute_shell_command, so they would never screen code run through
        // execute_code. Mirror the key patterns for execute_code here, reusing
        // the shell rules' i18n strings (gn/gf keyed by the SHELL_* ids).
        rules.add(rule("CODE_RM_RF_ROOT", gn("SHELL_RM_RF_ROOT"), "rm\\s+-(rf|fr)\\s+/\\s*$",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION, "BLOCK",
                "execute_code", gf("SHELL_RM_RF_ROOT"), 200));
        rules.add(rule("CODE_MKFS", gn("SHELL_MKFS"), "mkfs\\b",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION, "BLOCK",
                "execute_code", gf("SHELL_MKFS"), 200));
        rules.add(rule("CODE_DD_DEV", gn("SHELL_DD_DEV"), "dd\\s+if=.+of=/dev/",
                GuardSeverity.CRITICAL, GuardCategory.COMMAND_INJECTION, "BLOCK",
                "execute_code", gf("SHELL_DD_DEV"), 200));
        rules.add(rule("CODE_FORK_BOMB", gn("SHELL_FORK_BOMB"), ":\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\}\\s*;\\s*:",
                GuardSeverity.CRITICAL, GuardCategory.RESOURCE_ABUSE, "BLOCK",
                "execute_code", gf("SHELL_FORK_BOMB"), 200));
        rules.add(rule("CODE_REVERSE_SHELL", gn("SHELL_REVERSE_SHELL"), "(/dev/tcp|\\bnc\\s+-e\\b|\\bncat\\s+-e\\b|\\bsocat\\s+EXEC:)",
                GuardSeverity.CRITICAL, GuardCategory.NETWORK_ABUSE, "BLOCK",
                "execute_code", gf("SHELL_REVERSE_SHELL"), 200));
        rules.add(rule("CODE_CURL_PIPE_SH", gn("SHELL_CURL_PIPE_SH"), "curl.*\\|\\s*(sh|bash|zsh)",
                GuardSeverity.CRITICAL, GuardCategory.CODE_EXECUTION, "BLOCK",
                "execute_code", gf("SHELL_CURL_PIPE_SH"), 200));
        rules.add(rule("CODE_WGET_PIPE_SH", gn("SHELL_WGET_PIPE_SH"), "wget.*\\|\\s*(sh|bash|zsh)",
                GuardSeverity.CRITICAL, GuardCategory.CODE_EXECUTION, "BLOCK",
                "execute_code", gf("SHELL_WGET_PIPE_SH"), 200));
        rules.add(rule("CODE_KILL_INIT", gn("SHELL_KILL_INIT"), "\\bkill\\s+-9\\s+1\\b",
                GuardSeverity.CRITICAL, GuardCategory.RESOURCE_ABUSE, "BLOCK",
                "execute_code", gf("SHELL_KILL_INIT"), 200));
        rules.add(rule("CODE_RM", gn("SHELL_RM"), "(^|[;&|]|\\s)rm\\s",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_code", gf("SHELL_RM"), 150));
        rules.add(rule("CODE_RM_RF", gn("SHELL_RM_RF"), "rm\\s+-(rf|fr)",
                GuardSeverity.HIGH, GuardCategory.COMMAND_INJECTION, "NEEDS_APPROVAL",
                "execute_code", gf("SHELL_RM_RF"), 150));
        rules.add(rule("CODE_CHMOD_777", gn("SHELL_CHMOD_777"), "chmod\\s+777",
                GuardSeverity.HIGH, GuardCategory.PRIVILEGE_ESCALATION, "NEEDS_APPROVAL",
                "execute_code", gf("SHELL_CHMOD_777"), 150));
        rules.add(rule("CODE_OBFUSCATED_EXEC", gn("SHELL_OBFUSCATED_EXEC"), "base64\\s+-d.*\\|\\s*(bash|sh)",
                GuardSeverity.HIGH, GuardCategory.CODE_EXECUTION, "NEEDS_APPROVAL",
                "execute_code", gf("SHELL_OBFUSCATED_EXEC"), 150));

        return rules;
    }

    /** Guard rule name shorthand */
    private String gn(String ruleId) { return i18n.msg("guard." + ruleId + ".name"); }
    /** Guard rule fix/remediation shorthand */
    private String gf(String ruleId) { return i18n.msg("guard." + ruleId + ".fix"); }

    private ToolGuardRuleEntity rule(String ruleId, String name, String pattern,
                                     GuardSeverity severity, GuardCategory category,
                                     String decision, String toolName, String remediation,
                                     int priority) {
        ToolGuardRuleEntity entity = new ToolGuardRuleEntity();
        entity.setRuleId(ruleId);
        entity.setName(name);
        entity.setDescription(name);
        entity.setPattern(pattern);
        entity.setSeverity(severity.name());
        entity.setCategory(category.name());
        entity.setDecision(decision);
        entity.setToolName(toolName);
        entity.setRemediation(remediation);
        entity.setBuiltin(true);
        entity.setEnabled(true);
        entity.setPriority(priority);
        return entity;
    }
}
