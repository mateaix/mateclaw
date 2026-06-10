package vip.mate.tool.guard.guardian;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic guardian — applies any DB-stored {@link ToolGuardRuleEntity}
 * to the matching tool invocation, regardless of tool type. Previously
 * the only consumer of DB rules was {@link ShellCommandGuardian}, which
 * gated on a hard-coded list of 3 shell tool names; that left every
 * other tool (including channel-native ones from
 * {@code ChannelToolProvider}) with no path from a {@code
 * mate_tool_guard_rule} row to a Guard finding. This class fixes that
 * gap.
 *
 * <p><b>Disjointness contract</b>: {@link ShellCommandGuardian} now
 * defers to this class when a shell tool has DB rules — see its
 * {@code supports()} gate. So a single invocation is evaluated by
 * exactly one of the two, never both. Pattern matching is identical
 * between them, preserving the existing test corpus.
 */
@Slf4j
@Component
public class DbRuleGuardian implements ToolGuardGuardian {

    private final ToolGuardRuleRegistry ruleRegistry;

    public DbRuleGuardian(ToolGuardRuleRegistry ruleRegistry) {
        this.ruleRegistry = ruleRegistry;
    }

    @Override
    public boolean supports(ToolInvocationContext context) {
        return context != null && context.toolName() != null
                && !ruleRegistry.getRulesForTool(context.toolName()).isEmpty();
    }

    /**
     * Priority just below {@link ShellCommandGuardian}'s 200 so when
     * the engine sorts guardians, both will fire in a stable order
     * before any built-in-rule fallbacks. They're disjoint via
     * {@code supports()} so the order is cosmetic.
     */
    @Override
    public int priority() {
        return 199;
    }

    @Override
    public List<GuardFinding> evaluate(ToolInvocationContext context) {
        String combined = buildMatchInput(context);
        if (combined == null || combined.isEmpty()) return List.of();

        List<GuardFinding> findings = new ArrayList<>();
        for (ToolGuardRuleEntity rule : ruleRegistry.getRulesForTool(context.toolName())) {
            Pattern pattern = ruleRegistry.getCompiledPattern(rule.getPattern());
            Matcher matcher = pattern.matcher(combined);
            if (!matcher.find()) continue;

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
                    rule.getParamName() != null ? rule.getParamName() : "args",
                    rule.getPattern(),
                    snippet,
                    parseDecision(rule.getDecision())
            ));
        }
        return findings;
    }

    private static String buildMatchInput(ToolInvocationContext context) {
        String raw = context.rawArguments();
        if (raw == null || raw.isEmpty()) return null;
        return (context.toolName() != null ? context.toolName() + " " : "") + raw;
    }

    private static GuardDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return GuardDecision.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String extractSnippet(String input, int matchStart, int contextLen) {
        int start = Math.max(0, matchStart - contextLen / 2);
        int end = Math.min(input.length(), matchStart + contextLen / 2);
        return input.substring(start, end);
    }
}
