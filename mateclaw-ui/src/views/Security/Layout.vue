<template>
  <div class="mc-page-shell security-shell">
    <div class="mc-page-frame security-frame">
      <div class="mc-page-inner security-layout">
        <div class="settings-nav mc-surface-card" :class="{ 'nav-collapsed': navCollapsed }">
          <div v-if="!navCollapsed" class="settings-nav__intro">
            <div class="mc-page-kicker">{{ t('security.kicker') }}</div>
            <h2 class="nav-title">{{ t('security.title') }}</h2>
          </div>
          <template v-for="section in sections" :key="section.id">
            <el-tooltip
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
          <!-- 折叠切换按钮 -->
          <button class="nav-collapse-btn" @click="toggleNav" :title="navCollapsed ? t('common.expandSidebar') : t('common.collapseSidebar')">
            <svg v-if="!navCollapsed" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
            <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
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
import { useI18n } from 'vue-i18n'
import { useMediaQuery, BREAKPOINTS } from '@/composables/useBreakpoint'

const route = useRoute()
const { t } = useI18n()

// 折叠状态（与 Settings 共享 key）
const navCollapsed = ref(localStorage.getItem('mc-settings-nav-collapsed') === 'true')
const userExplicit = ref(localStorage.getItem('mc-settings-nav-collapsed') === 'true')

function toggleNav() {
  navCollapsed.value = !navCollapsed.value
  userExplicit.value = navCollapsed.value
  localStorage.setItem('mc-settings-nav-collapsed', String(navCollapsed.value))
}

// Auto-collapse the nav on narrow desktop unless the user collapsed it explicitly.
const compactViewport = useMediaQuery(BREAKPOINTS.compact)
watch(compactViewport, (compact) => {
  if (!userExplicit.value) navCollapsed.value = compact
}, { immediate: true })

const sections = computed(() => [
  {
    id: 'toolGuard',
    path: '/security/tool-guard',
    label: t('security.sections.toolGuard'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>',
  },
  {
    id: 'fileGuard',
    path: '/security/file-guard',
    label: t('security.sections.fileGuard'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="9" y1="15" x2="15" y2="15"/></svg>',
  },
  {
    id: 'auditLogs',
    path: '/security/audit-logs',
    label: t('security.sections.auditLogs'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>',
  },
])

function isActive(path: string) {
  return route.path === path
}
</script>

<style scoped>
.security-shell {
  background: transparent;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.security-frame {
  height: min(calc(100vh - 28px), 100%);
  min-height: 0;
  overflow: hidden;
}

.security-layout {
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

.nav-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--mc-text-primary);
  letter-spacing: -0.03em;
  margin: 0 0 4px;
}

.nav-desc {
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  background: transparent;
  color: var(--mc-text-secondary);
  font-size: 13px;
  border-radius: 10px;
  cursor: pointer;
  text-align: left;
  text-decoration: none;
  margin-bottom: 2px;
  font-weight: 500;
  transition: all 0.15s;
}

.nav-item:hover { background: var(--mc-bg-muted); color: var(--mc-text-primary); }
.nav-item.active { background: var(--mc-primary-bg); color: var(--mc-primary); font-weight: 600; box-shadow: inset 0 0 0 1px rgba(217, 109, 70, 0.08); }
.nav-icon { display: flex; align-items: center; flex-shrink: 0; }

.nav-collapsed .nav-item {
  justify-content: center;
  padding: 10px 8px;
}

.nav-collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 32px;
  margin-top: auto;
  border: none;
  border-top: 1px solid var(--mc-border-light);
  background: transparent;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
}

.nav-collapse-btn:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
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
  .security-frame {
    height: auto;
    min-height: calc(100vh - 28px);
    overflow: visible;
  }

  .security-layout {
    flex-direction: row;
    height: auto;
  }

  .settings-nav {
    width: 56px;
    min-width: 56px;
    align-self: auto;
    padding: 12px 8px;
  }

  .settings-nav .settings-nav__intro { display: none; }
  .settings-nav .nav-item { justify-content: center; padding: 10px 8px; }
  .settings-nav .nav-label { display: none; }
  .nav-collapse-btn { display: none; }

  .settings-content {
    overflow: visible;
  }

  .settings-content__inner {
    overflow: visible;
    padding-right: 0;
  }
}
</style>
