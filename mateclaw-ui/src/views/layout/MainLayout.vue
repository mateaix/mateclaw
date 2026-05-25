<template>
  <div class="app-layout">
    <!-- 移动端背景遮罩 -->
    <Transition name="fade">
      <div v-if="isMobile && mobileMenuOpen" class="sidebar-backdrop" @click="mobileMenuOpen = false"></div>
    </Transition>

    <!-- 左侧导航栏 -->
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed && !isMobile, 'mobile-open': mobileMenuOpen }">
      <!-- Logo -->
      <div class="sidebar-logo">
        <div class="logo-icon">
          <img src="/logo/mateclaw_logo_s.png" alt="MateClaw" class="logo-img" />
        </div>
        <transition name="fade">
          <div v-if="!effectiveCollapsed" class="logo-text">
            <span class="logo-name">Mate<span class="logo-name-highlight">Claw</span></span>
            <span class="logo-version">v{{ appVersion }}</span>
          </div>
        </transition>
        <button
          class="collapse-btn"
          :title="sidebarToggleLabel"
          :aria-label="sidebarToggleLabel"
          @click="toggleSidebar"
        >
          <svg v-if="!effectiveCollapsed" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="15 18 9 12 15 6"/>
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
      </div>

      <!-- 工作区切换 -->
      <WorkspaceSwitcher :collapsed="effectiveCollapsed" />

      <!-- 导航菜单 -->
      <nav class="sidebar-nav">
        <template v-for="group in navGroups" :key="group.key">
          <div class="nav-group">
            <div v-if="!effectiveCollapsed" class="nav-group-title">{{ group.label }}</div>
            <McTooltip
              v-for="item in group.items"
              :key="item.path"
              :content="item.tooltip || item.label"
              placement="right"
              :disabled="!effectiveCollapsed"
            >
              <router-link
                :to="item.path"
                class="nav-item"
                :class="{ active: isNavItemActive(item) }"
                :title="effectiveCollapsed ? '' : (item.tooltip || '')"
                @click="onNavClick"
              >
                <span class="nav-icon" v-html="item.icon"></span>
                <span v-if="!effectiveCollapsed" class="nav-label">{{ item.label }}</span>
                <NavBadge
                  v-if="item.path === '/agents' && isAdminRole"
                  :dot="liveAlertActive"
                  tone="warning"
                  :collapsed="effectiveCollapsed"
                  :title="t('live.attention')"
                />
                <NavBadge
                  v-else-if="item.path === '/security' && isAdminRole"
                  :count="pendingApprovals"
                  tone="urgent"
                  :collapsed="effectiveCollapsed"
                  :title="t('notifications.pendingApprovals', { n: pendingApprovals })"
                />
              </router-link>
            </McTooltip>
          </div>
        </template>
      </nav>

      <!-- 底部 -->
      <div class="sidebar-footer">
        <template v-if="!sidebarCollapsed || isMobile">
          <!-- Doctor 健康指示器 -->
          <button class="health-indicator" :class="healthStatus" @click="showDoctor = true" :title="t('doctor.title')">
            <span class="health-dot"></span>
            <span class="health-label">{{ t('doctor.title') }}</span>
          </button>

          <div class="sidebar-utility-card">
            <div class="compact-utility-row">
              <span class="compact-utility-title">{{ t('nav.themeLabel') }}</span>
              <div class="theme-toggle-row theme-toggle-row--compact">
                <button
                  v-for="opt in themeOptions"
                  :key="opt.value"
                  class="theme-btn theme-btn--compact"
                  :class="{ active: themeStore.mode === opt.value }"
                  :title="opt.label"
                  @click="themeStore.setMode(opt.value)"
                >
                  <span v-html="opt.icon"></span>
                </button>
              </div>
            </div>

            <div class="compact-utility-row">
              <span class="compact-utility-title">{{ t('nav.languageLabel') }}</span>
              <div class="language-toggle-row language-toggle-row--compact">
                <button
                  v-for="opt in localeOptions"
                  :key="opt.value"
                  class="language-btn language-btn--compact"
                  :class="{ active: currentLocaleValue === opt.value }"
                  @click="changeLocale(opt.value)"
                >
                  <span class="language-abbr">{{ opt.short }}</span>
                </button>
              </div>
            </div>
          </div>

          <div class="user-info">
            <div class="user-avatar">{{ userInitial }}</div>
            <div class="user-detail">
              <div class="user-name">{{ username }}</div>
              <div class="user-role">{{ roleLabel }}</div>
            </div>
            <button class="change-password-btn" @click="showChangePassword = true" :title="t('auth.changePassword')">
              <el-icon :size="16"><Lock /></el-icon>
            </button>
            <button class="logout-btn" @click="logout" :title="t('nav.logout')">
              <el-icon :size="16"><SwitchButton /></el-icon>
            </button>
          </div>

          <div class="shortcuts-hint" :title="shortcutsHintText">
            <kbd>Ctrl+K</kbd>
            <span>{{ t('nav.shortcutAgents') }}</span>
            <span class="shortcuts-hint__sep">|</span>
            <kbd>Ctrl+N</kbd>
            <span>{{ t('nav.shortcutNew') }}</span>
          </div>
        </template>

        <template v-else>
          <div class="collapsed-footer-actions">
            <button class="footer-icon-btn" :class="healthStatus" @click="showDoctor = true" :title="t('doctor.title')">
              <span class="health-dot"></span>
            </button>
            <button class="footer-icon-btn" :title="t('nav.logout')" @click="logout">
              <el-icon :size="16"><SwitchButton /></el-icon>
            </button>
          </div>
        </template>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="main-content">
      <!-- 移动端顶部栏 -->
      <div v-if="isMobile" class="mobile-topbar">
        <button class="mobile-menu-btn" @click="mobileMenuOpen = true" :title="t('common.expandSidebar')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="3" y1="6" x2="21" y2="6"/>
            <line x1="3" y1="12" x2="21" y2="12"/>
            <line x1="3" y1="18" x2="21" y2="18"/>
          </svg>
        </button>
        <span class="mobile-topbar-title">Mate<span class="logo-name-highlight">Claw</span></span>
      </div>
      <!-- RFC-074 PR-1 fix: include route.path in the key so two different
           keepAlive routes (e.g. /channels and /settings/models) don't collide
           on the same vnode slot. Without this, switching between two keep-alive
           routes leaves both component trees mounted because Vue sees identical
           keys and patches in place. The comment must live OUTSIDE <keep-alive>
           — KeepAlive treats comments as children and rejects "more than one". -->
      <router-view v-slot="{ Component, route }">
        <keep-alive>
          <component :is="Component" :key="`${workspaceRouteKey}:${route.path}`" v-if="route.meta?.keepAlive" />
        </keep-alive>
        <component :is="Component" :key="`${workspaceRouteKey}:${route.path}`" v-if="!route.meta?.keepAlive" />
      </router-view>
    </main>

    <OnboardingWizard v-if="showOnboarding" @close="showOnboarding = false" />
    <DoctorDrawer :visible="showDoctor" @close="showDoctor = false" @status="onHealthStatus" />

    <ChangePasswordDialog v-model:visible="showChangePassword" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useIsMobile, useMediaQuery } from '@/composables/useBreakpoint'
