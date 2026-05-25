package vip.mate.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.Map;

/**
 * Workspace 访问拦截器
 * <p>
 * 对标注了 {@link RequireWorkspaceRole} 的 Controller 方法，自动校验：
 * 1. 当前用户已认证
 * 2. 请求中有 X-Workspace-Id header（否则使用默认 workspace=1）
 * 3. 用户是该 workspace 的成员且角色 ≥ 注解要求的最低角色
 * <p>
 * 成员资格查询使用 Caffeine 缓存（60s TTL），避免每次请求查库。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceAccessInterceptor implements HandlerInterceptor {

    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AgentMapper agentMapper;

    /** 默认 workspace ID（未传 header 时使用） */
    private static final long DEFAULT_WORKSPACE_ID = 1L;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 只拦截 Controller 方法
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 检查注解：@RequireGlobalAdmin 与 @RequireWorkspaceRole 二选一
        RequireGlobalAdmin globalAdmin = handlerMethod.getMethodAnnotation(RequireGlobalAdmin.class);
        RequireWorkspaceRole annotation = handlerMethod.getMethodAnnotation(RequireWorkspaceRole.class);
        if (globalAdmin == null && annotation == null) {
            return true;
        }

        // 获取当前认证用户
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            // 未认证的请求由 Spring Security 处理，这里不拦截
            return true;
        }

        String username = auth.getName();
        UserEntity user = authService.findByUsername(username);
        if (user == null) {
            sendForbidden(response, "User not found");
            return false;
        }

        boolean isGlobalAdmin = "admin".equalsIgnoreCase(user.getRole());

        // 全局 admin 注解：必须是 mate_user.role=admin，与工作区无关
        if (globalAdmin != null && !isGlobalAdmin) {
            log.warn("Global admin access denied: user={}, path={}", username, request.getRequestURI());
            sendForbidden(response, "Global administrator role required");
            return false;
        }
        if (globalAdmin != null) {
            return true;
        }

        // @RequireWorkspaceRole 分支：全局 admin 跳过
        if (isGlobalAdmin) {
            return true;
        }

        long workspaceId = resolveWorkspaceId(request);
        String minRole = annotation.value();
        if (!workspaceService.hasPermissionCached(workspaceId, user.getId(), minRole)) {
            log.warn("Workspace access denied: user={}, workspaceId={}, requiredRole={}", username, workspaceId, minRole);
            sendForbidden(response, "Workspace permission denied: requires " + minRole + " role");
            return false;
        }

        // The role check above only proves the user belongs to the *header*
        // workspace — not that a path-bound {agentId} actually lives there.
        // Without this a member of workspace A could read workspace B's agent
        // memory / context files by supplying B's agent id with their own header.
        if (!agentBelongsToWorkspace(request, workspaceId)) {
            log.warn("Cross-workspace agent access denied: user={}, workspaceId={}, path={}",
                    username, workspaceId, request.getRequestURI());
            sendForbidden(response, "Agent does not belong to the current workspace");
            return false;
        }

        return true;
    }

    /**
     * When the matched route carries an {@code {agentId}} path variable, verify
     * that agent belongs to the resolved workspace. Allows the request through
     * when there is no agent id, the id is unparseable, the agent does not
     * exist (so the handler can return its own 404), or the agent has not been
     * assigned a workspace.
     */
    @SuppressWarnings("unchecked")
    private boolean agentBelongsToWorkspace(HttpServletRequest request, long workspaceId) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map)) {
            return true;
        }
        Object rawAgentId = ((Map<String, String>) attr).get("agentId");
        if (rawAgentId == null) {
            return true;
        }
        long agentId;
        try {
            agentId = Long.parseLong(rawAgentId.toString());
        } catch (NumberFormatException e) {
            return true;
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null || agent.getWorkspaceId() == null) {
            return true;
        }
        return agent.getWorkspaceId() == workspaceId;
    }

    private long resolveWorkspaceId(HttpServletRequest request) {
        String header = request.getHeader("X-Workspace-Id");
        if (header != null && !header.isBlank()) {
            try {
                return Long.parseLong(header.trim());
            } catch (NumberFormatException e) {
                return DEFAULT_WORKSPACE_ID;
            }
        }
        return DEFAULT_WORKSPACE_ID;
    }

    private void sendForbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"msg\":\"" + message + "\",\"data\":null}");
    }
}
