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
import org.springframework.security.core.Authentication;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * MCP Server 管理接口
 * <p>
 * Workspace administrators manage local servers; global ownership requires system administration.
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

    private final AuthService authService;

    @Operation(summary = "获取 MCP Server 列表")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<McpServerEntity>> list(@RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(mcpServerService.sanitizeList(mcpServerService.listVisible(workspace(workspaceId))));
    }

    @Operation(summary = "获取 MCP Server 详情")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> get(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(mcpServerService.sanitize(requireVisible(id, workspaceId)));
    }

    @Operation(summary = "创建 MCP Server")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> create(@RequestBody McpServerEntity entity,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(required = false) Boolean global, Authentication authentication) {
        if (Boolean.TRUE.equals(global)) requireSystemAdmin(authentication);
        entity.setWorkspaceId(Boolean.TRUE.equals(global) ? null : workspace(workspaceId));
        McpServerEntity created = mcpServerService.create(entity);
        return R.ok(mcpServerService.sanitize(created));
    }

    @Operation(summary = "更新 MCP Server")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> update(@PathVariable Long id, @RequestBody McpServerEntity entity,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(required = false) Boolean global, Authentication authentication) {
        McpServerEntity existing = requireMutable(id, workspaceId, authentication);
        Long owner = existing.getWorkspaceId();
        if (global != null) {
            Long target = global ? null : workspace(workspaceId);
            if (!java.util.Objects.equals(owner, target)) requireSystemAdmin(authentication);
            owner = target;
        } else if (entity.getWorkspaceId() != null && !entity.getWorkspaceId().equals(owner)) {
            throw new MateClawException("MCP ownership changes require an explicit global parameter");
        }
        entity.setWorkspaceId(owner);
        McpServerEntity updated = mcpServerService.update(id, entity, owner);
        return R.ok(mcpServerService.sanitize(updated));
    }

    @Operation(summary = "删除 MCP Server")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> delete(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId, Authentication authentication) {
        requireMutable(id, workspaceId, authentication);
        mcpServerService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用/禁用 MCP Server")
    @PutMapping("/{id}/toggle")
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId, Authentication authentication) {
        requireMutable(id, workspaceId, authentication);
        McpServerEntity toggled = mcpServerService.toggle(id, enabled);
        return R.ok(mcpServerService.sanitize(toggled));
    }

    @Operation(summary = "设置 MCP Server 披露分级（core / extension），整组工具跟随")
    @PutMapping("/{id}/disclosure-tier")
    @RequireWorkspaceRole("admin")
    public R<McpServerEntity> setDisclosureTier(@PathVariable Long id,
                                                @RequestBody java.util.Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId, Authentication authentication) {
        requireMutable(id, workspaceId, authentication);
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
    public R<ConnectionResult> test(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId, Authentication authentication) {
        requireMutable(id, workspaceId, authentication);
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
    public R<List<McpToolDescriptor>> listTools(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        requireVisible(id, workspaceId);
        return R.ok(mcpServerService.listToolsByServer(id));
    }

    @Operation(summary = "刷新所有 MCP Server 连接")
    @PostMapping("/refresh")
    @RequireWorkspaceRole("admin")
    public R<Void> refresh(Authentication authentication) {
        requireSystemAdmin(authentication);
        mcpServerService.refreshAll();
        return R.ok();
    }
    private long workspace(Long workspaceId) {
        return workspaceId != null ? workspaceId : 1L;
    }

    private McpServerEntity requireVisible(Long id, Long workspaceId) {
        McpServerEntity server = mcpServerService.getById(id);
        if (!McpServerService.isVisible(server, workspace(workspaceId))) {
            throw new MateClawException("err.mcp.not_found", "MCP server 不存在: " + id);
        }
        return server;
    }

    private McpServerEntity requireMutable(Long id, Long workspaceId, Authentication authentication) {
        McpServerEntity server = requireVisible(id, workspaceId);
        if (server.getWorkspaceId() == null) requireSystemAdmin(authentication);
        return server;
    }

    private void requireSystemAdmin(Authentication authentication) {
        var user = authentication != null ? authService.findByUsername(authentication.getName()) : null;
        if (user == null || !"admin".equalsIgnoreCase(user.getRole())) {
            throw new MateClawException("err.workspace.forbidden", 403, "Only system administrators can manage global MCP servers or change ownership");
        }
    }
}
