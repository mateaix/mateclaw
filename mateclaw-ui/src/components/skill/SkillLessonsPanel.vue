<template>
  <div class="skill-lessons-panel">
    <article v-for="(entry,index) in entries" :key="`${entry.id}-${index}`" class="lesson-entry">
      <header><strong>{{ entry.heading }}</strong><button type="button" :disabled="disabled || !entry.deletable"
        :title="entry.deletable ? t('skills.detail.withdrawLesson') : t('skills.detail.ambiguousLesson')"
        @click="emit('remove',entry.id)">{{ t('skills.detail.withdrawLesson') }}</button></header>
      <p>{{ entry.content }}</p>
    </article>
  </div>
</template>
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { SkillLessonEntry } from '@/api'
defineProps<{entries:SkillLessonEntry[];disabled?:boolean}>()
const emit=defineEmits<{remove:[id:string]}>()
const {t}=useI18n()
</script>
<style scoped>
.lesson-entry { padding: 12px; margin-top: 10px; border: 1px solid var(--mc-border); border-radius: 8px; }
header { display: flex; justify-content: space-between; align-items: center; gap: 12px; font-size: 12px; }
button { color: var(--mc-danger); background: none; border: 0; cursor: pointer; flex-shrink: 0; }
button:disabled { opacity: .4; cursor: default; }
p { white-space: pre-wrap; overflow-wrap: anywhere; margin: 10px 0 0; font-size: 13px; line-height: 1.6; }
</style>
