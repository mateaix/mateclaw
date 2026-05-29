import { createRouter, createWebHistory } from 'vue-router'
import type { Capability } from '@/composables/capabilities'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

// Augment vue-router's RouteMeta so each route can declare its capability gate.
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    keepAlive?: boolean
    requireAdmin?: boolean
    requiredCapability?: Capability
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('@/views/layout/MainLayout.vue'),
      redirect: '/chat',
      children: [
        // ==================== Core ====================
        {
          path: 'chat',
          name: 'Chat',
          component: () => import('@/views/ChatConsole.vue'),
          meta: { title: 'Chat', requiredCapability: 'chat' },
        },
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/Dashboard.vue'),
          meta: { title: 'Dashboard', requiredCapability: 'view:dashboard' },
        },
        {
          path: 'agents',
          name: 'Agents',
          component: () => import('@/views/Agents.vue'),
          meta: { title: 'Agents', requiredCapability: 'manage:agents' },
        },
        {
          // Live runtime view folded into the Agents page as a sub-view.
          // Kept as a redirect so old links / bookmarks still resolve.
          path: 'backstage',
          redirect: { path: '/agents', query: { view: 'live' } },
        },
        {
          path: 'wiki',
          name: 'Wiki',
          component: () => import('@/views/Wiki/index.vue'),
          meta: { title: 'Wiki', requiredCapability: 'view:wiki' },
        },
        {
          path: 'enterprise',
          name: 'Enterprise',
          component: () => import('@/views/Enterprise/index.vue'),
          meta: { title: 'Enterprise Scenarios', requiredCapability: 'manage:agents' },
        },
        {
          path: 'memory',
          name: 'Memory',
          component: () => import('@/views/Memory/index.vue'),
          meta: { title: 'Memory', requiredCapability: 'view:memory' },
        },
        // ==================== Connect ====================
        {
          path: 'channels',
          name: 'Channels',
          component: () => import('@/views/Channels.vue'),
          // keepAlive: cache the component instance so navigating away and
          // back doesn't re-mount + re-fetch the list. Channels.vue must
          // pause polling in onDeactivated to avoid a leaked timer.
          meta: { title: 'Channels', keepAlive: true, requiredCapability: 'manage:channels' },
        },
        {
          path: 'skills',
          name: 'Skills',
          component: () => import('@/views/SkillMarket.vue'),
          meta: { title: 'Skills', requiredCapability: 'manage:skills' },
        },
        // Tools 顶层入口已降级到 Settings ▸ Tools (Catalog) (RFC-090 Phase 1)
        // 旧路径 /tools 由下方 redirect 兼容
        {
          path: 'activity',
          name: 'Activity',
          component: () => import('@/views/Security/Activity/index.vue'),
          meta: { title: 'Activity', requiredCapability: 'manage:security' },
        },
        // RFC-091: Skill 模板库 + 创作向导
        {
          path: 'skills/templates',
          name: 'SkillTemplates',
          component: () => import('@/views/SkillTemplates.vue'),
          meta: { title: 'Skill Templates', requiredCapability: 'manage:skills' },
        },
        {
          path: 'plugins',
          name: 'Plugins',
          component: () => import('@/views/Plugins.vue'),
          meta: { title: 'Plugins', requiredCapability: 'manage:settings' },
        },
        // ==================== Settings (absorbs advanced pages) ====================
        {
          path: 'settings',
          component: () => import('@/views/Settings/Layout.vue'),
          redirect: '/settings/models',
          children: [
            {
              path: 'models',
              name: 'SettingsModels',
              component: () => import('@/views/Settings/Models/index.vue'),
              meta: { title: 'Settings - Models', requiredCapability: 'manage:models' },
            },
            {
              path: 'system',
              name: 'SettingsSystem',
              component: () => import('@/views/Settings/System/index.vue'),
              meta: { title: 'Settings - System', requiredCapability: 'manage:settings' },
            },
            {
              path: 'image',
              name: 'SettingsImage',
              component: () => import('@/views/Settings/Image/index.vue'),
              meta: { title: 'Settings - Image', requiredCapability: 'manage:models' },
            },
            {
              path: 'tts',
              name: 'SettingsTts',
              component: () => import('@/views/Settings/Tts/index.vue'),
              meta: { title: 'Settings - TTS', requiredCapability: 'manage:models' },
            },
            {
              path: 'stt',
              name: 'SettingsStt',
              component: () => import('@/views/Settings/Stt/index.vue'),
              meta: { title: 'Settings - STT', requiredCapability: 'manage:models' },
            },
            {
              path: 'music',
              name: 'SettingsMusic',
              component: () => import('@/views/Settings/Music/index.vue'),
              meta: { title: 'Settings - Music', requiredCapability: 'manage:models' },
            },
            {
              path: 'video',
              name: 'SettingsVideo',
              component: () => import('@/views/Settings/Video/index.vue'),
              meta: { title: 'Settings - Video', requiredCapability: 'manage:models' },
            },
            {
              path: 'model3d',
              name: 'SettingsModel3D',
              component: () => import('@/views/Settings/Model3D/index.vue'),
              meta: { title: 'Settings - 3D Model', requiredCapability: 'manage:models' },
            },
            // Workspace management
            {
              path: 'workspaces',
              name: 'SettingsWorkspaces',
              component: () => import('@/views/Security/Workspaces/index.vue'),
              meta: { title: 'Settings - Workspaces', requiredCapability: 'manage:settings' },
            },
            {
              path: 'members',
              name: 'SettingsMembers',
              component: () => import('@/views/Security/Members/index.vue'),
              meta: { title: 'Settings - Members', requiredCapability: 'manage:settings' },
            },
            // RFC-090 Phase 4: Activity 提升到顶层 /activity（下方 children-out
            // 的 settings/activity redirect 兼容旧链接，此处不再注册子路由）
            // Advanced (absorbed from top-level nav)
            {
              path: 'agent-context',
              name: 'SettingsAgentContext',
              component: () => import('@/views/AgentContext.vue'),
              meta: { title: 'Settings - Agent Context', requiredCapability: 'manage:agents' },
            },
            {
              // Unified scheduler — scheduled jobs, event triggers and run
              // history under one tabbed page (?tab= selects the tab).
              path: 'scheduler',
              name: 'SettingsScheduler',
              component: () => import('@/views/Scheduler/index.vue'),
              meta: { title: 'Settings - Scheduler', requiredCapability: 'manage:agents' },
            },
            {
              path: 'skill-curator',
              name: 'SettingsSkillCurator',
              component: () => import('@/views/Settings/SkillCurator/index.vue'),
              meta: { title: 'Settings - Skill Curator', requiredCapability: 'manage:settings' },
            },
            {
              path: 'workflows',
              name: 'SettingsWorkflows',
              component: () => import('@/views/Workflows.vue'),
              meta: { title: 'Settings - Workflows', requiredCapability: 'manage:settings' },
            },
            {
              path: 'datasources',
              name: 'SettingsDatasources',
              component: () => import('@/views/Datasources.vue'),
              meta: { title: 'Settings - Datasources', requiredCapability: 'manage:models' },
            },
            {
              path: 'mcp-servers',
              name: 'SettingsMcpServers',
              component: () => import('@/views/McpServers.vue'),
              meta: { title: 'Settings - MCP Connections', requiredCapability: 'manage:settings' },
            },
            {
              path: 'tools',
              name: 'SettingsTools',
              component: () => import('@/views/Tools.vue'),
              meta: { title: 'Settings - Tools Catalog', requiredCapability: 'manage:settings' },
            },
            // RFC-090 Phase 7: ACP endpoints (External coding agents)
            {
              path: 'acp',
              name: 'SettingsAcpEndpoints',
              component: () => import('@/views/AcpEndpoints.vue'),
              meta: { title: 'Settings - ACP Endpoints', requiredCapability: 'manage:settings' },
            },
            {
              path: 'token-usage',
              name: 'SettingsTokenUsage',
              component: () => import('@/views/TokenUsage.vue'),
              meta: { title: 'Settings - Token Usage', requiredCapability: 'view:dashboard' },
            },
            {
              path: 'feature-flags',
              name: 'SettingsFeatureFlags',
              component: () => import('@/views/Settings/FeatureFlags/index.vue'),
              meta: { title: 'Settings - Feature Flags', requiredCapability: 'manage:settings' },
            },
            {
              path: 'about',
              name: 'SettingsAbout',
              component: () => import('@/views/Settings/About/index.vue'),
              meta: { title: 'Settings - About' },
            },
          ],
        },
        // ==================== Security ====================
        {
          path: 'security',
          component: () => import('@/views/Security/Layout.vue'),
          redirect: '/security/tool-guard',
          children: [
            {
              path: 'tool-guard',
              name: 'SecurityToolGuard',
              component: () => import('@/views/Security/ToolGuard/index.vue'),
              meta: { title: 'Security - Tool Guard', requiredCapability: 'manage:security' },
            },
            {
              path: 'file-guard',
              name: 'SecurityFileGuard',
              component: () => import('@/views/Security/FileGuard/index.vue'),
              meta: { title: 'Security - File Guard', requiredCapability: 'manage:security' },
            },
            {
              path: 'audit-logs',
              name: 'SecurityAuditLogs',
              component: () => import('@/views/Security/AuditLogs/index.vue'),
              meta: { title: 'Security - Audit Logs', requiredCapability: 'manage:security' },
            },
            {
              path: 'auto-approve',
              name: 'SecurityAutoApprove',
              component: () => import('@/views/Security/AutoApproveGrants/index.vue'),
              meta: { title: 'Security - Auto Approve', requiredCapability: 'manage:security' },
            },
          ],
        },
        // ==================== Forbidden ====================
        {
          path: 'forbidden',
          name: 'Forbidden',
          component: () => import('@/views/Forbidden.vue'),
          meta: { title: 'Forbidden' },
        },
        // ==================== Sessions admin ====================
        // Cross-channel conversation manager. Surfaced from ChatConsole's
        // header overflow menu so the user can audit / switch model per
        // conversation without leaving the chat surface.
        {
          path: 'sessions',
          name: 'Sessions',
          component: () => import('@/views/Sessions.vue'),
          meta: { title: 'sessions.title' },
        },
        // ==================== Redirects (backward compatibility) ====================
        { path: 'workspace', redirect: '/settings/agent-context' },
        { path: 'security/workspaces', redirect: '/settings/workspaces' },
        { path: 'security/members', redirect: '/settings/members' },
        // RFC-090 Phase 4: Activity 提升到顶层
        { path: 'security/activity', redirect: '/activity' },
        { path: 'settings/activity', redirect: '/activity' },
        // Scheduler absorbs the former Cron Jobs + Triggers pages.
        { path: 'cron-jobs', redirect: '/settings/scheduler' },
        { path: 'settings/cron-jobs', redirect: '/settings/scheduler' },
        { path: 'settings/triggers', redirect: { path: '/settings/scheduler', query: { tab: 'triggers' } } },
        { path: 'datasources', redirect: '/settings/datasources' },
        { path: 'mcp-servers', redirect: '/settings/mcp-servers' },
        { path: 'token-usage', redirect: '/settings/token-usage' },
        // RFC-090 Phase 1: Tools 顶层降级到 Settings
        { path: 'tools', redirect: '/settings/tools' },
      ],
    },
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/Login.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/chat',
    },
  ],
})

// Auth + capability guard. Order matters: bail to /login before we touch the
// workspace store, and never let an uninitialized capability set fall through
// to a protected route (the store enforces default-deny while accessLoaded is
// false; we await refreshAccess so the decision is made on real data).
router.beforeEach(async (to) => {
  if (import.meta.env.VITE_SKIP_AUTH === 'true') return true
  const token = localStorage.getItem('token')

  if (to.name === 'Login' && token) return { path: '/' }
  if (to.name !== 'Login' && !token) return { name: 'Login' }
  if (to.name === 'Login' || to.name === 'Forbidden') return true

  const store = useWorkspaceStore()
  if (!store.accessLoaded) {
    if (!store.workspaces.length) {
      await store.fetchWorkspaces()
    } else {
      await store.refreshAccess()
    }
  }

  const requireAdmin = to.meta.requireAdmin === true
  if (requireAdmin && !store.isGlobalAdmin) {
    return store.can('chat') ? { path: '/chat' } : { path: '/forbidden' }
  }

  const required = to.meta.requiredCapability
  if (required && !store.can(required)) {
    return store.can('chat') ? { path: '/chat' } : { path: '/forbidden' }
  }
  return true
})

export default router
