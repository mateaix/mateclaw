<template>
  <div class="mc-toast" :class="`mc-toast--${type}`" role="status" aria-live="polite">
    <span class="mc-toast__indicator">
      <svg
        v-if="type === 'success'"
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <polyline points="20 6 9 17 4 12" />
      </svg>
      <svg
        v-else-if="type === 'error'"
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <line x1="18" y1="6" x2="6" y2="18" />
        <line x1="6" y1="6" x2="18" y2="18" />
      </svg>
      <svg
        v-else-if="type === 'warning'"
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.6"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
        <line x1="12" y1="9" x2="12" y2="13" />
        <circle cx="12" cy="17" r="0.6" />
      </svg>
      <svg
        v-else
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.6"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <circle cx="12" cy="12" r="9" />
        <line x1="12" y1="11" x2="12" y2="16" />
        <circle cx="12" cy="8" r="0.6" />
      </svg>
    </span>
    <span class="mc-toast__message">{{ message }}</span>
  </div>
</template>

<script setup lang="ts">
// Single frosted toast row. Layout, animation, and stacking live in
// useMcToast — this component just renders one row with the right
// status accent.
defineProps<{
  type: 'success' | 'error' | 'warning' | 'info'
  message: string
}>()
</script>

<style scoped>
.mc-toast {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px 10px 12px;
  border-radius: 14px;
  background: rgba(255, 250, 245, 0.82);
  backdrop-filter: blur(28px) saturate(180%);
  -webkit-backdrop-filter: blur(28px) saturate(180%);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.6) inset,
    0 8px 28px rgba(25, 14, 8, 0.18);
  color: var(--mc-text-primary);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: -0.005em;
  max-width: min(440px, calc(100vw - 32px));
  pointer-events: auto;
}
:global(html.dark .mc-toast) {
  background: rgba(32, 26, 22, 0.86);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.06) inset,
    0 8px 28px rgba(0, 0, 0, 0.45);
}

.mc-toast__indicator {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.mc-toast--success .mc-toast__indicator {
  background: var(--mc-success);
  box-shadow: 0 0 0 4px rgba(90, 138, 90, 0.16);
}
.mc-toast--error .mc-toast__indicator {
  background: var(--mc-danger);
  box-shadow: 0 0 0 4px rgba(200, 60, 60, 0.16);
}
.mc-toast--warning .mc-toast__indicator {
  background: var(--mc-primary);
  box-shadow: 0 0 0 4px rgba(217, 119, 87, 0.18);
}
.mc-toast--info .mc-toast__indicator {
  background: var(--mc-text-tertiary);
  box-shadow: 0 0 0 4px rgba(0, 0, 0, 0.06);
}
:global(html.dark .mc-toast--info .mc-toast__indicator) {
  box-shadow: 0 0 0 4px rgba(255, 255, 255, 0.06);
}

.mc-toast__message {
  line-height: 1.5;
  word-break: break-word;
}
</style>