import { useI18n } from 'vue-i18n'
import { useThemeStore } from '@/stores/useThemeStore'
import { version as appVersion } from '../../../package.json'
import type { ThemeMode } from '@/stores/useThemeStore'
import { http, settingsApi, setupApi } from '@/api/index'
import OnboardingWizard from '@/views/Onboarding/OnboardingWizard.vue'
import DoctorDrawer from '@/views/Doctor/DoctorDrawer.vue'
import WorkspaceSwitcher from '@/components/workspace/WorkspaceSwitcher.vue'
import NavBadge from '@/components/common/NavBadge.vue'
import McTooltip from '@/components/common/McTooltip.vue'
import { useNotificationCenter } from '@/composables/useNotificationCenter'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { applyLocale, currentLocale, type AppLocale } from '@/i18n'
import { SwitchButton, Lock } from '@element-plus/icons-vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const themeStore = useThemeStore()
const workspaceStore = useWorkspaceStore()
const sidebarCollapsed = ref(localStorage.getItem('mc-sidebar-collapsed') === 'true')
const footerPanelOpen = ref(false)

// Workspace 切换时通过 key 变化让 router-view 重新挂载，避免 hard reload 破坏运行状态
const workspaceRouteKey = computed(() => `ws-${workspaceStore.currentWorkspaceId ?? 'none'}`)
const showOnboarding = ref(false)
const showDoctor = ref(false)
const healthStatus = ref('unknown')

