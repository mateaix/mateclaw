<template>
  <div class="mcp-empty">
    <svg
      class="mcp-empty-icon"
      width="48"
      height="48"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.4"
    >
      <rect x="2" y="2" width="20" height="8" rx="2" ry="2" />
      <rect x="2" y="14" width="20" height="8" rx="2" ry="2" />
      <line x1="6" y1="6" x2="6.01" y2="6" />
      <line x1="6" y1="18" x2="6.01" y2="18" />
    </svg>
    <p class="mcp-empty-title">{{ title }}</p>
    <p v-if="hint" class="mcp-empty-hint">{{ hint }}</p>
    <button v-if="kind === 'initial'" class="mcp-empty-cta" @click="$emit('add')">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <line x1="12" y1="5" x2="12" y2="19" />
        <line x1="5" y1="12" x2="19" y2="12" />
      </svg>
      {{ t('mcp.addCustom') }}
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  kind: 'initial' | 'search'
}>()

defineEmits<{ (e: 'add'): void }>()

const { t } = useI18n()

const title = computed(() =>
  props.kind === 'search' ? t('mcp.emptyMatch') : t('mcp.messages.empty'),
)
const hint = computed(() =>
  props.kind === 'search' ? '' : t('mcp.messages.emptyDesc'),
)
</script>

<style scoped>
.mcp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 56px 24px;
  color: var(--mc-text-tertiary);
  text-align: center;
}
.mcp-empty-icon { opacity: 0.35; }
.mcp-empty-title { font-size: 14px; margin: 4px 0 0; }
.mcp-empty-hint { font-size: 12px; margin: 0; }
.mcp-empty-cta {
  margin-top: 10px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  background: var(--mc-primary);
  color: #fff;
  border: none;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.mcp-empty-cta:hover { background: var(--mc-primary-hover); }
</style>
