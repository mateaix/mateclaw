<template>
  <div class="mc-page-shell settings-shell">
    <div class="mc-page-frame settings-frame">
      <div class="mc-page-inner settings-layout">
        <div class="settings-nav mc-surface-card" :class="{ 'nav-collapsed': navCollapsed }">
          <div v-if="!navCollapsed" class="settings-nav__intro">
            <div class="mc-page-kicker">{{ t('settings.kicker') }}</div>
            <h2 class="nav-title">{{ t('settings.title') }}</h2>
          </div>
          <template v-for="section in sections" :key="section.id">
            <div v-if="section.isDivider && !navCollapsed" class="nav-divider">{{ section.label }}</div>
            <el-tooltip
              v-else-if="!section.isDivider"
              :content="section.label"
              placement="right"
              :disabled="!navCollapsed"
            >
              <router-link
                :to="section.path"
                class="nav-item"
                :class="{ active: isActive(section.path) }"
              >
                <span class="nav-icon" v-html="section.icon"></span>
                <span v-if="!navCollapsed" class="nav-label">{{ section.label }}</span>
              </router-link>
            </el-tooltip>
          </template>
          <!-- Floating collapse toggle, pinned to the bottom of the nav -->
          <button
            class="nav-collapse-btn"
            @click="toggleNav"
            :title="navCollapsed ? t('common.expandSidebar') : t('common.collapseSidebar')"
          >
            <svg v-if="!navCollapsed" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
          </button>
        </div>

        <div class="settings-content mc-surface-card">
          <div class="settings-content__inner">
            <router-view />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useMediaQuery, BREAKPOINTS } from '@/composables/useBreakpoint'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const { t } = useI18n()

// Routes that benefit from extra editor width — the sub-nav auto-collapses
// to a 56px rail unless the user has explicitly toggled it open.
const COMPACT_ROUTES = ['/settings/workflows']

const navCollapsed = ref(localStorage.getItem('mc-settings-nav-collapsed') === 'true')
const userExplicit = ref(localStorage.getItem('mc-settings-nav-collapsed') !== null)
const compactViewport = useMediaQuery(BREAKPOINTS.compact)

function toggleNav() {
  navCollapsed.value = !navCollapsed.value
  userExplicit.value = true
  localStorage.setItem('mc-settings-nav-collapsed', String(navCollapsed.value))
}

function isCompactRoute(path: string): boolean {
  return COMPACT_ROUTES.some((p) => path.startsWith(p))
}

function recomputeAuto() {
  if (userExplicit.value) return
  navCollapsed.value = isCompactRoute(route.path) || compactViewport.value
}

watch(() => route.path, recomputeAuto)
watch(compactViewport, recomputeAuto, { immediate: true })

