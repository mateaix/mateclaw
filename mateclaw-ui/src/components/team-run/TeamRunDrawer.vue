<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import type { TeamRun, TeamRunTask } from '@/api'
import TeamRunDetail from './TeamRunDetail.vue'
import TeamRunStatus from './TeamRunStatus.vue'
import type { TeamRunRoute } from './teamRunPresentation'

const props = withDefaults(defineProps<{
  run: TeamRun | null
  open?: boolean
  selectedTaskId?: string | null
  canCancel?: boolean
  detailLoading?: boolean
  detailError?: string | null
  managementActions?: boolean
  pendingActions?: string[]
}>(), { open: false, selectedTaskId: null, canCancel: false, detailLoading: false, detailError: null, managementActions: false, pendingActions: () => [] })

const emit = defineEmits<{
  close: []
  cancel: [runId: string]
  'select-task': [task: TeamRunTask]
  navigate: [route: TeamRunRoute]
  'retry-detail': []
  'view-task': [taskId: string]
  'retry-task': [taskId: string]
  'approve-task': [taskId: string]
}>()
const { t } = useI18n()
const closeButton = ref<HTMLButtonElement | null>(null)
const drawer = ref<HTMLElement | null>(null)
let returnFocus: HTMLElement | null = null

function close() {
  emit('close')
  void nextTick(() => returnFocus?.focus())
}
function onKeydown(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'Escape') { event.preventDefault(); close() }
  if (event.key !== 'Tab') return
  const focusable = Array.from(drawer.value?.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  ) ?? [])
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault(); last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault(); first.focus()
  }
}
watch(() => props.open, async (open) => {
  if (!open) return
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  document.addEventListener('keydown', onKeydown)
  await nextTick(); closeButton.value?.focus()
}, { immediate: true })
watch(() => props.open, open => { if (!open) document.removeEventListener('keydown', onKeydown) })
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div v-if="open && run" class="run-drawer-layer" @click.self="close">
    <aside ref="drawer" class="run-drawer" role="dialog" aria-modal="true" :aria-label="run.title">
      <header class="run-drawer__header">
        <div>
          <h2>{{ run.title }}</h2>
          <TeamRunStatus :status="run.status" :started-at="run.startedAt" :completed-at="run.completedAt" show-duration />
        </div>
        <button ref="closeButton" data-team-run-drawer-close type="button" :aria-label="t('teamRuns.close')" @click="close">
          <el-icon :size="17"><Close /></el-icon>
        </button>
      </header>
      <p v-if="run.status === 'partial'" class="run-drawer__notice is-partial">{{ t('teamRuns.partialNotice') }}</p>
      <p v-if="run.status === 'cancelled'" class="run-drawer__notice">{{ run.stopReason || t('teamRuns.status.cancelled') }}</p>
      <div v-if="detailLoading || run.projectionCompleteness === 'summary'" class="run-drawer__detail-state" role="status">
        <template v-if="detailLoading">{{ t('teamRuns.detailLoading') }}</template>
        <template v-else>
          <span>{{ detailError || t('teamRuns.detailUnavailable') }}</span>
          <button data-team-run-detail-retry type="button" @click="emit('retry-detail')">{{ t('teamRuns.retryLoad') }}</button>
        </template>
      </div>
      <TeamRunDetail
        v-else
        :run="run"
        :selected-task-id="selectedTaskId"
        :can-cancel="canCancel"
        :management-actions="managementActions"
        :pending-actions="pendingActions"
        @select-task="emit('select-task', $event)"
        @cancel="emit('cancel', $event)"
        @navigate="emit('navigate', $event)"
        @view-task="emit('view-task', $event)"
        @retry-task="emit('retry-task', $event)"
        @approve-task="emit('approve-task', $event)"
      />
    </aside>
  </div>
</template>

<style scoped>
.run-drawer-layer { position: fixed; z-index: 1200; inset: 0; display: flex; justify-content: flex-end; background: rgba(15, 23, 42, 0.28); }
.run-drawer { width: min(620px, 94vw); height: 100%; overflow-y: auto; border-left: 1px solid var(--mc-border); background: var(--mc-bg-elevated, #fff); box-shadow: -12px 0 28px rgba(15, 23, 42, 0.14); letter-spacing: 0; }
.run-drawer__header { position: sticky; z-index: 1; top: 0; display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px; border-bottom: 1px solid var(--mc-border); background: var(--mc-team-run-glass-bg, rgba(255,255,255,.88)); backdrop-filter: blur(12px) saturate(1.05); -webkit-backdrop-filter: blur(12px) saturate(1.05); }
.run-drawer__header h2 { margin: 0 0 6px; color: var(--mc-text-primary); font-size: 16px; overflow-wrap: anywhere; }
.run-drawer__header button { display: grid; width: 30px; height: 30px; flex: none; place-items: center; border: 0; border-radius: 6px; background: transparent; color: var(--mc-text-secondary); cursor: pointer; }
.run-drawer__header button:hover { background: var(--mc-bg-sunken); }
.run-drawer__header button:focus-visible { outline: 2px solid #16835b; outline-offset: 2px; }
.run-drawer__notice { margin: 0; padding: 9px 16px; border-bottom: 1px solid var(--mc-border-light); background: rgba(71, 85, 105, 0.06); color: var(--mc-text-secondary); font-size: 12px; }
.run-drawer__notice.is-partial { background: rgba(185, 108, 8, 0.08); color: #8a5108; }
.run-drawer__detail-state{display:grid;min-height:180px;place-content:center;gap:8px;padding:24px;color:var(--mc-text-secondary);font-size:13px;text-align:center}.run-drawer__detail-state button{border:0;background:transparent;color:#16795a;cursor:pointer}
@media (prefers-reduced-motion: reduce) { .run-drawer, .run-drawer * { scroll-behavior: auto !important; transition: none !important; animation: none !important; } }
@media (prefers-reduced-transparency: reduce) { .run-drawer__header { background: var(--mc-bg-elevated, #fff); backdrop-filter: none; -webkit-backdrop-filter: none; } }
@supports not (backdrop-filter: blur(1px)) { .run-drawer__header { background: var(--mc-bg-elevated, #fff); } }
</style>
