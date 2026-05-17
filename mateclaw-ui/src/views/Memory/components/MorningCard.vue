<template>
  <Transition name="slide-down">
    <div v-if="card" class="morning-card">
      <div class="morning-card__header">
        <span class="morning-card__icon">🌅</span>
        <span class="morning-card__title">{{ t('memory.morningCard.title') }}</span>
        <button class="morning-card__close" @click="dismiss">&times;</button>
      </div>
      <div class="morning-card__body">
        <span class="morning-card__mode">{{ card.mode === 'FOCUSED' ? t('memory.modeFocused') : t('memory.modeNightly') }}</span>
        <span v-if="card.topic" class="morning-card__topic">{{ card.topic }}</span>
        <p class="morning-card__summary">
          {{ t('memory.morningCard.promoted', { count: card.promotedCount }) }}
        </p>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { http } from '@/api'

const props = defineProps<{ agentId: string | number }>()
const { t } = useI18n()
const card = ref<any>(null)

watch(() => props.agentId, async (id) => {
  if (!id) { card.value = null; return }
  try {
    const res = await http.get(`/memory/${id}/dream/morning-card`)
    card.value = res.data
  } catch { card.value = null }
}, { immediate: true })

async function dismiss() {
  if (!card.value) return
  try { await http.post(`/memory/${props.agentId}/dream/morning-card/seen`, { reportId: card.value.reportId }) } catch {}
  card.value = null
}
</script>

<style scoped>
.morning-card {
  margin: 0 12px 8px;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--mc-primary-bg);
  border: 1px solid var(--mc-border-light);
}
.morning-card__header {
  display: flex;
  align-items: center;
  gap: 6px;
}
.morning-card__icon { font-size: 16px; }
.morning-card__title { flex: 1; font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }
.morning-card__close {
  background: none; border: none; font-size: 16px; color: var(--mc-text-tertiary);
  cursor: pointer; line-height: 1;
}
.morning-card__body { margin-top: 6px; font-size: 12px; color: var(--mc-text-secondary); }
.morning-card__mode { font-weight: 600; text-transform: uppercase; font-size: 10px; letter-spacing: 0.5px; }
.morning-card__topic { margin-left: 6px; font-weight: 500; }
.morning-card__summary { margin: 4px 0 0; }

.slide-down-enter-active, .slide-down-leave-active { transition: all 0.25s ease; }
.slide-down-enter-from, .slide-down-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
