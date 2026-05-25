package vip.mate.tool.guard.guardian;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 凭据泄露守卫
 * <p>
 * 检测工具参数中可能包含的敏感凭据信息。alwaysRun=true，不受 guarded tools 范围限制。
 * <p>
 * 规则优先级：
 * <ol>
 *   <li>优先读取 DB 中 category=CREDENTIAL_EXPOSURE 的已启用规则（受 UI 开关控制）；</li>
 *   <li>DB 无任何凭据规则时回退到内置硬编码列表（保证未初始化部署也能工作）。</li>
 * </ol>
 */
@Slf4j
@Component
public class CredentialExposureGuardian implements ToolGuardGuardian {

    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private record CredentialRule(String ruleId, String pattern, String title,
                                  String description, GuardDecision decision) {}

    private static final List<CredentialRule> BUILTIN_FALLBACK = List.of(
            new CredentialRule("CRED_PASSWORD_ASSIGN",
                    "(password|secret|api[_-]?key|token)\\s*=\\s*['\"]?\\S{8,}",
                    "凭据信息暴露",
                    "检测到可能的密码/密钥/Token 赋值",
                    GuardDecision.NEEDS_APPROVAL),
            new CredentialRule("CRED_AWS_KEY",
                    "AKIA[0-9A-Z]{16}",
                    "AWS Access Key 泄露",
                    "检测到 AWS Access Key ID 模式",
                    GuardDecision.NEEDS_APPROVAL),
            new CredentialRule("CRED_PRIVATE_KEY",
                    "-----BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY-----",
                    "私钥泄露",
                    "检测到 PEM 格式私钥",
                    GuardDecision.BLOCK),
            new CredentialRule("CRED_JWT_TOKEN",
                    "eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+",
                    "JWT Token 泄露",
                    "检测到 JWT Token 格式的字符串",
                    GuardDecision.NEEDS_APPROVAL),
            new CredentialRule("CRED_GITHUB_TOKEN",
                    "gh[pousr]_[A-Za-z0-9_]{36,}",
                    "GitHub Token 泄露",
                    "检测到 GitHub Personal Access Token",
                    GuardDecision.NEEDS_APPROVAL)
    );

    private final ToolGuardRuleRegistry ruleRegistry;

    public CredentialExposureGuardian(ToolGuardRuleRegistry ruleRegistry) {
        this.ruleRegistry = ruleRegistry;
    }

    @Override
    public boolean supports(ToolInvocationContext context) {
        return true;
    }

    @Override
    public boolean alwaysRun() {
        return true;
    }

    @Override
    public int priority() {
        return 250;
    }

    @Override
    public List<GuardFinding> evaluate(ToolInvocationContext context) {
        String raw = context.rawArguments();
        if (raw == null || raw.isEmpty()) return List.of();

        List<GuardFinding> findings = new ArrayList<>();

        // 1) 优先使用 DB 规则（受 UI 启用/禁用开关控制）
        List<ToolGuardRuleEntity> dbRules = ruleRegistry.getRulesByCategory(
                GuardCategory.CREDENTIAL_EXPOSURE.name());
        if (!dbRules.isEmpty()) {
            for (ToolGuardRuleEntity rule : dbRules) {
                if (rule.getPattern() == null || rule.getPattern().isBlank()) continue;
                Pattern p = ruleRegistry.getCompiledPattern(rule.getPattern());
                Matcher matcher = p.matcher(raw);
                if (!matcher.find()) continue;

                // 排除模式（白名单）
                if (rule.getExcludePattern() != null && !rule.getExcludePattern().isBlank()) {
                    Pattern exclude = ruleRegistry.getCompiledExcludePattern(rule.getExcludePattern());
                    if (exclude.matcher(raw).find()) continue;
                }

                String snippet = extractSnippet(raw, matcher.start(), 30);
                GuardSeverity severity = parseSeverity(rule.getSeverity());
                GuardDecision decision = parseDecision(rule.getDecision());
                findings.add(new GuardFinding(
                        rule.getRuleId(),
                        severity,
                        GuardCategory.CREDENTIAL_EXPOSURE,
                        rule.getName(),
                        rule.getDescription(),
                        rule.getRemediation(),
                        context.toolName(),
                        rule.getParamName(),
                        rule.getPattern(),
                        maskCredential(snippet),
                        decision
                ));
            }
            return findings;
        }

        // 2) DB 未初始化 → 回退内置规则
        for (CredentialRule rule : BUILTIN_FALLBACK) {
            Pattern p = COMPILED.computeIfAbsent(rule.pattern,
                    r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE));
            Matcher matcher = p.matcher(raw);
            if (matcher.find()) {
                String snippet = extractSnippet(raw, matcher.start(), 30);
                findings.add(new GuardFinding(
                        rule.ruleId,
                        GuardSeverity.HIGH,
                        GuardCategory.CREDENTIAL_EXPOSURE,
                        rule.title,
                        rule.description,
                        "请移除凭据信息，使用环境变量或密钥管理服务",
                        context.toolName(),
                        null,
                        rule.pattern,
                        maskCredential(snippet),
                        rule.decision
                ));
            }
        }
        return findings;
    }

    private GuardSeverity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) return GuardSeverity.HIGH;
        try {
            return GuardSeverity.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return GuardSeverity.HIGH;
        }
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

    /**
     * 对凭据片段做遮蔽处理，避免在日志/UI 中泄露完整凭据
     */
    private String maskCredential(String snippet) {
        if (snippet.length() <= 8) return "***";
        return snippet.substring(0, 4) + "***" + snippet.substring(snippet.length() - 4);
    }
}
