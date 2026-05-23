package vip.mate.tool.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.tool.disclosure.DisclosureTier;
import vip.mate.tool.disclosure.ToolDisclosureService;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.tool.service.ToolService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * 工具管理接口
 *
 * @author MateClaw Team
 */
@Tag(name = "工具管理")
@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;
    private final AvailableToolService availableToolService;
    private final ToolDisclosureService toolDisclosureService;

    @Operation(summary = "获取工具列表")
    @GetMapping
    @RequireWorkspaceRole("member")
    public R<List<ToolEntity>> list() {
        return R.ok(toolService.listTools());
    }

    @Operation(summary = "获取已启用工具列表")
    @GetMapping("/enabled")
    @RequireWorkspaceRole("member")
    public R<List<ToolEntity>> listEnabled() {
        return R.ok(toolService.listEnabledTools());
    }

    @Operation(summary = "获取员工可绑定的全部原子工具（含 MCP）")
    @GetMapping("/available")
    @RequireWorkspaceRole("member")
    public R<List<AvailableToolDTO>> listAvailable() {
        return R.ok(availableToolService.listAvailable());
    }

    @Operation(summary = "获取工具详情")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<ToolEntity> get(@PathVariable Long id) {
        return R.ok(toolService.getTool(id));
    }

    @Operation(summary = "创建工具（MCP）")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<ToolEntity> create(@RequestBody ToolEntity tool) {
        return R.ok(toolService.createTool(tool));
    }

    @Operation(summary = "更新工具")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<ToolEntity> update(@PathVariable Long id, @RequestBody ToolEntity tool) {
        tool.setId(id);
        return R.ok(toolService.updateTool(tool));
    }

    @Operation(summary = "删除工具")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> delete(@PathVariable Long id) {
        toolService.deleteTool(id);
        return R.ok();
    }

    @Operation(summary = "启用/禁用工具")
    @PutMapping("/{id}/toggle")
    @RequireWorkspaceRole("admin")
    public R<ToolEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        return R.ok(toolService.toggleTool(id, enabled));
    }

    @Operation(summary = "设置工具披露分级（core / extension）")
    @PutMapping("/{id}/disclosure-tier")
    @RequireWorkspaceRole("admin")
    public R<ToolEntity> setDisclosureTier(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String tier = body == null ? null : body.get("tier");
        if (!DisclosureTier.isValidToken(tier)) {
            return R.fail(400, "tier must be 'core' or 'extension'");
        }
        ToolEntity tool = toolService.getTool(id);
        String type = tool.getToolType();
        // Only builtin / channel atomic tools are tiered on the row itself; MCP /
        // ACP / skill tools are tiered at their owning source.
        if (!"builtin".equals(type) && !"channel".equals(type)) {
            return R.fail(409, "This tool's tier is decided by its owning server/endpoint/skill. "
                    + "Modify it there instead.");
        }
        ToolEntity updated = toolService.setDisclosureTier(id, tier);
        toolDisclosureService.invalidate();
        return R.ok(updated);
    }
}
