<template>
  <div class="preview-spinner" role="status" :aria-label="label">
    <span class="preview-spinner__ring" />
    <span v-if="label" class="preview-spinner__label">{{ label }}</span>
  </div>
</template>

<script setup lang="ts">
// Self-drawn spinner in the app's terracotta accent — replaces Element Plus's
// blue v-loading directive so preview surfaces stay on-theme (and drops the
// "Failed to resolve directive: loading" warning).
defineProps<{ label?: string }>()
</script>

<style scoped>
.preview-spinner {
  height: 100%;
  min-height: 160px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
}
.preview-spinner__ring {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 3px solid var(--mc-primary-bg, #f6e2d7);
  border-top-color: var(--mc-primary, #d96d46);
  animation: preview-spin 0.7s linear infinite;
}
.preview-spinner__label {
  font-size: var(--mc-text-sm, 13px);
  color: var(--mc-text-secondary, #665245);
}
@keyframes preview-spin {
  to { transform: rotate(360deg); }
}
</style>