const sections = computed(() => [
  {
    id: 'model',
    path: '/settings/models',
    label: t('settings.sections.model'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 8V4H8"/><rect x="4" y="8" width="16" height="12" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/></svg>',
  },
  {
    id: 'system',
    path: '/settings/system',
    label: t('settings.sections.system'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.09a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9c0 .66.26 1.3.73 1.77.47.47 1.11.73 1.77.73H21a2 2 0 1 1 0 4h-.09c-.66 0-1.3.26-1.77.73-.47.47-.73 1.11-.73 1.77z"/></svg>',
  },
  {
    id: 'image',
    path: '/settings/image',
    label: t('settings.sections.image'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>',
  },
  {
    id: 'tts',
    path: '/settings/tts',
    label: t('settings.sections.tts'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>',
  },
  {
    id: 'stt',
    path: '/settings/stt',
    label: t('settings.sections.stt'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>',
  },
  {
    id: 'music',
    path: '/settings/music',
    label: t('settings.sections.music'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
  },
  {
    id: 'video',
    path: '/settings/video',
    label: t('settings.sections.video'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>',
  },
  {
    id: 'model3d',
    path: '/settings/model3d',
    label: t('settings.sections.model3d'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2 L21 7 L21 17 L12 22 L3 17 L3 7 Z"/><path d="M3 7 L12 12 L21 7"/><path d="M12 12 L12 22"/></svg>',
  },
  // Divider: Workspace
  { id: 'divider-workspace', path: '', label: t('settings.sections.workspace', 'Workspace'), icon: '', isDivider: true },
  {
    id: 'workspaces',
    path: '/settings/workspaces',
    label: t('security.sections.workspaces', 'Workspaces'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="7" width="20" height="14" rx="2" ry="2"/><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/></svg>',
  },
  {
    id: 'agent-context',
    path: '/settings/agent-context',
    label: t('nav.agentContext', '智能体上下文'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="9" y1="21" x2="9" y2="9"/></svg>',
  },
  {
    id: 'members',
    path: '/settings/members',
    label: t('security.sections.members', 'Members'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
  },
  // RFC-090 Phase 4: Activity 子项移除，提升至顶层 /activity
  // Divider: Advanced
  { id: 'divider-advanced', path: '', label: t('settings.sections.advanced'), icon: '', isDivider: true },
  {
    id: 'scheduler',
    path: '/settings/scheduler',
    label: t('nav.scheduler', 'Scheduler'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>',
  },
  {
    id: 'workflows',
    path: '/settings/workflows',
    label: t('nav.workflows', 'Workflows'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>',
  },
  {
    id: 'skill-curator',
    path: '/settings/skill-curator',
    label: t('settings.sections.skillCurator', 'Skill Curator'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 8v13H3V8"/><rect x="1" y="3" width="22" height="5" rx="1"/><line x1="10" y1="12" x2="14" y2="12"/></svg>',
  },
  {
    id: 'datasources',
    path: '/settings/datasources',
    label: t('nav.datasources'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>',
  },
  {
    id: 'mcp-servers',
    path: '/settings/mcp-servers',
    label: t('nav.mcpConnections'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="8" rx="2" ry="2"/><rect x="2" y="14" width="20" height="8" rx="2" ry="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>',
  },
  {
    id: 'tools',
    path: '/settings/tools',
    label: t('nav.toolsCatalog'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>',
  },
  // RFC-090 Phase 7: ACP endpoints
  {
    id: 'acp',
    path: '/settings/acp',
    label: t('nav.acpEndpoints'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>',
  },
  {
    id: 'token-usage',
    path: '/settings/token-usage',
    label: t('nav.tokenUsage'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>',
  },
  {
    id: 'feature-flags',
    path: '/settings/feature-flags',
    label: t('settings.sections.featureFlags', 'Feature Flags'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 21V4l12 4-12 4"/><path d="M4 12v9"/></svg>',
  },
  {
    id: 'about',
    path: '/settings/about',
    label: t('settings.sections.about'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>',
  },
])

function isActive(path: string) {
  return route.path === path
}
</script>

<style scoped>
.settings-shell {
  background: transparent;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.settings-frame {
  height: min(calc(100vh - 28px), 100%);
  min-height: 0;
  overflow: hidden;
}

.settings-layout {
  display: flex;
  height: 100%;
  min-height: 0;
  gap: 18px;
}

.settings-nav {
  width: 210px;
  min-width: 210px;
  padding: 14px 10px;
  overflow-y: auto;
  align-self: stretch;
  transition: width 0.25s ease, min-width 0.25s ease;
  display: flex;
  flex-direction: column;
}

.settings-nav.nav-collapsed {
  width: 56px;
  min-width: 56px;
  padding: 12px 8px;
}

.settings-nav__intro {
  padding: 4px 8px 10px;
  border-bottom: 1px solid var(--mc-border-light);
  margin-bottom: 6px;
}

.nav-title { font-size: 20px; font-weight: 700; color: var(--mc-text-primary); letter-spacing: -0.03em; margin: 0 0 4px; }
.nav-desc { color: var(--mc-text-secondary); font-size: 12px; line-height: 1.5; margin: 0; }
.nav-item { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px 10px; border: none; background: none; border-radius: 10px; font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; text-align: left; text-decoration: none; }
.nav-item:hover { background: var(--mc-bg-muted); color: var(--mc-text-primary); }
.nav-item.active { background: var(--mc-primary-bg); color: var(--mc-primary); font-weight: 600; box-shadow: inset 0 0 0 1px rgba(217, 109, 70, 0.08); }
.nav-item + .nav-item { margin-top: 2px; }

.nav-collapsed .nav-item {
  justify-content: center;
  padding: 10px 8px;
}

.nav-icon { width: 18px; height: 18px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; }
.nav-icon :deep(svg) { width: 18px; height: 18px; display: block; }
.nav-divider { font-size: 10px; font-weight: 700; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.1em; padding: 12px 8px 4px; margin-top: 2px; }

.nav-collapse-btn {
  align-self: center;
  margin-top: auto;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid rgba(217, 109, 70, 0.22);
  border-radius: 50%;
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  cursor: pointer;
  flex-shrink: 0;
  box-shadow:
    0 6px 16px rgba(217, 109, 70, 0.18),
    0 2px 4px rgba(217, 109, 70, 0.10),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
  transition:
    background 0.18s ease,
    color 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s cubic-bezier(0.4, 0, 0.2, 1);
  position: sticky;
  bottom: 8px;
}

.nav-collapse-btn:hover {
  background: var(--mc-primary);
  color: #fff;
  border-color: var(--mc-primary);
  box-shadow:
    0 10px 22px rgba(217, 109, 70, 0.34),
    0 3px 6px rgba(217, 109, 70, 0.20);
  transform: scale(1.06);
}

.nav-collapse-btn:active {
  transform: scale(0.96);
  transition-duration: 0.08s;
}

.nav-collapse-btn:focus-visible {
  outline: 2px solid var(--mc-primary);
  outline-offset: 2px;
}

.settings-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  padding: 22px;
}

.settings-content__inner {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
}

@media (max-width: 900px) {
  .settings-frame {
    height: auto;
    min-height: calc(100vh - 28px);
    overflow: visible;
  }

  .settings-layout { flex-direction: row; height: auto; }
  .settings-nav { width: 56px; min-width: 56px; max-height: none; align-self: auto; padding: 12px 8px; }
  .settings-nav .settings-nav__intro { display: none; }
  .settings-nav .nav-divider { display: none; }
  .settings-nav .nav-item { justify-content: center; padding: 10px 8px; }
  .settings-nav .nav-label { display: none; }
  .nav-collapse-btn { display: none; }
  .settings-content { min-height: 0; overflow: visible; }
  .settings-content__inner { overflow: visible; padding-right: 0; }
}
</style>
