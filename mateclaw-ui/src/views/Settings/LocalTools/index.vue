<template>
  <div class="settings-section">
    <div class="section-header">
      <h2 class="section-title">{{ t('settings.localToolsTitle') }}</h2>
      <p class="section-desc">{{ t('settings.localToolsDesc') }}</p>
    </div>

    <!-- Shown when the SPA is opened in a plain browser: the bridge only
         exists inside the desktop shell, so there is nothing to manage. -->
    <div v-if="!isDesktop" class="settings-card">
      <div class="empty-note">{{ t('settings.localTools.desktopOnly') }}</div>
    </div>

    <template v-else>
      <div class="settings-card">
        <div class="setting-item">
          <div class="setting-info">
            <div class="setting-label">{{ t('settings.localTools.enableLabel') }}</div>
            <div class="setting-hint">{{ t('settings.localTools.enableHint') }}</div>
          </div>
          <div class="setting-control">
            <label class="toggle-switch">
              <input type="checkbox" :checked="state.enabled" :disabled="busy" @change="onToggleEnabled" />
              <span class="toggle-slider"></span>
            </label>
          </div>
        </div>

        <div class="setting-item">
          <div class="setting-info">
            <div class="setting-label">{{ t('settings.localTools.tunnelLabel') }}</div>
            <div class="setting-hint">{{ t('settings.localTools.tunnelHint') }}</div>
          </div>
          <div class="setting-control">
            <span class="status-chip" :class="{ ok: state.connected }">
              <span class="dot"></span>
              {{ state.connected ? t('settings.localTools.tunnelConnected') : t('settings.localTools.tunnelDisconnected') }}
            </span>
          </div>
        </div>
      </div>

      <div class="settings-card dirs-card">
        <div class="dirs-header">
          <div class="setting-info">
            <div class="setting-label">{{ t('settings.localTools.dirsLabel') }}</div>
            <div class="setting-hint">{{ t('settings.localTools.dirsHint') }}</div>
          </div>
          <button class="btn-primary" :disabled="busy" @click="onAddDir">
            {{ t('settings.localTools.addDir') }}
          </button>
        </div>

        <div v-if="state.allowedDirs.length === 0" class="empty-note">
          {{ state.failClosed ? t('settings.localTools.emptyFailClosed') : t('settings.localTools.emptyFailOpen') }}
        </div>

        <ul v-else class="dir-list">
          <li v-for="dir in state.allowedDirs" :key="dir" class="dir-row">
            <span class="dir-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
            </span>
            <span class="dir-path">{{ dir }}</span>
            <button class="btn-remove" :disabled="busy" @click="onRemoveDir(dir)">
              {{ t('common.delete') }}
            </button>
          </li>
        </ul>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcConfirm } from '@/components/common/useConfirm'
import { mcToast } from '@/composables/useMcToast'
import type { LocalToolsState } from '@/types/desktop'

const { t } = useI18n()

const isDesktop = typeof window !== 'undefined' && !!window.mateClawAPI

const busy = ref(false)
const state = reactive<LocalToolsState>({
  enabled: false,
  allowedDirs: [],
  failClosed: true,
  connected: false,
})

onMounted(() => {
  if (isDesktop) void refresh()
})

async function refresh() {
  try {
    const cfg = await window.mateClawAPI!.getLocalToolsConfig()
    state.enabled = cfg.enabled
    state.allowedDirs = cfg.allowedDirs ?? []
    state.failClosed = cfg.failClosed
    state.connected = cfg.connected
  } catch {
    mcToast.error(t('settings.localTools.loadFail'))
  }
}

async function onToggleEnabled(e: Event) {
  const enabled = (e.target as HTMLInputElement).checked
  busy.value = true
  try {
    await window.mateClawAPI!.setLocalToolsConfig({ enabled })
    await refresh()
  } catch {
    mcToast.error(t('settings.localTools.saveFail'))
    await refresh()
  } finally {
    busy.value = false
  }
}

async function onAddDir() {
  busy.value = true
  try {
    const result = await window.mateClawAPI!.addLocalToolsDir()
    await refresh()
    if (result.added) mcToast.success(t('settings.localTools.addSuccess'))
  } catch {
    mcToast.error(t('settings.localTools.saveFail'))
  } finally {
    busy.value = false
  }
}

async function onRemoveDir(dir: string) {
  const ok = await mcConfirm({
    title: t('settings.localTools.removeTitle'),
    message: t('settings.localTools.removeConfirm', { dir }),
    confirmText: t('common.delete'),
    tone: 'danger',
  })
  if (!ok) return
  busy.value = true
  try {
    await window.mateClawAPI!.removeLocalToolsDir(dir)
    await refresh()
    mcToast.success(t('settings.localTools.removeSuccess'))
  } catch {
    mcToast.error(t('settings.localTools.saveFail'))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.settings-section { width: 100%; }
.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.settings-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124,63,30,0.04); width: 100%; }
.settings-card + .settings-card { margin-top: 16px; }
.setting-item { display: flex; justify-content: space-between; gap: 20px; padding: 16px 0; border-bottom: 1px solid var(--mc-border-light); }
.setting-item:last-child { border-bottom: none; }
.setting-info { flex: 1; }
.setting-label { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 4px; }
.setting-hint { font-size: 13px; color: var(--mc-text-secondary); line-height: 1.6; }
.setting-control { display: flex; align-items: center; justify-content: flex-end; }

.status-chip { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; padding: 5px 12px; border-radius: 999px; background: var(--mc-bg-muted); color: var(--mc-text-secondary); }
.status-chip .dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.status-chip.ok { background: var(--mc-accent-soft); color: var(--mc-accent); }

.dirs-card .dirs-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; padding-bottom: 14px; }
.empty-note { padding: 14px 4px; font-size: 13px; color: var(--mc-text-secondary); line-height: 1.6; }

.dir-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.dir-row { display: flex; align-items: center; gap: 10px; padding: 11px 14px; border: 1px solid var(--mc-border-light); border-radius: 12px; background: var(--mc-bg-sunken); }
.dir-icon { display: inline-flex; color: var(--mc-accent); flex-shrink: 0; }
.dir-path { flex: 1; font-family: var(--mc-font-mono); font-size: 13px; color: var(--mc-text-primary); word-break: break-all; }

.btn-primary { border: none; border-radius: 10px; padding: 9px 16px; font-size: 14px; cursor: pointer; transition: all 0.15s; background: var(--mc-primary); color: white; white-space: nowrap; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-remove { border: 1px solid var(--mc-border); border-radius: 8px; padding: 5px 12px; font-size: 13px; cursor: pointer; transition: all 0.15s; background: var(--mc-bg-elevated); color: var(--mc-danger); flex-shrink: 0; }
.btn-remove:hover { border-color: var(--mc-danger); background: var(--mc-danger); color: white; }
.btn-remove:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
