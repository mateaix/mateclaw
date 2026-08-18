<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowDown, UserFilled } from '@element-plus/icons-vue'
import type { Message } from '@/types'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'

const { t } = useI18n()
const { renderMarkdown } = useMarkdownRenderer()

const props = defineProps<{
  message: Message
}>()

// Collapsed by default: the settlement note is orchestration bookkeeping, not
// conversation content. Users who want the per-task detail expand on demand.
const expanded = ref(false)

const meta = computed<Record<string, any>>(() => {
  const raw = (props.message as any).metadata
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) || {} } catch { return {} }
})

const label = computed(() => {
  const count = Number(meta.value.taskCount) || 0
  return count > 0
    ? t('chat.teamAnnounce', { count })
    : t('chat.teamAnnounceGeneric')
})

// Show the per-task result blocks but trim the trailing model-facing
// orchestration instructions ("Review these results ... team_tasks(...)") —
// they stay in the stored content for the LLM turn, only display drops them.
const displayText = computed(() => {
  const content = props.message.content || ''
  const cut = content.indexOf('\n\nReview these results')
  return (cut > 0 ? content.slice(0, cut) : content).trim()
})
const displayHtml = computed(() => renderMarkdown(displayText.value))
</script>

<template>
  <div class="team-announce">
    <button class="team-announce__header" type="button" @click="expanded = !expanded">
      <span class="team-announce__icon"><el-icon :size="13"><UserFilled /></el-icon></span>
      <span class="team-announce__label">{{ label }}</span>
      <el-icon class="team-announce__arrow" :class="{ 'is-open': expanded }" :size="12"><ArrowDown /></el-icon>
    </button>
    <Transition name="team-announce-slide">
      <div v-if="expanded" class="team-announce__body markdown-body" v-html="displayHtml" />
    </Transition>
  </div>
</template>

<style scoped>
.team-announce {
  margin: 8px 0;
  border-radius: 8px;
  border: 1px dashed var(--mc-border, rgba(0, 0, 0, 0.12));
  background: transparent;
  overflow: hidden;
}
.team-announce__header {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  background: none;
  border: none;
  cursor: pointer;
  user-select: none;
  text-align: left;
}
.team-announce__header:hover {
  color: var(--mc-text-secondary);
}
.team-announce__icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.team-announce__label {
  flex: 1;
  font-weight: 500;
}
.team-announce__arrow {
  transition: transform 0.2s;
}
.team-announce__arrow.is-open {
  transform: rotate(180deg);
}
.team-announce__body {
  margin: 0 12px 8px;
  padding: 6px 0 6px 10px;
  border-left: 2px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  font-size: 13px;
  line-height: 1.7;
  color: var(--mc-text-secondary);
  word-break: break-word;
  overflow-wrap: anywhere;
  max-height: 260px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.team-announce__body :deep(p) {
  margin: 0 0 8px;
}
.team-announce__body :deep(p:last-child) {
  margin-bottom: 0;
}
.team-announce__body :deep(ul),
.team-announce__body :deep(ol) {
  margin: 6px 0;
  padding-left: 20px;
}
.team-announce__body :deep(li) {
  margin: 3px 0;
}
.team-announce__body :deep(h1),
.team-announce__body :deep(h2),
.team-announce__body :deep(h3) {
  margin: 10px 0 6px;
  color: var(--mc-text-primary);
  line-height: 1.35;
}
.team-announce__body :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
  margin: 8px 0;
}
.team-announce__body :deep(th),
.team-announce__body :deep(td) {
  border: 1px solid var(--mc-border);
  padding: 5px 8px;
  text-align: left;
  white-space: nowrap;
}
.team-announce__body :deep(th) {
  background: var(--mc-bg-subtle, rgba(0, 0, 0, 0.04));
  color: var(--mc-text-primary);
}
.team-announce__body :deep(blockquote) {
  margin: 8px 0;
  padding-left: 10px;
  border-left: 3px solid var(--mc-primary);
  color: var(--mc-text-secondary);
}
.team-announce__body :deep(pre) {
  max-width: 100%;
  overflow-x: auto;
  padding: 8px 10px;
  border-radius: 7px;
  background: var(--mc-bg-subtle, rgba(0, 0, 0, 0.05));
}
.team-announce__body :deep(code) {
  overflow-wrap: anywhere;
}

.team-announce-slide-enter-active, .team-announce-slide-leave-active {
  transition: all 0.2s ease;
}
.team-announce-slide-enter-from, .team-announce-slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