function onHealthStatus(status: string) {
  healthStatus.value = status
}

async function fetchHealthStatus() {
  try {
    const res: any = await http.get('/system/health')
    const data = res?.data || res
    healthStatus.value = data?.overall || 'healthy'
  } catch {
    healthStatus.value = 'unknown'
  }
}

// Sidebar attention signals — admin-only. Both `/agents` (stuck agents in the
// Live view) and `/security` (pending approvals) read from a shared 15s poller
// so multiple consumers don't multiply HTTP traffic.
const isAdminRole = computed(() => (localStorage.getItem('role') || 'user') === 'admin')
const { stuckAgents, pendingApprovals } = useNotificationCenter()
const liveAlertActive = computed(() => isAdminRole.value && stuckAgents.value > 0)

// 移动端状态
const mobileMenuOpen = ref(false)
const userExplicitCollapse = ref(localStorage.getItem('mc-sidebar-collapsed') === 'true')

const isMobile = useIsMobile()
// 中等屏幕自动折叠（≤1024px）
const compactViewport = useMediaQuery('(max-width: 1024px)')

// Mobile breakpoint side effects: close the drawer / footer panel when the
// layout flips between mobile and desktop.
watch(isMobile, (mobile) => {
  if (!mobile) mobileMenuOpen.value = false
  if (mobile) footerPanelOpen.value = false
})

// Auto-collapse the sidebar on narrow desktop unless the user set it explicitly.
watch(compactViewport, (compact) => {
  if (!userExplicitCollapse.value) sidebarCollapsed.value = compact
}, { immediate: true })

const shortcutsHintText = computed(() =>
  `Ctrl+K ${t('nav.shortcutAgents')} | Ctrl+N ${t('nav.shortcutNew')}`,
)

function openAgentsMenu() {
  if (!workspaceStore.can('manage:agents' as never)) return
  if (route.path !== '/agents') router.push('/agents')
}

function fireNewChatShortcut() {
  if (route.path === '/chat') {
    window.dispatchEvent(new CustomEvent('mc:chat-shortcut', { detail: 'newChat' }))
  } else {
    router.push({ path: '/chat', query: { action: 'newChat' } })
  }
}

function isEditableTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  if (el.isContentEditable) return true
  const tag = el.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  return false
}

function onGlobalKeydown(e: KeyboardEvent) {
  const mod = e.metaKey || e.ctrlKey
  if (!mod || e.altKey) return
  const key = e.key.toLowerCase()
  if (key !== 'k' && key !== 'n') return
  // Ctrl+N within an editable field should keep its native behavior; Ctrl+K
  // is rarely used by browsers (Firefox uses it for search-bar focus), but we
  // still want to let the chat input handle native paste / undo unblocked.
  if (key === 'n' && isEditableTarget(e.target)) return
  e.preventDefault()
  if (key === 'k') {
    openAgentsMenu()
  } else {
    fireNewChatShortcut()
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKeydown)

  // Check onboarding status
  if (!localStorage.getItem('mc-onboarding-done')) {
    try {
      const res: any = await setupApi.onboardingStatus()
      if (res?.data && !res.data.hasDefaultModel) {
        showOnboarding.value = true
      }
    } catch {
      // If endpoint doesn't exist yet, skip onboarding
    }
  }

  // Fetch initial health status for sidebar indicator
  fetchHealthStatus()
  // Sidebar attention counts (live / security) are driven by
  // useNotificationCenter — it polls when admins are mounted.
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
})

