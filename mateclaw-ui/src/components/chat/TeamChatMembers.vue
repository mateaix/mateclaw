<template>
  <section class="team-chat-members" :aria-label="t('chatTeam.members')">
    <div class="team-chat-members__heading">
      <strong>{{ team.name }}</strong
      ><span>{{
        t(
          coordinatorMode
            ? 'chatTeam.coordinatorOn'
            : 'chatTeam.coordinatorOff',
        )
      }}</span>
    </div>
    <div class="team-chat-members__roster">
      <button
        v-for="member in members"
        :key="member.agentId"
        type="button"
        :disabled="disabled || member.enabled !== true"
        :title="member.description || member.name"
        @click="emit('mention', member)"
      >
        <strong>{{ member.name }}</strong><span v-if="member.description">{{
          member.description
        }}</span>
        <small
          >{{
            t(
              member.enabled === true
                ? 'chatTeam.enabled'
                : member.enabled === false
                  ? 'chatTeam.disabled'
                  : 'chatTeam.enabledUnknown',
            )
          }}
          · {{ t(`chatTeam.${member.activity}`) }}</small
        >
      </button>
    </div>
    <p>{{ t('chatTeam.preferenceHint') }}</p>
  </section>
</template>
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { AgentTeam } from '@/api'
import type { ChatTeamMember } from '@/composables/chat/useChatTeam'
defineProps<{
  team: AgentTeam
  members: ChatTeamMember[]
  coordinatorMode: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ mention: [member: ChatTeamMember] }>()
const { t } = useI18n()
</script>
<style scoped>
.team-chat-members {
  padding: 8px 14px;
  border-top: 1px solid var(--mc-border, #ddd);
  font-size: 12px;
}
.team-chat-members__heading {
  display: flex;
  gap: 12px;
  align-items: center;
}
.team-chat-members__roster {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  margin-top: 8px;
}
button {
  flex: 0 0 190px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  text-align: left;
  padding: 8px;
  border: 1px solid var(--mc-border, #ddd);
  border-radius: 8px;
  background: var(--mc-bg-elevated, #fff);
  color: inherit;
  cursor: pointer;
}
button:disabled {
  cursor: default;
  opacity: 0.65;
}
button span {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
small,
p {
  color: var(--mc-text-secondary, #666);
}
p {
  margin: 6px 0 0;
}
@media (max-width: 768px) {
  button {
    flex-basis: 145px;
  }
  .team-chat-members {
    padding: 6px 10px;
  }
}
</style>
