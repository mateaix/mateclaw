<template>
  <RouterLink
    v-if="!disabled"
    class="btn-secondary team-chat-button"
    :to="{ path: '/chat', query: { agentId: leadAgentId, action: 'newChat' } }"
    @click.stop
  >{{ t('teams.chatWithTeam') }}</RouterLink>
  <button v-else type="button" class="btn-secondary team-chat-button" disabled :title="t('teams.chatUnavailable')" @click.stop>
    {{ t('teams.chatWithTeam') }}
  </button>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'

defineProps<{ leadAgentId: string; disabled?: boolean }>()
const { t } = useI18n()
</script>

<style scoped>
.team-chat-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
  white-space: nowrap;
}
.team-chat-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