function onNavClick() {
  if (isMobile.value) mobileMenuOpen.value = false
}

const username = computed(() => localStorage.getItem('username') || 'User')
const role = computed(() => localStorage.getItem('role') || 'user')
const userInitial = computed(() => username.value.charAt(0).toUpperCase())
const roleLabel = computed(() => role.value === 'admin' ? t('nav.roleAdmin') : t('nav.roleUser'))
const effectiveCollapsed = computed(() => sidebarCollapsed.value && !isMobile.value)
const sidebarToggleLabel = computed(() => sidebarCollapsed.value ? t('common.expandSidebar') : t('common.collapseSidebar'))
const currentLocaleValue = computed(() => currentLocale.value)

const themeOptions = computed<{ value: ThemeMode; label: string; icon: string }[]>(() => [
  {
    value: 'light',
    label: t('nav.themeLight'),
    icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>',
  },
  {
    value: 'dark',
    label: t('nav.themeDark'),
    icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>',
  },
  {
    value: 'system',
    label: t('nav.themeSystem'),
    icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>',
  },
])

const localeOptions = computed<{ value: AppLocale; label: string; short: string }[]>(() => [
  { value: 'zh-CN', label: t('settings.languageOptions.zhCN'), short: '中' },
  { value: 'en-US', label: t('settings.languageOptions.enUS'), short: 'EN' },
])

// Capability-gated nav. Each item declares a capability or globalAdmin flag;
// useWorkspaceStore.can() decides visibility from the backend access set so
// the sidebar can't drift from the route guard or controller annotations.
type NavItem = {
  path: string
  label: string
  icon: string
  tooltip?: string
  requiredCapability?:
    | 'chat'
    | 'view:wiki'
    | 'view:memory'
    | 'view:dashboard'
    | 'manage:wiki'
    | 'manage:agents'
    | 'manage:skills'
    | 'manage:channels'
    | 'manage:models'
    | 'manage:security'
    | 'manage:settings'
  globalAdmin?: boolean
}

function filterNav(items: NavItem[]): NavItem[] {
  // Default deny while access is still loading — render an empty group rather
  // than flashing the full menu before refreshAccess() returns.
  if (!workspaceStore.accessLoaded) return []
  return items.filter((item) => {
    if (item.globalAdmin) return workspaceStore.isGlobalAdmin
    if (item.requiredCapability && !workspaceStore.can(item.requiredCapability as never)) return false
    return true
  })
}

