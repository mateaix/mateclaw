<script setup lang="ts">
import { ElMessage } from 'element-plus/es/components/message/index'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import JsonView from './JsonView.vue'

/**
 * Full-detail viewer for an execution step or a tool call.
 * Shows the complete request payload and response output without truncation,
 * so users can audit exactly what an agent ran and what came back.
 */
const props = defineProps<{
  modelValue: boolean
  title: string
  status?: 'running' | 'completed' | 'error' | 'pending'
  /** Raw request payload (typically JSON arguments). Optional — plan steps have none. */
  request?: string
  /** Raw response / output text. */
  response?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
}>()

const { t } = useI18n()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const hasRequest = computed(() => !!(props.request || '').trim())
const hasResponse = computed(() => !!(props.response || '').trim())

const statusLabel = computed(() => t(`chat.detail.status.${props.status || 'pending'}`))

async function copy(text?: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('chat.detail.copied'))
  } catch {
    ElMessage.error(t('chat.detail.copyFailed'))
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    width="700px"
    append-to-body
    align-center
    :show-close="false"
    class="exec-detail-dialog"
    modal-class="exec-detail-overlay"
  >
    <template #header>
      <div class="exec-detail__head">
        <span class="exec-detail__dot" :class="`is-${status || 'pending'}`" />
        <span class="exec-detail__title">{{ title }}</span>
        <span v-if="status" class="exec-detail__badge" :class="`is-${status}`">{{ statusLabel }}</span>
        <button class="exec-detail__close" :aria-label="$t('common.close')" @click="visible = false">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18" /></svg>
        </button>
      </div>
    </template>

    <div class="exec-detail__body">
      <section v-if="hasRequest" class="exec-detail__section">
        <div class="exec-detail__label">
          <span>{{ $t('chat.detail.request') }}</span>
          <button class="exec-detail__copy" :title="$t('chat.detail.copy')" @click="copy(request)">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V5a2 2 0 0 1 2-2h10" /></svg>
          </button>
        </div>
        <JsonView :raw="request" />
      </section>

      <section class="exec-detail__section">
        <div class="exec-detail__label">
          <span>{{ $t('chat.detail.response') }}</span>
          <button v-if="hasResponse" class="exec-detail__copy" :title="$t('chat.detail.copy')" @click="copy(response)">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V5a2 2 0 0 1 2-2h10" /></svg>
          </button>
        </div>
        <JsonView v-if="hasResponse" :raw="response" />
        <div v-else class="exec-detail__empty">{{ $t('chat.detail.empty') }}</div>
      </section>
    </div>
  </el-dialog>
</template>

<style scoped>
.exec-detail__head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.exec-detail__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--mc-text-quaternary, #c0bfbc);
}
.exec-detail__dot.is-completed { background: var(--mc-success, #67c23a); }
.exec-detail__dot.is-error { background: var(--mc-danger, #f56c6c); }
.exec-detail__dot.is-running { background: var(--mc-primary, #d96d46); }

.exec-detail__title {
  font-weight: 600;
  font-size: 15px;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.exec-detail__badge {
  flex-shrink: 0;
  font-size: 11px;
  line-height: 18px;
  padding: 0 8px;
  border-radius: 9px;
  font-weight: 500;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-muted, #f1ece8);
}
.exec-detail__badge.is-completed {
  color: var(--mc-success, #4f9a3f);
  background: rgba(103, 194, 58, 0.12);
}
.exec-detail__badge.is-error {
  color: var(--mc-danger, #d9533f);
  background: rgba(245, 108, 108, 0.12);
}
.exec-detail__badge.is-running {
  color: var(--mc-primary, #d96d46);
  background: var(--mc-primary-bg, rgba(217, 109, 70, 0.1));
}

.exec-detail__close {
  margin-left: auto;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.exec-detail__close:hover {
  background: var(--mc-bg-muted, #f1ece8);
  color: var(--mc-text-primary);
}

.exec-detail__body {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.exec-detail__section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.exec-detail__label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  color: var(--mc-text-tertiary);
}
.exec-detail__copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.exec-detail__copy:hover {
  background: var(--mc-bg-muted, #f1ece8);
  color: var(--mc-primary);
}
.exec-detail__empty {
  padding: 16px;
  font-size: 13px;
  color: var(--mc-text-tertiary);
  text-align: center;
  background: rgba(255, 255, 255, 0.3);
  border: 1px dashed var(--mc-border-light);
  border-radius: 10px;
}
</style>

<!-- Frosted-glass dialog shell. Non-scoped because el-dialog teleports to body,
     out of this component's scoped style reach. -->
<style>
.exec-detail-overlay {
  background: rgba(28, 20, 16, 0.28) !important;
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
}
.exec-detail-dialog.el-dialog {
  background: rgba(255, 255, 255, 0.62);
  backdrop-filter: blur(24px) saturate(180%);
  -webkit-backdrop-filter: blur(24px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.55);
  border-radius: 18px;
  box-shadow: 0 16px 56px rgba(28, 20, 16, 0.22);
  overflow: hidden;
}
.exec-detail-dialog .el-dialog__header {
  margin: 0;
  padding: 16px 18px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.4);
}
.exec-detail-dialog .el-dialog__body {
  padding: 14px 18px 20px;
}

html.dark .exec-detail-overlay {
  background: rgba(0, 0, 0, 0.42) !important;
}
html.dark .exec-detail-dialog.el-dialog {
  background: rgba(34, 27, 23, 0.6);
  border-color: rgba(255, 255, 255, 0.08);
  box-shadow: 0 16px 56px rgba(0, 0, 0, 0.55);
}
html.dark .exec-detail-dialog .el-dialog__header {
  border-bottom-color: rgba(255, 255, 255, 0.08);
}
</style>
