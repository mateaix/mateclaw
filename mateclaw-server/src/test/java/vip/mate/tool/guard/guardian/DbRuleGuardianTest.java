package vip.mate.tool.guard.guardian;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.GuardCategory;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.model.ToolInvocationContext;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pin the DbRuleGuardian contract:
 * <ul>
 *   <li>supports(): true iff DB rules exist for the tool</li>
 *   <li>evaluate(): produces a finding when the rule pattern matches</li>
 *   <li>exclude pattern suppresses the finding</li>
 *   <li>rule decision carries through to GuardFinding.decision</li>
 * </ul>
 */
class DbRuleGuardianTest {

    private static ToolGuardRuleEntity rule(String pattern, String severity, String decision, String exclude) {
        ToolGuardRuleEntity r = new ToolGuardRuleEntity();
        r.setRuleId("rule-1");
        r.setToolName("feishu_doc_create_c1");
        r.setName("Approval gate");
        r.setDescription("desc");
        r.setRemediation("approve to proceed");
        r.setParamName("args");
        r.setCategory(GuardCategory.SENSITIVE_FILE_ACCESS.name());
        r.setSeverity(severity);
        r.setDecision(decision);
        r.setPattern(pattern);
        r.setExcludePattern(exclude);
        r.setEnabled(true);
        r.setPriority(100);
        return r;
    }

    private static ToolInvocationContext ctx(String toolName, String args) {
        return ToolInvocationContext.of(toolName, args, null, null);
    }

    @Test
    @DisplayName("supports() returns false when registry has no rules for the tool")
    void supportsOnlyWhenRulesPresent() {
        ToolGuardRuleRegistry reg = mock(ToolGuardRuleRegistry.class);
        when(reg.getRulesForTool(any())).thenReturn(List.of());
        DbRuleGuardian g = new DbRuleGuardian(reg);
        assertFalse(g.supports(ctx("feishu_doc_create_c1", "{}")));
    }

    @Test
    @DisplayName("supports() returns true and matching .* pattern produces a finding")
    void evaluateMatchingPatternProducesFinding() {
        ToolGuardRuleRegistry reg = mock(ToolGuardRuleRegistry.class);
        ToolGuardRuleEntity r = rule(".*", "HIGH", "NEEDS_APPROVAL", null);
        when(reg.getRulesForTool(eq("feishu_doc_create_c1"))).thenReturn(List.of(r));
        when(reg.getCompiledPattern(".*")).thenReturn(Pattern.compile(".*", Pattern.CASE_INSENSITIVE));

        DbRuleGuardian g = new DbRuleGuardian(reg);
        ToolInvocationContext context = ctx("feishu_doc_create_c1", "{\"title\":\"meeting notes\"}");
        assertTrue(g.supports(context));
        List<GuardFinding> findings = g.evaluate(context);
        assertEquals(1, findings.size());
        GuardFinding f = findings.get(0);
        assertEquals(GuardSeverity.HIGH, f.severity());
        assertEquals(GuardDecision.NEEDS_APPROVAL, f.decision());
        assertEquals("feishu_doc_create_c1", f.toolName());
    }

    @Test
    @DisplayName("exclude pattern suppresses the finding")
    void excludePatternSuppresses() {
        ToolGuardRuleRegistry reg = mock(ToolGuardRuleRegistry.class);
        ToolGuardRuleEntity r = rule("title", "HIGH", "NEEDS_APPROVAL", "test");
        when(reg.getRulesForTool(any())).thenReturn(List.of(r));
        when(reg.getCompiledPattern("title")).thenReturn(Pattern.compile("title", Pattern.CASE_INSENSITIVE));
        when(reg.getCompiledExcludePattern("test")).thenReturn(Pattern.compile("test", Pattern.CASE_INSENSITIVE));

        DbRuleGuardian g = new DbRuleGuardian(reg);
        List<GuardFinding> findings = g.evaluate(ctx("any_tool", "{\"title\":\"test notes\"}"));
        assertEquals(0, findings.size(), "exclude pattern should suppress finding");
    }

    @Test
    @DisplayName("non-matching pattern produces no findings")
    void nonMatchingPatternNoFinding() {
        ToolGuardRuleRegistry reg = mock(ToolGuardRuleRegistry.class);
        ToolGuardRuleEntity r = rule("THIS_NEVER_MATCHES", "HIGH", "NEEDS_APPROVAL", null);
        when(reg.getRulesForTool(any())).thenReturn(List.of(r));
        when(reg.getCompiledPattern("THIS_NEVER_MATCHES"))
                .thenReturn(Pattern.compile("THIS_NEVER_MATCHES", Pattern.CASE_INSENSITIVE));

        DbRuleGuardian g = new DbRuleGuardian(reg);
        assertEquals(0, g.evaluate(ctx("any_tool", "{}")).size());
    }
}