const navGroups = computed(() => [
  {
    key: 'core',
    label: t('nav.core'),
    items: filterNav([
      {
        path: '/dashboard',
        label: t('nav.dashboard', 'Dashboard'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>`,
        requiredCapability: 'view:dashboard',
      },
      {
        path: '/chat',
        label: t('nav.chat'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>`,
        requiredCapability: 'chat',
      },
      {
        path: '/agents',
        label: t('nav.agents'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="4"/><path d="M20 21a8 8 0 1 0-16 0"/></svg>`,
        requiredCapability: 'manage:agents',
      },
      {
        path: '/wiki',
        label: t('nav.wiki'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/><line x1="8" y1="7" x2="16" y2="7"/><line x1="8" y1="11" x2="14" y2="11"/></svg>`,
        requiredCapability: 'view:wiki',
      },
      {
        path: '/memory',
        label: t('nav.memory'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a4 4 0 0 1 4 4v2a4 4 0 0 1-8 0V6a4 4 0 0 1 4-4z"/><path d="M16 14H8a4 4 0 0 0-4 4v2h16v-2a4 4 0 0 0-4-4z"/><line x1="12" y1="11" x2="12" y2="14"/></svg>`,
        requiredCapability: 'view:memory',
      },
      {
        path: '/enterprise',
        label: t('nav.enterprise'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 21h18"/><path d="M5 21V7l7-4 7 4v14"/><path d="M9 9h.01"/><path d="M9 12h.01"/><path d="M9 15h.01"/><path d="M9 18h.01"/><path d="M15 9h.01"/><path d="M15 12h.01"/><path d="M15 15h.01"/><path d="M15 18h.01"/></svg>`,
        requiredCapability: 'manage:agents',
      },
    ] as NavItem[]),
  },
  {
    key: 'connect',
    label: t('nav.connect'),
    items: filterNav([
      {
        path: '/channels',
        label: t('nav.channels'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07A19.5 19.5 0 0 1 4.69 12a19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 3.6 1.18h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81a2 2 0 0 1-.45 2.11L7.91 8.73a16 16 0 0 0 6.29 6.29l1.62-1.62a2 2 0 0 1 2.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0 1 22 16.92z"/></svg>`,
        requiredCapability: 'manage:channels',
      },
      {
        path: '/skills',
        label: t('nav.skills'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>`,
        requiredCapability: 'manage:skills',
      },
      {
        path: '/plugins',
        label: t('nav.plugins'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="7" width="20" height="14" rx="2" ry="2"/><path d="M16 3h-8v4h8V3z"/></svg>`,
        requiredCapability: 'manage:settings',
      },
      // RFC-090 Phase 4: Activity 提升到顶层
      {
        path: '/activity',
        label: t('nav.activity'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>`,
        requiredCapability: 'manage:security',
      },
    ] as NavItem[]),
  },
  {
    key: 'system',
    label: t('nav.system'),
    items: filterNav([
      {
        path: '/settings/models',
        label: t('nav.settings'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14M4.93 4.93a10 10 0 0 0 0 14.14"/></svg>`,
        requiredCapability: 'manage:models',
      },
      {
        path: '/security',
        label: t('nav.security'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
        requiredCapability: 'manage:security',
      },
    ] as NavItem[]),
  },
].filter((group) => group.items.length > 0))

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  userExplicitCollapse.value = sidebarCollapsed.value
  localStorage.setItem('mc-sidebar-collapsed', String(sidebarCollapsed.value))
  if (!sidebarCollapsed.value) {
    footerPanelOpen.value = false
  }
}

function isNavItemActive(item: { path: string; label: string }) {
  if (item.path.startsWith('/settings')) {
    return route.path.startsWith('/settings')
  }
  if (item.path === '/security') {
    return route.path.startsWith('/security')
  }
  return route.path === item.path
}

const showChangePassword = ref(false)

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  router.push('/login')
}

async function changeLocale(locale: AppLocale) {
  await applyLocale(locale)
  footerPanelOpen.value = false
  try {
    await settingsApi.update({ language: locale })
  } catch {
    // keep local preference even if backend persistence fails
  }
}

watch(() => route.fullPath, () => {
  footerPanelOpen.value = false
  if (isMobile.value) mobileMenuOpen.value = false
})

watch(() => sidebarCollapsed.value, (collapsed) => {
  if (!collapsed) footerPanelOpen.value = false
})

watch(() => workspaceStore.currentWorkspaceId, () => {
  footerPanelOpen.value = false
})
</script>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  background: var(--mc-bg);
  overflow: hidden;
  position: relative;
}

.app-layout::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at top left, rgba(217, 109, 70, 0.12), transparent 22%),
    radial-gradient(circle at bottom right, rgba(24, 74, 69, 0.08), transparent 18%);
  pointer-events: none;
}

:global(html.dark) .app-layout::before {
  background:
    radial-gradient(circle at top left, rgba(235, 143, 101, 0.14), transparent 24%),
    radial-gradient(circle at bottom right, rgba(92, 166, 157, 0.08), transparent 20%);
}

/* ===== 侧边栏 ===== */
.sidebar {
  width: 236px;
  min-width: 236px;
  margin: 14px 0 14px 14px;
  background:
    linear-gradient(180deg, var(--mc-panel-top), var(--mc-panel-bottom));
  border: 1px solid var(--mc-sidebar-border);
  border-radius: 28px;
  box-shadow: var(--mc-shadow-soft);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease, min-width 0.2s ease;
  overflow: hidden;
  position: relative;
  z-index: 1;
}

.sidebar.collapsed {
  width: 74px;
  min-width: 74px;
}

.sidebar::before {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--mc-glow);
  pointer-events: none;
}

.sidebar-logo {
  display: flex;
  align-items: center;
  padding: 14px 14px 12px;
  border-bottom: 1px solid var(--mc-border-light);
  gap: 12px;
  min-height: 64px;
}

.sidebar.collapsed .sidebar-logo {
  flex-direction: column;
  justify-content: center;
  padding: 12px 10px;
  gap: 6px;
  min-height: 92px;
}

.logo-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
  background: linear-gradient(135deg, rgba(217, 109, 70, 0.18), rgba(24, 74, 69, 0.08));
  border: 1px solid rgba(217, 109, 70, 0.14);
}

.logo-img {
  width: 34px;
  height: 34px;
  object-fit: contain;
  filter: drop-shadow(0 8px 18px rgba(217, 109, 70, 0.22));
}

.logo-emoji { font-size: 16px; }

.logo-text { flex: 1; overflow: hidden; }

.logo-name {
  display: block;
  font-size: 16px;
  font-weight: 800;
  color: var(--mc-sidebar-logo-name);
  white-space: nowrap;
  letter-spacing: -0.03em;
}

.logo-name-highlight {
  color: var(--mc-primary);
}

.logo-version {
  display: block;
  font-size: 10px;
  color: var(--mc-text-tertiary);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.collapse-btn {
  width: 28px;
  height: 28px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-muted);
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  flex-shrink: 0;
  padding: 0;
  margin-left: auto;
}

.collapse-btn:hover {
  background: var(--mc-sidebar-hover);
  color: var(--mc-text-primary);
}

.sidebar.collapsed .collapse-btn {
  width: 32px;
  height: 32px;
  margin-left: 0;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  color: var(--mc-sidebar-text-active);
}

.sidebar.collapsed .collapse-btn:hover {
  background: var(--mc-sidebar-hover);
  border-color: var(--mc-border);
}

/* 导航 */
.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0 4px;
  scrollbar-width: none;
}

