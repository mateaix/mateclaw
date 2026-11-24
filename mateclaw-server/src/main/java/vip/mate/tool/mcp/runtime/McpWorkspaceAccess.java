package vip.mate.tool.mcp.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentToolSet;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.repository.McpServerMapper;

import java.util.HashSet;

/** Shared scope policy for MCP schemas, bindings and live calls (#695). */
@Component
@RequiredArgsConstructor
public class McpWorkspaceAccess {
    private final McpServerMapper mapper;
    private final AuthService authService;

    public boolean canAccess(Long serverId, Long workspaceId) {
        McpServerEntity server = mapper.selectById(serverId);
        return visible(server, workspaceId);
    }

    public boolean canUse(Long serverId, Long workspaceId) {
        McpServerEntity server = mapper.selectById(serverId);
        return visible(server, workspaceId) && Boolean.TRUE.equals(server.getEnabled());
    }

    private boolean visible(McpServerEntity server, Long workspaceId) {
        return server != null && !Integer.valueOf(1).equals(server.getDeleted())
                && (server.getWorkspaceId() == null || server.getWorkspaceId().equals(workspaceId));
    }

    public void requireMutable(Long serverId, Long workspaceId, Authentication authentication) {
        var server = mapper.selectById(serverId);
        if (!visible(server, workspaceId)) {
            throw new MateClawException("err.mcp.not_found", "MCP server not found: " + serverId);
        }
        if (server.getWorkspaceId() == null) {
            var user = authentication == null ? null : authService.findByUsername(authentication.getName());
            if (user == null || !"admin".equalsIgnoreCase(user.getRole())) {
                throw new MateClawException("err.workspace.forbidden", 403,
                        "Only system administrators can manage global MCP servers");
            }
        }
    }

    public AgentToolSet scope(AgentToolSet tools, Long workspaceId) {
        var denied = new HashSet<String>();
        var visibleServers = new java.util.HashMap<Long, Boolean>();
        for (var callback : tools.callbacks()) {
            var name = callback.getToolDefinition().name();
            var ref = McpToolNameResolver.parse(name);
            if (ref != null && !visibleServers.computeIfAbsent(ref.serverId(), id -> canUse(id, workspaceId))) denied.add(name);
        }
        return tools.withDeniedToolsFiltered(denied);
    }
}
