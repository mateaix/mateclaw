package vip.mate.workspace.core.security;

/**
 * Module-level capability constants for workspace RBAC.
 * <p>
 * Capability is the abstraction that sits between roles (viewer/member/admin/owner)
 * and concrete REST methods. Frontend routes declare {@code meta.requiredCapability}
 * and the backend {@code /api/v1/workspaces/&#123;id&#125;/access} endpoint returns
 * the resolved capability set for the current user.
 * <p>
 * Granularity is intentionally coarse: one capability per UI module, not one per
 * HTTP action.
 */
public final class Capability {

    private Capability() {}

    /** Conversation runtime: send/stream/execute + own conversation read/write. */
    public static final String CHAT = "chat";

    /** Wiki knowledge base / page / relation read-only. */
    public static final String VIEW_WIKI = "view:wiki";

    /** Memory / Fact / Dream operator surfaces (separate from chat runtime). */
    public static final String VIEW_MEMORY = "view:memory";

    /** Dashboard, token usage, cron run history. */
    public static final String VIEW_DASHBOARD = "view:dashboard";

    /** Wiki write: KB CRUD, transformations, research, hot cache. */
    public static final String MANAGE_WIKI = "manage:wiki";

    /** Agent CRUD, bindings (skill/tool/provider), cron jobs, templates. */
    public static final String MANAGE_AGENTS = "manage:agents";

    /** Skill catalog management, install, templates, secrets. */
    public static final String MANAGE_SKILLS = "manage:skills";

    /** Channel CRUD, health, preflight. */
    public static final String MANAGE_CHANNELS = "manage:channels";

    /** LLM provider, model config, OAuth credentials, datasource. */
    public static final String MANAGE_MODELS = "manage:models";

    /** Tool guard, file guard, audit, activity feed. */
    public static final String MANAGE_SECURITY = "manage:security";

    /** System settings, feature flags, MCP, plugins, ACP, workflow, trigger, members. */
    public static final String MANAGE_SETTINGS = "manage:settings";
}
