package vip.mate.tool.guard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.repository.ToolGuardRuleMapper;

/**
 * 工具安全规则 CRUD 服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolGuardRuleService {

    private final ToolGuardRuleMapper ruleMapper;
    private final ToolGuardRuleRegistry ruleRegistry;

    /**
     * 分页查询规则
     */
    public IPage<ToolGuardRuleEntity> listRules(int page, int size,
                                                 Boolean builtin, Boolean enabled,
                                                 String category, String severity) {
        LambdaQueryWrapper<ToolGuardRuleEntity> wrapper = new LambdaQueryWrapper<>();
        if (builtin != null) {
            wrapper.eq(ToolGuardRuleEntity::getBuiltin, builtin);
        }
        if (enabled != null) {
            wrapper.eq(ToolGuardRuleEntity::getEnabled, enabled);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(ToolGuardRuleEntity::getCategory, category);
        }
        if (severity != null && !severity.isBlank()) {
            wrapper.eq(ToolGuardRuleEntity::getSeverity, severity);
        }
        wrapper.orderByDesc(ToolGuardRuleEntity::getPriority);
        return ruleMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 查询所有内置规则
     */
    public IPage<ToolGuardRuleEntity> listBuiltinRules(int page, int size) {
        return listRules(page, size, true, null, null, null);
    }

    /**
     * 按 ruleId 查询
     */
    public ToolGuardRuleEntity getByRuleId(String ruleId) {
        return ruleMapper.selectOne(
                new LambdaQueryWrapper<ToolGuardRuleEntity>()
                        .eq(ToolGuardRuleEntity::getRuleId, ruleId));
    }

    /**
     * 新增自定义规则
     */
    public ToolGuardRuleEntity createRule(ToolGuardRuleEntity rule) {
        if (rule == null) {
            throw new IllegalArgumentException("Rule body is required");
        }
        requireNonBlank(rule.getRuleId(), "Rule ID");
        requireNonBlank(rule.getName(), "Rule name");
        requireNonBlank(rule.getPattern(), "Rule pattern");
        rule.setRuleId(rule.getRuleId().trim());
        rule.setName(rule.getName().trim());
        rule.setPattern(rule.getPattern().trim());
        rule.setBuiltin(false);
        // Pre-check uniqueness so the API returns a friendly message instead of
        // surfacing the raw JDBC UNIQUE-constraint violation through the global
        // exception handler. The DB constraint still guards against races.
        if (getByRuleId(rule.getRuleId()) != null) {
            throw new IllegalArgumentException("Rule ID already exists: " + rule.getRuleId());
        }
        ruleMapper.insert(rule);
        ruleRegistry.reload();
        return rule;
    }

    /**
     * 更新规则。仅覆盖请求里显式提供的字段；显式传入的关键字段（name / pattern）
     * 不允许置为空白，避免回写出无意义的"空名空模式"行。
     */
    public ToolGuardRuleEntity updateRule(String ruleId, ToolGuardRuleEntity update) {
        ToolGuardRuleEntity existing = getByRuleId(ruleId);
        if (existing == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }

        if (update.getName() != null) {
            requireNonBlank(update.getName(), "Rule name");
            existing.setName(update.getName().trim());
        }
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getToolName() != null) existing.setToolName(update.getToolName());
        if (update.getParamName() != null) existing.setParamName(update.getParamName());
        if (update.getCategory() != null) existing.setCategory(update.getCategory());
        if (update.getSeverity() != null) existing.setSeverity(update.getSeverity());
        if (update.getDecision() != null) existing.setDecision(update.getDecision());
        if (update.getPattern() != null) {
            requireNonBlank(update.getPattern(), "Rule pattern");
            existing.setPattern(update.getPattern().trim());
        }
        if (update.getExcludePattern() != null) existing.setExcludePattern(update.getExcludePattern());
        if (update.getRemediation() != null) existing.setRemediation(update.getRemediation());
        if (update.getEnabled() != null) existing.setEnabled(update.getEnabled());
        if (update.getPriority() != null) existing.setPriority(update.getPriority());

        ruleMapper.updateById(existing);
        ruleRegistry.reload();
        return existing;
    }

    private static void requireNonBlank(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldLabel + " is required");
        }
    }

    /**
     * 启用/禁用规则
     */
    public void toggleRule(String ruleId, boolean enabled) {
        ToolGuardRuleEntity existing = getByRuleId(ruleId);
        if (existing == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }
        existing.setEnabled(enabled);
        ruleMapper.updateById(existing);
        ruleRegistry.reload();
    }

    /**
     * 删除自定义规则（内置规则不允许删除）
     */
    public void deleteRule(String ruleId) {
        ToolGuardRuleEntity existing = getByRuleId(ruleId);
        if (existing == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new IllegalArgumentException("Cannot delete builtin rule: " + ruleId);
        }
        ruleMapper.deleteById(existing.getId());
        ruleRegistry.reload();
    }

    /**
     * 按主键 ID 删除自定义规则。兜底通道：当 rule_id 因历史脏数据为空或无法走
     * /guard/rules/{ruleId} 路径变量时，UI 仍可通过主键删除。
     */
    public void deleteRuleByPk(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Rule primary key is required");
        }
        ToolGuardRuleEntity existing = ruleMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("Rule not found: id=" + id);
        }
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new IllegalArgumentException(
                    "Cannot delete builtin rule: " + existing.getRuleId());
        }
        ruleMapper.deleteById(id);
        ruleRegistry.reload();
    }
}
