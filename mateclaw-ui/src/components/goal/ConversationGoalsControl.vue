<template>
  <button class="conversation-goals-button" type="button" :title="t('plans.goals')"
    :aria-label="t('plans.goals')" :aria-expanded="open" @click="show">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>
  </button>
  <GoalsPanel v-if="open" :open="open" :goals="goals" :loading="loading"
    :title="t('plans.goals')" :error="error" :has-more="hasMore" :show-refresh="true"
    @close="close" @refresh="load(false)" @load-more="load(true)" />
</template>

<script setup lang="ts">
import { ref, watch, onBeforeUnmount, onDeactivated } from 'vue'
import { useI18n } from 'vue-i18n'
import { goalApi, type Goal } from '@/api'
import GoalsPanel from '@/components/agents/GoalsPanel.vue'

const props = defineProps<{ conversationId: string }>()
const { t } = useI18n()
const open = ref(false)
const loading = ref(false)
const goals = ref<Goal[]>([])
const error = ref('')
const hasMore = ref(false)
let request = 0

function close() {
  request++
  open.value = false
  loading.value = false
  goals.value = []
  error.value = ''
  hasMore.value = false
}
async function show() {
  open.value = true
  await load(false)
}
async function load(append: boolean) {
  if (loading.value || !props.conversationId) return
  const cursor = append ? goals.value.at(-1)?.id : undefined
  const token = ++request
  const conversation = props.conversationId
  loading.value = true
  error.value = ''
  if (!append) goals.value = []
  try {
    const response: any = await goalApi.history(conversation, cursor)
    if (token !== request || conversation !== props.conversationId || !open.value) return
    const page: Goal[] = response?.data ?? []
    goals.value = append ? [...goals.value, ...page] : page
    hasMore.value = page.length === 20
  } catch {
    if (token !== request || conversation !== props.conversationId || !open.value) return
    goals.value = []
    hasMore.value = false
    error.value = t('goalJsonAcceptance.historyLoadFailed')
  } finally {
    if (token === request) loading.value = false
  }
}
watch(() => props.conversationId, close)
onBeforeUnmount(close)
onDeactivated(close)
</script>

<style scoped>
.conversation-goals-button { width:30px; height:30px; border:1px solid var(--mc-border); background:var(--mc-panel-raised); border-radius:10px; cursor:pointer; display:flex; align-items:center; justify-content:center; color:var(--mc-text-secondary); }
.conversation-goals-button:hover { border-color:var(--mc-danger); color:var(--mc-danger); }
</style>
