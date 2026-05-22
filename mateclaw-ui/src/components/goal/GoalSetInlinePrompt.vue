<script setup lang="ts">
import { ref } from 'vue'
import { useGoalStore } from '@/stores/useGoalStore'

/**
 * The dashed inline "Set a goal" invitation that appears under an
 * assistant message when the conversation might benefit from a goal.
 *
 * Jobs-cut design: a single line of text + two buttons. No dialog. No
 * separate form. If the user clicks "Yes", the assistant's recent reply
 * + the user's last message determine the title — the parent passes
 * those in via the {@code suggestedTitle} prop. Everything else gets a
 * sensible default; users who want budgets / criteria can edit later
 * from the /goals page.
 */
const props = defineProps<{
  conversationId: string
  agentId: string
  workspaceId: string
  suggestedTitle: string
}>()
const emit = defineEmits<{ (e: 'dismiss'): void }>()

const goalStore = useGoalStore()
const busy = ref(false)
const dismissed = ref(false)

async function accept() {
  if (busy.value) return
  busy.value = true
  try {
    await goalStore.create(
      props.conversationId,
      props.agentId,
      props.workspaceId,
      props.suggestedTitle,
    )
  } finally {
    busy.value = false
    dismissed.value = true
  }
}

function decline() {
  dismissed.value = true
  emit('dismiss')
}
</script>

<template>
  <div v-if="!dismissed" class="goal-prompt-inline">
    <span class="gp-icon">🎯</span>
    <span class="gp-text">{{ $t('goal.inlinePrompt') }}</span>
    <button class="gp-btn" :disabled="busy" @click="decline">{{ $t('goal.inlinePromptDecline') }}</button>
    <button class="gp-btn is-primary" :disabled="busy" @click="accept">
      {{ busy ? '…' : $t('goal.inlinePromptAccept') }}
    </button>
  </div>
</template>

<style scoped>
.goal-prompt-inline {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 4px 0 4px 46px;
  padding: 8px 12px;
  background: var(--mc-bg-elevated, #ffffff);
  border: 1px dashed var(--mc-border, #d9cec2);
  border-radius: 10px;
  font-size: 13px;
  color: var(--mc-text-secondary, #665245);
  max-width: 480px;
}
.gp-icon {
  font-size: 14px;
}
.gp-text {
  flex: 1;
}
.gp-btn {
  background: transparent;
  border: 1px solid var(--mc-border, #d9cec2);
  border-radius: 999px;
  padding: 3px 12px;
  font-size: 12px;
  cursor: pointer;
  color: var(--mc-text-primary, #1d1612);
  font-family: inherit;
}
.gp-btn:hover:not(:disabled) {
  border-color: var(--mc-text-tertiary, #9b7d6c);
}
.gp-btn.is-primary {
  background: var(--mc-primary, #d97757);
  border-color: var(--mc-primary, #d97757);
  color: white;
}
.gp-btn.is-primary:hover:not(:disabled) {
  background: var(--mc-primary-hover, #c1572b);
  border-color: var(--mc-primary-hover, #c1572b);
}
.gp-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
