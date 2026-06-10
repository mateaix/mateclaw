package vip.mate.tool.guard.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.common.result.R;
import vip.mate.tool.guard.model.ToolGuardAuditLogEntity;
import vip.mate.tool.guard.model.ToolGuardConfigEntity;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.service.ToolGuardAuditService;
import vip.mate.tool.guard.service.ToolGuardConfigService;
import vip.mate.tool.guard.service.ToolGuardRuleService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * 安全管理接口
 * <p>
 * 提供 Guard 配置、规则管理、审计日志查询、审批记录管理视角。
 *
 * @author MateClaw Team
 */
@Tag(name = "安全管理")
@Slf4j
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class SecurityController {

    private final ToolGuardConfigService configService;
    private final ToolGuardRuleService ruleService;
    private final ToolGuardAuditService auditService;
    private final ApprovalWorkflowService approvalWorkflowService;

    // ==================== Guard Config ====================

    @Operation(summary = "获取 Guard 配置")
    @GetMapping("/guard/config")
    @RequireWorkspaceRole("admin")
    public R<ToolGuardConfigEntity> getGuardConfig() {
        return R.ok(configService.getConfig());
    }

    @Operation(summary = "更新 Guard 配置")
    @PutMapping("/guard/config")
    @RequireWorkspaceRole("admin")
    public R<ToolGuardConfigEntity> updateGuardConfig(@RequestBody ToolGuardConfigEntity config) {
        return R.ok(configService.updateConfig(config));
    }

    @Operation(summary = "获取 File Guard 配置")
    @GetMapping("/guard/config/file-guard")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> getFileGuardConfig() {
        ToolGuardConfigEntity config = configService.getConfig();
        Map<String, Object> result = new HashMap<>();
        result.put("fileGuardEnabled", config.getFileGuardEnabled());
        result.put("sensitivePaths", configService.getSensitivePaths());
        return R.ok(result);
    }

    @Operation(summary = "更新 File Guard 配置")
    @PutMapping("/guard/config/file-guard")
    @RequireWorkspaceRole("admin")
    public R<ToolGuardConfigEntity> updateFileGuardConfig(@RequestBody ToolGuardConfigEntity config) {
        ToolGuardConfigEntity update = new ToolGuardConfigEntity();
        update.setFileGuardEnabled(config.getFileGuardEnabled());
        update.setSensitivePathsJson(config.getSensitivePathsJson());
        return R.ok(configService.updateConfig(update));
    }

    // ==================== Rules ====================

    @Operation(summary = "规则列表")
    @GetMapping("/guard/rules")
    @RequireWorkspaceRole("admin")
    public R<IPage<ToolGuardRuleEntity>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Boolean builtin,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String severity) {
        return R.ok(ruleService.listRules(page, size, builtin, enabled, category, severity));
    }

    @Operation(summary = "内置规则列表")
    @GetMapping("/guard/rules/builtin")
    @RequireWorkspaceRole("admin")
    public R<IPage<ToolGuardRuleEntity>> listBuiltinRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return R.ok(ruleService.listBuiltinRules(page, size));
    }

    @Operation(summary = "新增自定义规则")
    @PostMapping("/guard/rules")
    @RequireWorkspaceRole("admin")
    public R<ToolGuardRuleEntity> createRule(@RequestBody ToolGuardRuleEntity rule) {
        try {
            return R.ok(ruleService.createRule(rule));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (DuplicateKeyException e) {
            // Race fallback: pre-check passed but a concurrent insert took the slot.
            String ruleId = rule != null && rule.getRuleId() != null ? rule.getRuleId().trim() : "";
            return R.fail("Rule ID already exists: " + ruleId);
        }
    }

    @Operation(summary = "更新规则")
    @PutMapping("/guard/rules/{ruleId}")
    @RequireWorkspaceRole("admin")
    public R<ToolGuardRuleEntity> updateRule(
            @PathVariable String ruleId,
            @RequestBody ToolGuardRuleEntity rule) {
        try {
            return R.ok(ruleService.updateRule(ruleId, rule));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "启用/禁用规则")
    @PutMapping("/guard/rules/{ruleId}/toggle")
    @RequireWorkspaceRole("admin")
    public R<String> toggleRule(
            @PathVariable String ruleId,
            @RequestParam boolean enabled) {
        try {
            ruleService.toggleRule(ruleId, enabled);
            return R.ok("操作成功");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除自定义规则")
    @DeleteMapping("/guard/rules/{ruleId}")
    @RequireWorkspaceRole("admin")
    public R<String> deleteRule(@PathVariable String ruleId) {
        try {
            ruleService.deleteRule(ruleId);
            return R.ok("删除成功");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "按主键 ID 删除自定义规则（兜底，rule_id 异常时使用）")
    @DeleteMapping("/guard/rules/by-id/{id}")
    @RequireWorkspaceRole("admin")
    public R<String> deleteRuleByPk(@PathVariable Long id) {
        try {
            ruleService.deleteRuleByPk(id);
            return R.ok("删除成功");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "导出全部规则为 JSON")
    @GetMapping("/guard/rules/export")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> exportRules() {
        return R.ok(ruleService.exportRules());
    }

    @Operation(summary = "从 JSON 批量导入规则（upsert 语义）")
    @PostMapping("/guard/rules/import")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> importRules(@RequestBody Map<String, Object> body) {
        try {
            Object rulesNode = body == null ? null : body.get("rules");
            if (!(rulesNode instanceof List<?> raw)) {
                return R.fail("Body must contain a 'rules' array");
            }
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<ToolGuardRuleEntity> incoming = new java.util.ArrayList<>();
            for (Object item : raw) {
                ToolGuardRuleEntity rule = om.convertValue(item, ToolGuardRuleEntity.class);
                incoming.add(rule);
            }
            return R.ok(ruleService.importRules(incoming));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            return R.fail("Import failed: " + e.getMessage());
        }
    }

    // ==================== Audit ====================

    @Operation(summary = "审计日志")
    @GetMapping("/audit/logs")
    @RequireWorkspaceRole("admin")
    public R<IPage<ToolGuardAuditLogEntity>> listAuditLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String conversationId) {
        return R.ok(auditService.listAll(page, size, toolName, decision, conversationId));
    }

    @Operation(summary = "审计统计")
    @GetMapping("/audit/stats")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> getAuditStats() {
        return R.ok(auditService.getStats());
    }

    // ==================== Approvals (管理视角) ====================

    @Operation(summary = "审批记录（管理视角）")
    @GetMapping("/approvals")
    @RequireWorkspaceRole("admin")
    public R<Object> listApprovals(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        if (conversationId != null && !conversationId.isBlank()) {
            return R.ok(approvalWorkflowService.getPendingByConversation(conversationId));
        }
        // Global view — reads from mate_tool_approval directly so the result
        // survives in-memory map drift after a restart/recovery cycle.
        return R.ok(approvalWorkflowService.listPendingFromDb(limit));
    }
}
