package vip.mate.workspace.core.annotation;

import java.lang.annotation.*;

/**
 * Methods annotated with this require {@code mate_user.role='admin'} (system-wide
 * administrator). Unlike {@link RequireWorkspaceRole}, this is not a per-workspace
 * check &mdash; the user must be a global admin regardless of workspace membership.
 * <p>
 * Used by:
 * <ul>
 *   <li>{@code AuthController.listUsers / createUser} &mdash; user management</li>
 *   <li>{@code AgentRuntimeController.*} &mdash; runtime ops under {@code /api/v1/admin}</li>
 *   <li>{@code SubagentController.*} &mdash; runtime subagent operations</li>
 *   <li>workspace creation (only global admins may create new workspaces)</li>
 * </ul>
 * Workspace-scoped operations should keep using {@link RequireWorkspaceRole}; a
 * global admin automatically passes those by bypassing the workspace check in
 * {@code WorkspaceAccessInterceptor}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireGlobalAdmin {
}
