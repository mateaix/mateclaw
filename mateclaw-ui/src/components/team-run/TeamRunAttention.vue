<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TeamRun } from '@/api'
import { runAttention } from './teamRunProjection'
import TeamRunReadingSurface from './TeamRunReadingSurface.vue'

const props = withDefaults(defineProps<{
  run: TeamRun
  managementActions?: boolean
  pendingActions?: string[]
}>(), { managementActions: false, pendingActions: () => [] })

const emit = defineEmits<{
  'view-task': [taskId: string]
  'retry-task': [taskId: string]
  'approve-task': [taskId: string]
}>()

const { t } = useI18n()
const items = computed(() => runAttention(props.run))
const retryable = (type: string) => ['failed', 'failure', 'stale'].includes(type.toLowerCase())
const reviewable = (type: string) => ['review', 'in_review'].includes(type.toLowerCase())
const isPending = (taskId: string, action: 'retry' | 'approve') => props.pendingActions.includes(`${taskId}:${action}`)
</script>

<template>
  <TeamRunReadingSurface data-team-run-attention class="run-attention">
    <h3>{{ t('teamRuns.attention') }}</h3>
    <ul v-if="items.length">
      <li v-for="item in items" :key="item.id" :class="`is-${item.severity}`">
        <div class="run-attention__copy">
          <strong>{{ item.type }}</strong>
          <span>{{ item.message }}</span>
        </div>
        <div
          v-if="managementActions && item.taskId"
          data-team-run-attention-actions
          class="run-attention__actions"
          :aria-label="item.message"
        >
          <button
            type="button"
            :data-attention-view-task="item.taskId"
            @click="emit('view-task', item.taskId)"
          >{{ t('teamRuns.openTask') }}</button>
          <button
            v-if="retryable(item.type)"
            type="button"
            class="is-primary"
            :data-attention-retry-task="item.taskId"
            :disabled="isPending(item.taskId, 'retry')"
            :aria-busy="isPending(item.taskId, 'retry')"
            @click="emit('retry-task', item.taskId)"
          >{{ t('common.retry') }}</button>
          <button
            v-if="reviewable(item.type)"
            type="button"
            class="is-primary"
            :data-attention-approve-task="item.taskId"
            :disabled="isPending(item.taskId, 'approve')"
            :aria-busy="isPending(item.taskId, 'approve')"
            @click="emit('approve-task', item.taskId)"
          >{{ t('common.approve') }}</button>
        </div>
      </li>
    </ul>
    <p v-else>{{ t('teamRuns.noAttention') }}</p>
  </TeamRunReadingSurface>
</template>

<style scoped>
h3 { margin: 0 0 7px; font-size: 13px; }
ul { display: grid; gap: 5px; margin: 0; padding: 0; list-style: none; }
li { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 10px; padding: 7px 9px; border-left: 3px solid #b96c08; background: #fff9ef; font-size: 12px; }
li.is-error { border-left-color: #c13d3d; background: #fff5f5; }
.run-attention__copy { display: grid; min-width: 0; gap: 2px; overflow-wrap: anywhere; }
.run-attention__copy strong { text-transform: capitalize; }
.run-attention__actions { display: flex; min-width: 0; flex: none; flex-wrap: wrap; justify-content: flex-end; gap: 5px; }
.run-attention__actions button { min-height: 28px; padding: 4px 8px; border: 1px solid var(--mc-border, #d9e1e7); border-radius: 5px; background: #fff; color: var(--mc-text-secondary, #475569); cursor: pointer; font: inherit; white-space: nowrap; }
.run-attention__actions button.is-primary { border-color: #16835b; color: #126c4d; }
.run-attention__actions button:disabled { cursor: wait; opacity: 0.6; }
.run-attention__actions button:focus-visible { outline: 2px solid #16835b; outline-offset: 2px; }
p { margin: 0; color: var(--mc-text-tertiary); font-size: 12px; }
@media (max-width: 520px) {
  li { align-items: stretch; flex-direction: column; }
  .run-attention__actions { justify-content: flex-start; }
}
</style>
