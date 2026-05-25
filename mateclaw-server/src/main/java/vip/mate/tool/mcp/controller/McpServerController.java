package vip.mate.tool.mcp.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.model.McpToolDescriptor;
import vip.mate.tool.mcp.runtime.McpClientManager.ConnectionResult;
import vip.mate.tool.mcp.service.McpServerService;

import java.util.List;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * MCP Server 管理接口
 * <p>
 * 安全说明：MCP Server 配置涉及注册外部可执行进程（stdio command）和远程服务端点，
 * 属于系统管理级能力。当前所有 /api/v1/** 接口均需 authenticated（见 SecurityConfig），
 * 且系统仅有 admin 角色，因此已满足 admin-only 要求。
 * 若后续引入多用户/多角色，必须在此类上增加 @PreAuthorize("hasRole('ADMIN')") 并启用
 * {@code @EnableMethodSecurity}。
 *
 * @author MateClaw Team
 */
@Tag(name = "MCP Server 管理")
@RestController
@RequestMapping("/api/v1/mcp/servers")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;
    private final vip.mate.tool.disclosure.ToolDisclosureService toolDisclosureService;

    @Operation(summary = "获取 MCP Server 列表")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<McpServerEntity>> list() {
        return R.ok(mcpServerService.sanitizeList(mcpServerService.listAll()));
    }

    @Operation(summary = "获取 MCP Server 详情")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> get(@PathVariable Long id) {
        return R.ok(mcpServerService.sanitize(mcpServerService.getById(id)));
    }

    @Operation(summary = "创建 MCP Server")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> create(@RequestBody McpServerEntity entity) {
        McpServerEntity created = mcpServerService.create(entity);
        return R.ok(mcpServerService.sanitize(created));
    }

    @Operation(summary = "更新 MCP Server")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> update(@PathVariable Long id, @RequestBody McpServerEntity entity) {
        McpServerEntity updated = mcpServerService.update(id, entity);
        return R.ok(mcpServerService.sanitize(updated));
    }

    @Operation(summary = "删除 MCP Server")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> delete(@PathVariable Long id) {
        mcpServerService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用/禁用 MCP Server")
    @PutMapping("/{id}/toggle")
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        McpServerEntity toggled = mcpServerService.toggle(id, enabled);
        return R.ok(mcpServerService.sanitize(toggled));
    }

    @Operation(summary = "设置 MCP Server 披露分级（core / extension），整组工具跟随")
    @PutMapping("/{id}/disclosure-tier")
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> setDisclosureTier(@PathVariable Long id,
                                                @RequestBody java.util.Map<String, String> body) {
        String tier = body == null ? null : body.get("tier");
        if (!vip.mate.tool.disclosure.DisclosureTier.isValidToken(tier)) {
            return R.fail(400, "tier must be 'core' or 'extension'");
        }
        McpServerEntity updated = mcpServerService.setDisclosureTier(id, tier);
        toolDisclosureService.invalidate();
        return R.ok(mcpServerService.sanitize(updated));
    }

    @Operation(summary = "测试 MCP Server 连接")
    @PostMapping("/{id}/test")
    @RequireWorkspaceRole("admin")
    public R<ConnectionResult> test(@PathVariable Long id) {
        ConnectionResult result = mcpServerService.testConnectionById(id);
        return R.ok(result);
    }

    /**
     * List the tools surfaced by an MCP server.
     *
     * <p>Reads from the in-memory cache populated on connect/refresh, so the
     * call is non-blocking and safe to poll from the admin UI.
     *
     * <p><b>Response contract</b> (matches project convention "HTTP 200 + biz code"):
     * <ul>
     *   <li>Server exists, connected with discovered tools → HTTP 200, {@code code=200},
     *       {@code data} = list of {name, description, inputSchema}.</li>
     *   <li>Server exists but disconnected / in error / no tools → HTTP 200,
     *       {@code code=200}, {@code data=[]}. The UI renders "no tools yet" rather
     *       than an error toast.</li>
     *   <li>Server id does not exist → HTTP 200, {@code code=500},
     *       {@code msg="MCP server 不存在: {id}"}. The not-found path goes through
     *       {@code MateClawException("err.mcp.not_found")} which the global handler
     *       maps to a 200/500 envelope; callers detect not-found via {@code code != 200},
     *       not via the HTTP status (consistent with every other CRUD endpoint).</li>
     * </ul>
     */
    @Operation(summary = "列出 MCP Server 已发现的工具")
    @GetMapping("/{id}/tools")
    @RequireWorkspaceRole("admin")
    public R<List<McpToolDescriptor>> listTools(@PathVariable Long id) {
        return R.ok(mcpServerService.listToolsByServer(id));
    }

    @Operation(summary = "刷新所有 MCP Server 连接")
    @PostMapping("/refresh")
    @RequireWorkspaceRole("admin")
    public R<Void> refresh() {
        mcpServerService.refreshAll();
        return R.ok();
    }
}
