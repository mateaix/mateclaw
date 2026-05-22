<script setup lang="ts">
/**
 * The horizontal-rule "system line" that announces a goal terminal state
 * inside the message stream. Replaces the v1 banner.
 *
 * Rendered by ChatConsole / MessageList when it sees a goal_completed
 * or goal_exhausted SSE event. Keeps the chat the source of truth and
 * the goal itself moves out of the visual hierarchy when it's done.
 */
defineProps<{
  variant: 'completed' | 'exhausted'
  title: string
  detail?: string
}>()
</script>

<template>
  <div class="system-line" :class="`is-${variant}`">
    <div class="sl-title">
      <span class="sl-icon">{{ variant === 'completed' ? '✦' : '⚠' }}</span>
      <span>{{ title }}</span>
    </div>
    <div v-if="detail" class="sl-detail">{{ detail }}</div>
  </div>
</template>

<style scoped>
.system-line {
  align-self: center;
  max-width: 540px;
  text-align: center;
  margin: 12px auto;
  padding: 10px 16px;
  font-size: 13px;
  color: var(--mc-text-secondary, #665245);
  border-top: 1px solid var(--mc-border-light, #ebe3db);
  border-bottom: 1px solid var(--mc-border-light, #ebe3db);
  line-height: 1.5;
}
.sl-title {
  font-weight: 600;
  color: var(--mc-text-primary, #1d1612);
  margin-bottom: 2px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.sl-icon { font-size: 14px; }
.sl-detail { font-size: 12px; color: var(--mc-text-tertiary, #9b7d6c); }
.system-line.is-completed .sl-title { color: #2f8a6d; }
.system-line.is-exhausted .sl-title { color: #c5663d; }
</style>