.sidebar-nav::-webkit-scrollbar { width: 4px; }
.sidebar-nav::-webkit-scrollbar-thumb { background: var(--mc-border); border-radius: 2px; }
.sidebar-nav::-webkit-scrollbar { display: none; }

.nav-group { margin-bottom: 2px; }

.nav-group-title {
  padding: 8px 18px 4px;
  font-size: 10px;
  font-weight: 600;
  color: var(--mc-sidebar-group-title);
  text-transform: uppercase;
  letter-spacing: 0.1em;
  white-space: nowrap;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  color: var(--mc-sidebar-text);
  text-decoration: none;
  font-size: 13px;
  border-radius: 13px;
  margin: 2px 10px;
  transition: all 0.15s ease;
  white-space: nowrap;
  overflow: hidden;
  position: relative;
}

.nav-item:hover {
  background: var(--mc-sidebar-hover);
  color: var(--mc-text-primary);
}

.nav-item.active {
  background: var(--mc-sidebar-active);
  color: var(--mc-sidebar-text-active);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px rgba(217, 109, 70, 0.08);
}

/* Active indicator bar removed — active state uses bg color + font weight only */

/* Collapsed rail: the label is hidden, so center the lone icon on the
   sidebar's vertical axis instead of leaving it left-aligned by the
   nav-item's horizontal padding. */
.sidebar.collapsed .nav-item {
  justify-content: center;
  gap: 0;
}

.nav-icon { display: flex; align-items: center; flex-shrink: 0; }
.nav-label { overflow: hidden; text-overflow: ellipsis; }

