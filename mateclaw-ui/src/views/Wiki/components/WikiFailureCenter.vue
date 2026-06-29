<template>
  <!--
    Centralized, cross-KB view of materials needing attention. Renders only for
    admins (the endpoint spans every workspace) and only when there is at least
    one item, so it stays out of the way on a healthy install.
  -->
  <section v-if="items.length > 0" class="failure-center mc-surface-card">
    <button class="fc-header" @click="collapsed = !collapsed">
      <span class="fc-title">
        <span class="fc-dot"></span>
        {{ t('wiki.failureCenter.title') }}
        <span class="fc-count">{{ items.length }}</span>
      </span>
      <svg class="fc-chevron" :class="{ open: !collapsed }" width="16" height="16" viewBox="0 0 24 24"
           fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
    </button>

    <div v-show="!collapsed" class="fc-body">
      <div v-for="it in items" :key="it.rawId" class="fc-row" @click="$emit('open', it.kbId)">
        <span class="fc-badge" :class="rowKind(it)">{{ t(`wiki.status.${it.processingStatus}`) }}</span>
        <div class="fc-main">
          <div class="fc-line1">
            <span class="fc-raw-title">{{ it.title }}</span>
            <span class="fc-kb">· {{ it.kbName }}</span>
          </div>
          <div class="fc-msg" :title="it.errorMessage || it.warningMessage || ''">{{ friendly(it) }}</div>
        </div>
        <button class="fc-open" @click.stop="$emit('open', it.kbId)">{{ t('wiki.failureCenter.open') }}</button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { wikiApi, type WikiFailureItem } from '@/api/index'

defineEmits<{ (e: 'open', kbId: string): void }>()

const { t } = useI18n()
const items = ref<WikiFailureItem[]>([])
const collapsed = ref(false)

// Whether a row is a hard failure vs a non-blocking warning, for badge tone.
function rowKind(it: WikiFailureItem): string {
  if (it.processingStatus === 'failed') return 'failed'
  if (it.processingStatus === 'partial') return 'partial'
  return 'warning'
}

// Localized hint, keyed by the structured code; falls back to the raw text.
function friendly(it: WikiFailureItem): string {
  if (it.errorCode) {
    const key = `wiki.errorCode.${it.errorCode}`
    const msg = t(key)
    if (msg !== key) return msg
  }
  if (it.warningCode) {
    const key = `wiki.warningCode.${it.warningCode}`
    const msg = t(key)
    if (msg !== key) return msg
  }
  return it.errorMessage || it.warningMessage || t('wiki.errorCode.UNKNOWN')
}

onMounted(async () => {
  try {
    const res: any = await wikiApi.listFailures(100)
    items.value = res.data || res || []
  } catch { /* admin-only endpoint; ignore for non-admins */ }
})
</script>

<style scoped>
.failure-center { margin-bottom: 16px; padding: 0; overflow: hidden; }
.fc-header { width: 100%; display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; background: transparent; border: none; cursor: pointer; color: var(--mc-text-primary); }
.fc-title { display: inline-flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; }
.fc-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--mc-warning, #d98e00); }
.fc-count { font-size: 11px; font-weight: 700; color: var(--mc-warning, #d98e00); background: color-mix(in srgb, var(--mc-warning, #d98e00) 14%, transparent); border-radius: 999px; padding: 1px 8px; }
.fc-chevron { transition: transform 0.15s; color: var(--mc-text-secondary); }
.fc-chevron.open { transform: rotate(180deg); }
.fc-body { border-top: 1px solid var(--mc-border); }
.fc-row { display: flex; align-items: center; gap: 12px; padding: 10px 16px; border-bottom: 1px solid var(--mc-border); cursor: pointer; transition: background 0.12s; }
.fc-row:last-child { border-bottom: none; }
.fc-row:hover { background: var(--mc-bg-sunken); }
.fc-badge { flex-shrink: 0; font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.03em; padding: 2px 7px; border-radius: 6px; }
.fc-badge.failed { color: var(--mc-danger); background: color-mix(in srgb, var(--mc-danger) 12%, transparent); }
.fc-badge.partial, .fc-badge.warning { color: var(--mc-warning, #d98e00); background: color-mix(in srgb, var(--mc-warning, #d98e00) 14%, transparent); }
.fc-main { flex: 1; min-width: 0; }
.fc-line1 { display: flex; align-items: baseline; gap: 6px; }
.fc-raw-title { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fc-kb { font-size: 11px; color: var(--mc-text-secondary); flex-shrink: 0; }
.fc-msg { font-size: 11px; color: var(--mc-text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fc-open { flex-shrink: 0; font-size: 12px; padding: 4px 12px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); cursor: pointer; }
.fc-open:hover { background: var(--mc-bg-sunken); }
</style>
