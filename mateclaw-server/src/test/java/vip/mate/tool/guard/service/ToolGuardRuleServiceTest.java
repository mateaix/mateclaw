package vip.mate.tool.guard.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.repository.ToolGuardRuleMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolGuardRuleServiceTest {

    private ToolGuardRuleMapper ruleMapper;
    private ToolGuardRuleRegistry ruleRegistry;
    private ToolGuardRuleService service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(ToolGuardRuleMapper.class);
        ruleRegistry = mock(ToolGuardRuleRegistry.class);
        service = new ToolGuardRuleService(ruleMapper, ruleRegistry);
    }

    @Test
    @DisplayName("createRule rejects blank ruleId before persistence")
    void createRuleRejectsBlankRuleId() {
        ToolGuardRuleEntity rule = wellFormedRule();
        rule.setRuleId("   ");

        assertThrows(IllegalArgumentException.class, () -> service.createRule(rule));

        verify(ruleMapper, never()).insert(any(ToolGuardRuleEntity.class));
        verify(ruleRegistry, never()).reload();
    }

    @Test
    @DisplayName("createRule rejects blank name before persistence")
    void createRuleRejectsBlankName() {
        ToolGuardRuleEntity rule = wellFormedRule();
        rule.setName("");

        assertThrows(IllegalArgumentException.class, () -> service.createRule(rule));

        verify(ruleMapper, never()).insert(any(ToolGuardRuleEntity.class));
    }

    @Test
    @DisplayName("createRule rejects blank pattern before persistence")
    void createRuleRejectsBlankPattern() {
        ToolGuardRuleEntity rule = wellFormedRule();
        rule.setPattern(null);

        assertThrows(IllegalArgumentException.class, () -> service.createRule(rule));

        verify(ruleMapper, never()).insert(any(ToolGuardRuleEntity.class));
    }

    @Test
    @DisplayName("updateRule rejects explicit blank name")
    void updateRuleRejectsExplicitBlankName() {
        ToolGuardRuleEntity existing = wellFormedRule();
        existing.setId(7L);
        when(ruleMapper.selectOne(any())).thenReturn(existing);

        ToolGuardRuleEntity update = new ToolGuardRuleEntity();
        update.setName("   ");

        assertThrows(IllegalArgumentException.class,
                () -> service.updateRule("CUSTOM_RULE", update));

        verify(ruleMapper, never()).updateById(any(ToolGuardRuleEntity.class));
    }

    @Test
    @DisplayName("deleteRuleByPk hard-deletes a custom rule by primary key")
    void deleteRuleByPkRemovesCustomRule() {
        ToolGuardRuleEntity existing = wellFormedRule();
        existing.setId(42L);
        existing.setBuiltin(false);
        when(ruleMapper.selectById(42L)).thenReturn(existing);

        service.deleteRuleByPk(42L);

        verify(ruleMapper).deleteById(eq(42L));
        verify(ruleRegistry).reload();
    }

    @Test
    @DisplayName("deleteRuleByPk refuses to remove builtin rules")
    void deleteRuleByPkRejectsBuiltin() {
        ToolGuardRuleEntity existing = wellFormedRule();
        existing.setId(99L);
        existing.setBuiltin(true);
        when(ruleMapper.selectById(99L)).thenReturn(existing);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteRuleByPk(99L));
        assertEquals(true, ex.getMessage().contains("builtin"));

        verify(ruleMapper, never()).deleteById(any(Long.class));
    }

    private static ToolGuardRuleEntity wellFormedRule() {
        ToolGuardRuleEntity rule = new ToolGuardRuleEntity();
        rule.setRuleId("CUSTOM_RULE");
        rule.setName("Custom rule");
        rule.setPattern(".*");
        return rule;
    }
}
