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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        // 内置规则只允许调整策略字段；内容字段（pattern / category / name 等）由
        // 代码侧种子管理，写进 DB 也会在下次启动被覆盖回去，提前在这里拦掉以免误用。
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            return updateBuiltinPolicy(ruleId, update);
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
     * 更新内置规则时，限制只允许调整策略字段（severity / decision / priority / enabled
     * / excludePattern）。内容字段（pattern / category / name 等）由代码侧种子管理，
     * UI 改动也会在下次重启被覆盖回去，提前在 API 层拦截避免误用。
     */
    public ToolGuardRuleEntity updateBuiltinPolicy(String ruleId, ToolGuardRuleEntity patch) {
        ToolGuardRuleEntity existing = getByRuleId(ruleId);
        if (existing == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }
        if (!Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new IllegalArgumentException("Rule is not builtin: " + ruleId);
        }
        if (patch.getSeverity() != null) existing.setSeverity(patch.getSeverity());
        if (patch.getDecision() != null) existing.setDecision(patch.getDecision());
        if (patch.getPriority() != null) existing.setPriority(patch.getPriority());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getExcludePattern() != null) existing.setExcludePattern(patch.getExcludePattern());
        ruleMapper.updateById(existing);
        ruleRegistry.reload();
        return existing;
    }

    /**
     * 导出全部规则（含 builtin），格式可被 importRules 直接吃回去。
     * 导出时保留 ruleId 作为主键标识，省略 id / createTime / updateTime / deleted 这些
     * 部署敏感的字段；builtin 标志保留，import 时用来判断走 builtin policy 通道还是
     * 创建/覆盖 custom 规则。
     */
    public Map<String, Object> exportRules() {
        List<ToolGuardRuleEntity> all = ruleMapper.selectList(
                new LambdaQueryWrapper<ToolGuardRuleEntity>()
                        .orderByDesc(ToolGuardRuleEntity::getPriority));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ToolGuardRuleEntity r : all) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ruleId", r.getRuleId());
            row.put("name", r.getName());
            row.put("description", r.getDescription());
            row.put("toolName", r.getToolName());
            row.put("paramName", r.getParamName());
            row.put("category", r.getCategory());
            row.put("severity", r.getSeverity());
            row.put("decision", r.getDecision());
            row.put("pattern", r.getPattern());
            row.put("excludePattern", r.getExcludePattern());
            row.put("remediation", r.getRemediation());
            row.put("priority", r.getPriority());
            row.put("enabled", r.getEnabled());
            row.put("builtin", r.getBuiltin());
            rows.add(row);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "mateclaw.tool-guard.rules.v1");
        envelope.put("exportedAt", java.time.OffsetDateTime.now().toString());
        envelope.put("count", rows.size());
        envelope.put("rules", rows);
        return envelope;
    }

    /**
     * 导入规则。upsert 语义：
     * <ul>
     *   <li>ruleId 已存在 + builtin → 仅同步策略字段（severity / decision / priority / enabled / excludePattern）；</li>
     *   <li>ruleId 已存在 + custom → 全字段覆盖；</li>
     *   <li>ruleId 不存在 → 作为 custom 规则插入（强制 builtin=false，避免被 import 篡改内置标记）。</li>
     * </ul>
     */
    public Map<String, Object> importRules(List<ToolGuardRuleEntity> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            throw new IllegalArgumentException("No rules to import");
        }
        int inserted = 0;
        int updatedBuiltin = 0;
        int updatedCustom = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (ToolGuardRuleEntity rule : incoming) {
            try {
                if (rule.getRuleId() == null || rule.getRuleId().isBlank()) {
                    skipped++;
                    errors.add("missing ruleId");
                    continue;
                }
                if (rule.getPattern() == null || rule.getPattern().isBlank()) {
                    skipped++;
                    errors.add(rule.getRuleId() + ": missing pattern");
                    continue;
                }
                String rid = rule.getRuleId().trim();
                ToolGuardRuleEntity existing = getByRuleId(rid);
                if (existing == null) {
                    rule.setRuleId(rid);
                    rule.setBuiltin(false);
                    if (rule.getEnabled() == null) rule.setEnabled(true);
                    if (rule.getPriority() == null) rule.setPriority(100);
                    if (rule.getSeverity() == null) rule.setSeverity("HIGH");
                    if (rule.getDecision() == null) rule.setDecision("NEEDS_APPROVAL");
                    ruleMapper.insert(rule);
                    inserted++;
                } else if (Boolean.TRUE.equals(existing.getBuiltin())) {
                    updateBuiltinPolicy(rid, rule);
                    updatedBuiltin++;
                } else {
                    updateRule(rid, rule);
                    updatedCustom++;
                }
            } catch (Exception e) {
                skipped++;
                errors.add((rule.getRuleId() == null ? "<no id>" : rule.getRuleId())
                        + ": " + e.getMessage());
            }
        }
        ruleRegistry.reload();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inserted", inserted);
        summary.put("updatedBuiltin", updatedBuiltin);
        summary.put("updatedCustom", updatedCustom);
        summary.put("skipped", skipped);
        summary.put("errors", errors);
        return summary;
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
