<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Download, CircleCheckFilled, WarningFilled, Document, Files } from '@element-plus/icons-vue'
import { operationalApi } from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

/**
 * Self-contained operational-data export: a global-admin-only trigger button
 * plus the generate → progress → download dialog and its polling state machine.
 * Drop <OperationalExport /> anywhere; it gates its own visibility and owns all
 * export state, so the host view stays free of export concerns.
 */
const { t } = useI18n()
const workspaceStore = useWorkspaceStore()
const isGlobalAdmin = computed(() => workspaceStore.isGlobalAdmin)

type ExportStatus = 'idle' | 'generating' | 'locked' | 'completed' | 'failed'

const visible = ref(false)
const dateRange = ref<[string, string] | null>(null)
const status = ref<ExportStatus>('idle')
const step = ref(0)
const total = ref(9)
const taskId = ref('')
const downloadToken = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

// Excel sheet names, shown under the ring as the step advances. These mirror the
// server-side sheet titles (which are emitted in Chinese in the workbook itself).
const stepLabels = ['概览汇总', 'Token用量', '技能统计', '用户统计', '用户对话', '安全与审计', '渠道统计', '模型配置', '定时任务']
const stepLabel = computed(() => stepLabels[step.value - 1] || '')

const RADIUS = 52
const circumference = 2 * Math.PI * RADIUS
const progressOffset = computed(() => circumference * (1 - Math.min(step.value / total.value, 1)))

const isBusy = computed(() => status.value === 'generating' || status.value === 'locked')

function formatDate(d: Date): string {
  return d.toISOString().slice(0, 10)
}

/** Disable future dates in the picker; the 90-day span cap is enforced server-side. */
function disabledDate(date: Date): boolean {
  const now = new Date()
  now.setHours(23, 59, 59, 0)
  return date > now
}

// Quick-range presets (inclusive day counts) shown as chips above the picker.
const PRESETS = [7, 30, 90]
function applyPreset(days: number) {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - days + 1)
  dateRange.value = [formatDate(start), formatDate(end)]
}
function isPresetActive(days: number): boolean {
  if (!dateRange.value) return false
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - days + 1)
  return dateRange.value[0] === formatDate(start) && dateRange.value[1] === formatDate(end)
}

function open() {
  if (status.value === 'completed') {
    // keep the completed state so the user can still download
  } else if (isBusy.value) {
    startPolling()
  } else {
    applyPreset(30)
    status.value = 'idle'
    step.value = 0
  }
  visible.value = true
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(visible, (v) => {
  if (!v) stopPolling()
})
onUnmounted(stopPolling)

async function doGenerate() {
  if (!dateRange.value) return
  try {
    status.value = 'generating'
    step.value = 0
    const [start, end] = dateRange.value
    const res: any = await operationalApi.generate(start, end)
    taskId.value = res.data?.taskId || res.taskId || ''
    if (!taskId.value) throw new Error('No taskId')
    startPolling()
  } catch (e: any) {
    if (e?.response?.status === 409) {
      status.value = 'locked'
    } else {
      status.value = 'failed'
      console.error('Export generate failed:', e?.response?.data?.msg || e?.message || e)
    }
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const res: any = await operationalApi.progress(taskId.value)
      const data = res.data || res
      step.value = data.step || 0
      total.value = data.total || 9
      if (data.status === 'completed') {
        status.value = 'completed'
        downloadToken.value = data.downloadToken || ''
        stopPolling()
      } else if (data.status === 'failed') {
        status.value = 'failed'
        stopPolling()
      }
    } catch {
      status.value = 'failed'
      stopPolling()
    }
  }, 1000)
}

async function doDownload() {
  try {
    await operationalApi.download(taskId.value, downloadToken.value)
    visible.value = false
    status.value = 'idle'
    taskId.value = ''
    downloadToken.value = ''
  } catch (e) {
    console.error('Download failed:', e)
  }
}
</script>

