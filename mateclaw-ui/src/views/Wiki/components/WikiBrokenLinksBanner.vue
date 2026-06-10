<template>
  <!--
    Workspace-level banner that surfaces broken-link state without forcing the
    user into a separate tab. Three modes:
      * never scanned → compact "scan now" prompt with a button
      * scan running  → loading indicator + status text
      * have report   → count + last-scan timestamp + "view" + "rescan"
    The detail panel (per-page breakdown, "open source page" actions) lives
    in WikiBrokenLinksPanel.vue, mounted by the parent on demand.
  -->
  <div
    v-if="!isHidden"
    class="lint-banner"
    :class="{
      'lint-banner--clean': report && report.totalBrokenRefs === 0,
      'lint-banner--has-broken': report && report.totalBrokenRefs > 0,
      'lint-banner--running': loading,
      'lint-banner--empty': !report && !loading,
    }"
  >
    <span class="lint-icon" aria-hidden="true">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
        <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.72"/>
        <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.72-1.72"/>
      </svg>
    </span>

    <span v-if="loading" class="lint-text">
      {{ t('wiki.lint.running') }}
    </span>
    <span v-else-if="!report" class="lint-text">
      {{ t('wiki.lint.neverScanned') }}
    </span>
    <span v-else-if="report.totalBrokenRefs === 0" class="lint-text">
      {{ t('wiki.lint.cleanResult', { pages: report.totalPages }) }}
      <span class="lint-timestamp">{{ formattedTimestamp }}</span>
    </span>
    <span v-else class="lint-text">
      <i18n-t keypath="wiki.lint.brokenSummary" tag="span">
        <template #refs><strong>{{ report.totalBrokenRefs }}</strong></template>
        <template #pages><strong>{{ report.pagesWithBrokenLinks }}</strong></template>
      </i18n-t>
      <span class="lint-timestamp">{{ formattedTimestamp }}</span>
    </span>

    <div class="lint-actions">
      <button
        v-if="report && report.totalBrokenRefs > 0"
        class="lint-btn lint-btn--primary"
        @click="$emit('view')"
      >{{ t('wiki.lint.view') }}</button>
      <button
        class="lint-btn"
        :disabled="loading"
        @click="onScan"
      >{{ loading ? t('wiki.lint.scanning') : (report ? t('wiki.lint.rescan') : t('wiki.lint.scan')) }}</button>
      <button class="lint-btn lint-btn--ghost" :title="t('wiki.lint.dismissTitle')" @click="dismissed = true">×</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore } from '@/stores/useWikiStore'

defineEmits<{ (e: 'view'): void }>()

const { t, locale } = useI18n()
const store = useWikiStore()

// Per-session dismiss — user can hide the banner without affecting scan state.
// Reset implicitly on KB switch (banner is re-mounted when currentKB changes
// because the parent passes :key="kb.id"; if it doesn't, dismissals persist
// across KB switches which is acceptable for v1).
const dismissed = ref(false)

const report = computed(() => store.brokenLinksReport)
const loading = computed(() => store.brokenLinksLoading)
const isHidden = computed(() => dismissed.value)

const formattedTimestamp = computed(() => {
  const ts = report.value?.completedAt
  if (!ts) return ''
  try {
    const d = new Date(ts)
    return ' · ' + d.toLocaleString(locale.value)
  } catch {
    return ' · ' + ts
  }
})

async function onScan() {
  if (!store.currentKB) return
  try {
    await store.startBrokenLinksScan(Number(store.currentKB.id))
  } catch (e: any) {
    console.error('[Wiki] scan failed', e)
  }
}
</script>

<style scoped>
.lint-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-radius: 10px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  font-size: 13px;
  color: var(--mc-text-secondary);
  margin-bottom: 12px;
}
.lint-banner--has-broken {
  border-color: var(--el-color-warning-light-5, #f0c78a);
  background: var(--el-color-warning-light-9, #fdf6ec);
  color: var(--el-color-warning-dark-2, #b88230);
}
.lint-banner--clean {
  border-color: var(--el-color-success-light-5, #b3e19d);
  background: var(--el-color-success-light-9, #f0f9eb);
  color: var(--el-color-success-dark-2, #529b2e);
}
.lint-banner--running {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg, #fff5f0);
  color: var(--mc-primary);
}
.lint-icon { display: inline-flex; align-items: center; }
.lint-text { flex: 1; min-width: 0; }
.lint-timestamp { color: var(--mc-text-tertiary); font-size: 12px; }
.lint-actions { display: inline-flex; align-items: center; gap: 6px; }
.lint-btn {
  padding: 5px 11px;
  border-radius: 8px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  color: inherit;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
}
.lint-btn:hover:not(:disabled) { border-color: var(--mc-primary); color: var(--mc-primary); }
.lint-btn:disabled { cursor: not-allowed; opacity: 0.6; }
.lint-btn--primary {
  background: var(--mc-primary);
  border-color: var(--mc-primary);
  color: white;
}
.lint-btn--primary:hover { background: var(--mc-primary-hover); color: white; }
.lint-btn--ghost {
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  background: transparent;
  font-size: 16px;
  line-height: 1;
  color: var(--mc-text-tertiary);
}
.lint-btn--ghost:hover { color: var(--mc-text-primary); background: transparent; }
</style>