/* 底部 */
.sidebar-footer {
  border-top: 1px solid var(--mc-border-light);
  padding: 10px 12px 12px;
  background: var(--mc-sidebar-footer-bg);
  backdrop-filter: blur(14px);
  position: relative;
}
.health-indicator { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px 10px; border: 1px solid var(--mc-border-light); background: var(--mc-bg-muted); border-radius: 12px; cursor: pointer; color: var(--mc-text-secondary); font-size: 12px; margin-bottom: 8px; }
.health-indicator:hover { background: var(--mc-bg-sunken); }
.health-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.health-indicator.healthy .health-dot { background: var(--mc-success); }
.health-indicator.warning .health-dot { background: var(--mc-primary); }
.health-indicator.error .health-dot { background: var(--mc-danger); }
.health-indicator.unknown .health-dot { background: var(--mc-text-tertiary); }

.sidebar-utility-card {
  margin-bottom: 8px;
  padding: 8px 10px;
  border-radius: 16px;
  border: 1px solid var(--mc-border-light);
  background: color-mix(in srgb, var(--mc-sidebar-footer-bg) 74%, transparent);
}

.compact-utility-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.compact-utility-row + .compact-utility-row {
  margin-top: 6px;
}

.compact-utility-title {
  font-size: 10px;
  font-weight: 700;
  color: var(--mc-text-secondary);
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.utility-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.12em; color: var(--mc-text-tertiary); margin: 0 0 8px; padding-left: 2px; }

.shortcuts-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 4px 6px;
  margin-top: 8px;
  padding: 4px 6px;
  font-size: 10px;
  color: var(--mc-text-tertiary);
  letter-spacing: 0.02em;
  user-select: none;
}
.shortcuts-hint kbd {
  display: inline-flex;
  align-items: center;
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  font-family: inherit;
  font-size: 9.5px;
  font-weight: 600;
  line-height: 1.4;
}
.shortcuts-hint__sep {
  opacity: 0.45;
}

/* 主题切换 */
.theme-toggle-row {
  display: flex;
  gap: 2px;
  background: var(--mc-bg-muted);
  border-radius: 14px;
  padding: 4px;
  margin-bottom: 12px;
  border: 1px solid var(--mc-border-light);
}

.theme-toggle-row--compact {
  margin-bottom: 0;
  padding: 2px;
  gap: 3px;
  border-radius: 999px;
}

.language-toggle-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}

.language-toggle-row--compact {
  display: flex;
  gap: 6px;
}

.theme-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 5px 4px;
  border: none;
  background: transparent;
  color: var(--mc-text-tertiary);
  border-radius: 6px;
  cursor: pointer;
  font-size: 11px;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.theme-btn--compact {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 999px;
  flex: 0 0 auto;
}

.theme-btn:hover {
  color: var(--mc-text-secondary);
}

.theme-btn.active {
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  box-shadow: var(--mc-shadow-soft);
}

.theme-btn-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.language-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-start;
  width: 100%;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
  font-size: 12px;
  font-weight: 600;
}

.language-btn:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}

.language-btn.active {
  border-color: rgba(217, 109, 70, 0.18);
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}

.language-abbr {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--mc-panel-raised);
  color: inherit;
  font-size: 11px;
  font-weight: 800;
  flex-shrink: 0;
}

.language-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.language-btn--compact {
  width: 34px;
  min-width: 34px;
  justify-content: center;
  padding: 4px 0;
  border-radius: 999px;
}

.language-btn--compact .language-abbr {
  width: 20px;
  height: 20px;
  font-size: 10px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 9px;
  border-radius: 14px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
}

.user-avatar {
  width: 30px;
  height: 30px;
  background: linear-gradient(135deg, var(--mc-primary), var(--mc-accent));
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.user-detail { flex: 1; overflow: hidden; }

.user-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-role { font-size: 10px; color: var(--mc-text-tertiary); }

.change-password-btn,
.logout-btn {
  width: 26px;
  height: 26px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  padding: 0;
  flex-shrink: 0;
}

.change-password-btn:hover {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}

.logout-btn:hover {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}

.collapsed-footer-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
}

