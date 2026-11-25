<template>
  <section v-if="briefing" class="team-task-briefing">
    <h4>{{ t('chatTeam.savedBriefing') }}</h4>
    <p>{{ t('chatTeam.savedHint') }}</p>
    <template v-for="key in fields" :key="key"
      ><div v-if="briefing[key]">
        <strong>{{ t(`chatTeam.${key}`) }}</strong>
        <p class="team-task-briefing__text">{{ briefing[key] }}</p>
      </div></template
    >
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { parseTeamBriefing } from '@/composables/chat/teamBriefing'
const props = defineProps<{ metadata: string | null | undefined }>()
const { t } = useI18n()
const briefing = computed(() => parseTeamBriefing(props.metadata))
const fields = [
  'objective',
  'description',
  'acceptanceCriteria',
  'constraints',
] as const
</script>
<style scoped>
.team-task-briefing {
  padding: 12px;
  border: 1px solid var(--mc-border, #ddd);
  border-radius: 8px;
  margin-bottom: 16px;
}
.team-task-briefing p {
  font-size: 13px;
  color: var(--mc-text-secondary, #666);
}
.team-task-briefing__text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
