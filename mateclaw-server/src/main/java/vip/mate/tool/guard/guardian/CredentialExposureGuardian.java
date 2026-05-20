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
 * 检测工具参数中可能包含的敏感凭据信息。
 * alwaysRun=true，不受 guarded tools 范围限制。
 * <p>
 * 优先从 ToolGuardRuleRegistry 加载数据库规则（支持 enabled 开关）；
 * 若数据库中无凭据规则则 fallback 到内置硬编码规则。
 */
@Slf4j
@Component
public class CredentialExposureGuardian implements ToolGuardGuardian {

    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private record CredentialRule(String ruleId, String pattern, String title, String description) {}

    private static final List<CredentialRule> BUILTIN_RULES = List.of(
            new CredentialRule("CRED_PASSWORD_ASSIGN",
                    "(password|secret|api[_-]?key|token)\\s*=\\s*['\"]?\\S{8,}",
                    "凭据信息暴露",
                    "检测到可能的密码/密钥/Token 赋值"),
            new CredentialRule("CRED_AWS_KEY",
                    "AKIA[0-9A-Z]{16}",
                    "AWS Access Key 泄露",
                    "检测到 AWS Access Key ID 模式"),
            new CredentialRule("CRED_PRIVATE_KEY",
                    "-----BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY-----",
                    "私钥泄露",
                    "检测到 PEM 格式私钥"),
            new CredentialRule("CRED_JWT_TOKEN",
                    "eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+",
                    "JWT Token 泄露",
                    "检测到 JWT Token 格式的字符串"),
            new CredentialRule("CRED_GITHUB_TOKEN",
                    "gh[pousr]_[A-Za-z0-9_]{36,}",
                    "GitHub Token 泄露",
                    "检测到 GitHub Personal Access Token")
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

        // 优先使用数据库规则（已按 enabled=true 过滤，禁用规则不在列表内）
        List<ToolGuardRuleEntity> dbRules = ruleRegistry.getAllEnabled().stream()
                .filter(r -> GuardCategory.CREDENTIAL_EXPOSURE.name().equals(r.getCategory()))
                .toList();

        if (!dbRules.isEmpty()) {
            return evaluateDbRules(dbRules, raw, context);
        }

        // 数据库无凭据规则时 fallback 到内置规则
        return evaluateBuiltinRules(raw, context);
    }

    private List<GuardFinding> evaluateDbRules(List<ToolGuardRuleEntity> dbRules,
                                                String raw, ToolInvocationContext context) {
        List<GuardFinding> findings = new ArrayList<>();
        for (ToolGuardRuleEntity rule : dbRules) {
            Pattern p = ruleRegistry.getCompiledPattern(rule.getPattern());
            Matcher matcher = p.matcher(raw);
            if (matcher.find()) {
                String snippet = extractSnippet(raw, matcher.start(), 30);
                findings.add(new GuardFinding(
                        rule.getRuleId(),
                        GuardSeverity.valueOf(rule.getSeverity()),
                        GuardCategory.CREDENTIAL_EXPOSURE,
                        rule.getName(),
                        rule.getDescription(),
                        rule.getRemediation(),
                        context.toolName(),
                        null,
                        rule.getPattern(),
                        maskCredential(snippet)
                ));
            }
        }
        return findings;
    }

    private List<GuardFinding> evaluateBuiltinRules(String raw, ToolInvocationContext context) {
        List<GuardFinding> findings = new ArrayList<>();
        for (CredentialRule rule : BUILTIN_RULES) {
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
                        maskCredential(snippet)
                ));
            }
        }
        return findings;
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