.footer-icon-btn {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.footer-icon-btn:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}

.footer-icon-btn.healthy .health-dot { background: var(--mc-success); }
.footer-icon-btn.warning .health-dot { background: var(--mc-primary); }
.footer-icon-btn.error .health-dot { background: var(--mc-danger); }
.footer-icon-btn.unknown .health-dot { background: var(--mc-text-tertiary); }

.footer-icon-btn--accent {
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
  border-color: rgba(217, 109, 70, 0.18);
}

.sidebar-utility-panel {
  position: absolute;
  left: calc(100% + 14px);
  bottom: 16px;
  width: 236px;
  padding: 14px;
  border-radius: 22px;
  background: var(--mc-sidebar-floating-bg);
  border: 1px solid var(--mc-border);
  box-shadow: var(--mc-shadow-medium);
  display: flex;
  flex-direction: column;
  gap: 14px;
  backdrop-filter: blur(18px);
}

.panel-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.panel-option-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.panel-option-btn {
  width: 100%;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition: all 0.15s ease;
}

.panel-option-btn:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}

.panel-option-btn.active {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  border-color: rgba(217, 109, 70, 0.18);
}

.panel-option-icon {
  width: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.panel-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 16px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
}

.panel-user-meta {
  min-width: 0;
  flex: 1;
}

.logout-btn--panel {
  flex-shrink: 0;
}

/* ===== 主内容区 ===== */
.main-content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-width: 0;
  position: relative;
  z-index: 1;
  padding: 14px 14px 14px 18px;
}

/* ===== 移动端元素（桌面端隐藏） ===== */
.sidebar-backdrop {
  display: none;
}

.mobile-topbar {
  display: none;
}

/* 动画 */
.fade-enter-active,
.fade-leave-active { transition: opacity 0.15s ease; }
.fade-enter-from,
.fade-leave-to { opacity: 0; }

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 1000;
    width: 260px;
    min-width: 260px;
    margin: 10px;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: var(--mc-shadow-medium);
  }

  .sidebar.mobile-open {
    transform: translateX(0);
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.15);
  }

  .sidebar.collapsed {
    width: 260px;
    min-width: 260px;
  }

  .collapse-btn {
    display: none;
  }

  .sidebar-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 999;
    background: rgba(0, 0, 0, 0.3);
  }

  .mobile-topbar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 0 12px;
    padding: 12px 14px;
    background: var(--mc-surface-overlay);
    border: 1px solid var(--mc-border);
    border-radius: 18px;
    box-shadow: var(--mc-shadow-soft);
    flex-shrink: 0;
  }

  .mobile-topbar-title {
    font-size: 16px;
    font-weight: 700;
    color: var(--mc-text-primary);
  }

  .mobile-menu-btn {
    width: 36px;
    height: 36px;
    border: 1px solid var(--mc-border);
    background: var(--mc-bg-elevated);
    border-radius: 8px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--mc-text-primary);
    flex-shrink: 0;
  }

  .mobile-menu-btn:hover {
    background: var(--mc-bg-sunken);
  }

  .sidebar-utility-panel {
    display: none;
  }

  .sidebar-utility-card {
    padding: 10px;
  }

  .compact-utility-row {
    flex-direction: column;
    align-items: stretch;
  }

  .theme-toggle-row--compact,
  .language-toggle-row--compact {
    width: 100%;
    justify-content: stretch;
  }

  .theme-btn--compact,
  .language-btn--compact {
    flex: 1;
    width: auto;
  }

  .sidebar-footer {
    background: transparent;
    backdrop-filter: none;
  }
}

@media (max-width: 480px) {
  .mobile-topbar {
    padding: 8px 10px;
    margin: 0 0 8px;
    border-radius: 12px;
    gap: 8px;
  }

  .mobile-topbar-title {
    font-size: 14px;
  }

  .mobile-menu-btn {
    width: 32px;
    height: 32px;
  }

  .main-content {
    padding: 8px;
  }
}
</style>