<template>
  <button v-if="isGlobalAdmin" class="oe-trigger" @click="open">
    <el-icon :size="15"><Download /></el-icon>
    {{ t('dashboard.operationalExport') }}
  </button>

  <el-dialog
    v-model="visible"
    :title="t('dashboard.operationalExport')"
    width="460px"
    align-center
    :close-on-click-modal="false"
  >
    <div class="oe-body">
      <!-- Pick a range (idle / failed) -->
      <template v-if="status === 'idle' || status === 'failed'">
        <div class="oe-intro">
          <div class="oe-intro__icon"><el-icon :size="20"><Document /></el-icon></div>
          <p class="oe-intro__desc">{{ t('dashboard.exportDescription') }}</p>
        </div>

        <div class="oe-field">
          <span class="oe-field__label">{{ t('common.selectRange') }}</span>
          <div class="oe-quick">
            <button
              v-for="d in PRESETS" :key="d"
              type="button"
              class="oe-quick__chip"
              :class="{ 'is-active': isPresetActive(d) }"
              @click="applyPreset(d)"
            >{{ t('common.lastDays', { n: d }) }}</button>
          </div>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="~"
            :start-placeholder="t('common.startDate')"
            :end-placeholder="t('common.endDate')"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            :disabled-date="disabledDate"
            style="width:100%"
          />
        </div>

        <div class="oe-includes">
          <el-icon :size="13"><Files /></el-icon>
          <span>{{ t('dashboard.exportIncludes', { count: total }) }}</span>
        </div>

        <div v-if="status === 'failed'" class="oe-alert oe-alert--error">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ t('dashboard.generateFailed') }}</span>
        </div>
      </template>

      <!-- Generating (ring) -->
      <div v-else-if="isBusy" class="oe-progress">
        <div class="oe-ring">
          <svg class="oe-ring__svg" viewBox="0 0 120 120">
            <circle class="oe-ring__track" cx="60" cy="60" r="52" fill="none" />
            <circle
              class="oe-ring__arc" cx="60" cy="60" r="52" fill="none"
              :stroke-dasharray="circumference"
              :stroke-dashoffset="progressOffset"
            />
          </svg>
          <div class="oe-ring__inner">
            <span class="oe-ring__step">{{ step }}/{{ total }}</span>
            <span class="oe-ring__label">{{ stepLabel }}</span>
          </div>
        </div>
        <p class="oe-progress__hint">
          {{ status === 'locked' ? t('dashboard.exportInProgress') : t('dashboard.generating') }}
        </p>
      </div>

      <!-- Completed -->
      <div v-else-if="status === 'completed'" class="oe-done">
        <el-icon class="oe-done__icon" :size="44"><CircleCheckFilled /></el-icon>
        <p class="oe-done__title">{{ t('dashboard.reportReady') }}</p>
        <p class="oe-done__hint">{{ t('dashboard.expiredHint') }}</p>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">{{ t('common.close') }}</el-button>
      <el-button
        v-if="status === 'idle' || status === 'failed'"
        type="primary"
        :disabled="!dateRange"
        @click="doGenerate"
      >
        {{ status === 'failed' ? t('dashboard.regenerating') : t('dashboard.generateReport') }}
      </el-button>
      <el-button v-else-if="isBusy" type="primary" disabled loading>
        {{ status === 'locked' ? t('dashboard.exportInProgress') : t('dashboard.generating') }}
      </el-button>
      <el-button v-else-if="status === 'completed'" type="primary" @click="doDownload">
        <el-icon style="margin-right:4px"><Download /></el-icon>
        {{ t('dashboard.downloadReport') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* Pill matching the header's database chip shape, but tinted with the brand
   color so the export action stands out from the neutral chip beside it. */
.oe-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border: 1px solid var(--mc-primary-light);
  border-radius: 999px;
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  height: 26px;
  cursor: pointer;
  transition: all 0.18s;
  white-space: nowrap;
}
.oe-trigger:hover {
  border-color: var(--mc-primary);
  background: var(--mc-primary);
  color: #fff;
}

.oe-body {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 120px;
  justify-content: center;
}
.oe-intro {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.oe-intro__icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--mc-primary-soft, rgba(217, 119, 87, 0.12));
  color: var(--mc-primary);
}
.oe-intro__desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--mc-text-secondary);
}

.oe-quick {
  display: flex;
  gap: 8px;
}
.oe-quick__chip {
  padding: 4px 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 999px;
  background: transparent;
  font-size: 12px;
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: all 0.15s;
}
.oe-quick__chip:hover {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}
.oe-quick__chip.is-active {
  border-color: var(--mc-primary);
  background: var(--mc-primary-soft, rgba(217, 119, 87, 0.12));
  color: var(--mc-primary);
  font-weight: 600;
}

.oe-includes {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--mc-text-muted);
}
.oe-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.oe-field__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-tertiary);
}

.oe-alert {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
}
.oe-alert--error {
  background: var(--mc-danger-bg, rgba(239, 68, 68, 0.08));
  color: var(--mc-danger, #ef4444);
}

.oe-progress {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 8px 0;
}
.oe-ring {
  width: 120px;
  height: 120px;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.oe-ring__svg {
  position: absolute;
  inset: 0;
  width: 120px;
  height: 120px;
  animation: oe-ring-rotate 80s linear infinite;
}
.oe-ring__track {
  stroke: var(--mc-border-light, #e5e7eb);
  stroke-width: 8;
}
.oe-ring__arc {
  stroke: var(--mc-primary, #4f7aff);
  stroke-width: 8;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.6s cubic-bezier(0.4, 0, 0.2, 1);
  transform: rotate(-90deg);
  transform-origin: 60px 60px;
}
@keyframes oe-ring-rotate {
  to { transform: rotate(360deg); }
}
.oe-ring__inner {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  background: var(--mc-bg-surface);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  z-index: 1;
}
.oe-ring__step {
  font-size: 22px;
  font-weight: 700;
  color: var(--mc-primary);
}
.oe-ring__label {
  font-size: 11px;
  color: var(--mc-text-muted);
  margin-top: 2px;
}
.oe-progress__hint {
  margin: 0;
  font-size: 13px;
  color: var(--mc-text-secondary);
}

.oe-done {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  text-align: center;
}
.oe-done__icon {
  color: var(--mc-success, #67c23a);
}
.oe-done__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--mc-text-primary);
}
.oe-done__hint {
  margin: 0;
  font-size: 12px;
  color: var(--mc-text-muted);
}
</style>
