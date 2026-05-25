<template>
  <Teleport to="body">
    <Transition name="mc-drawer-fade">
      <div
        v-if="visible"
        class="mc-drawer-overlay"
        :class="overlayClass"
        @click.self="emit('close')"
      >
        <div class="mc-drawer-panel" :class="panelClass">
          <div class="mc-drawer-header">
            <div class="mc-drawer-header__meta">
              <span v-if="$slots.icon" class="mc-drawer-icon-shell">
                <slot name="icon" />
              </span>
              <div class="mc-drawer-titles">
                <h3 class="mc-drawer-title">{{ title }}</h3>
                <p
                  v-if="subtitle || $slots.subtitle"
                  class="mc-drawer-subtitle"
                >
                  <slot name="subtitle">{{ subtitle }}</slot>
                </p>
              </div>
            </div>
            <button
              class="mc-drawer-close"
              :title="closeLabel"
              @click="emit('close')"
            >
              <svg
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.4"
              >
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>

          <div class="mc-drawer-body" :class="bodyClass">
            <slot />
          </div>

          <div v-if="$slots.footer" class="mc-drawer-footer">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'

// Frosted-glass right-side drawer with a spring slide, circular close,
// 40px icon hero, and a mobile bottom-sheet fallback. The shared visual
// language for every detail/edit/diagnostic surface in the app.
//
// Three sizes — sm (480) for compact status drawers, md (640) for
// detail/edit drawers, lg (880) for full takeover editors. The
// bodyClass escape hatch lets callers swap padding/overflow rules
// without bloating this component's API.
const props = withDefaults(
  defineProps<{
    visible: boolean
    title: string
    subtitle?: string
    size?: 'sm' | 'md' | 'lg'
    bodyClass?: string
    closeLabel?: string
  }>(),
  {
    subtitle: '',
    size: 'sm',
    bodyClass: '',
    closeLabel: 'Close',
  },
)

const emit = defineEmits<{ (e: 'close'): void }>()

const overlayClass = computed(() => ({
  'mc-drawer-overlay--md': props.size === 'md',
  'mc-drawer-overlay--lg': props.size === 'lg',
}))
const panelClass = computed(() => ({
  'mc-drawer-panel--md': props.size === 'md',
  'mc-drawer-panel--lg': props.size === 'lg',
}))
</script>

<style scoped>
.mc-drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(20, 14, 10, 0.32);
  backdrop-filter: blur(8px) saturate(140%);
  -webkit-backdrop-filter: blur(8px) saturate(140%);
  z-index: 1500;
  display: flex;
  justify-content: flex-end;
}
:global(html.dark .mc-drawer-overlay) {
  background: rgba(0, 0, 0, 0.5);
}

.mc-drawer-panel {
  width: 480px;
  max-width: 92vw;
  height: 100%;
  background: rgba(255, 250, 245, 0.78);
  backdrop-filter: blur(48px) saturate(180%);
  -webkit-backdrop-filter: blur(48px) saturate(180%);
  border-left: 1px solid rgba(255, 255, 255, 0.4);
  box-shadow: -24px 0 60px rgba(25, 14, 8, 0.16);
  display: flex;
  flex-direction: column;
  animation: mc-drawer-slide 0.36s cubic-bezier(0.32, 0.72, 0, 1);
  transition: width 0.32s cubic-bezier(0.32, 0.72, 0, 1);
}
.mc-drawer-panel--md {
  width: 640px;
}
.mc-drawer-panel--lg {
  width: 880px;
}
:global(html.dark .mc-drawer-panel) {
  background: rgba(32, 26, 22, 0.82);
  border-left-color: rgba(255, 255, 255, 0.10);
  box-shadow: -24px 0 60px rgba(0, 0, 0, 0.5);
}

@keyframes mc-drawer-slide {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}

.mc-drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 22px 26px 18px;
  border-bottom: 1px solid rgba(123, 88, 67, 0.10);
  flex-shrink: 0;
}
:global(html.dark .mc-drawer-header) {
  border-bottom-color: rgba(255, 255, 255, 0.06);
}
.mc-drawer-header__meta {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.mc-drawer-icon-shell {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.55);
  box-shadow: inset 0 0 0 1px rgba(123, 88, 67, 0.10);
  color: var(--mc-text-primary);
}
:global(html.dark .mc-drawer-icon-shell) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}
.mc-drawer-titles {
  min-width: 0;
}
.mc-drawer-title {
  margin: 0 0 2px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mc-drawer-subtitle {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--mc-text-tertiary);
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.mc-drawer-close {
  background: transparent;
  border: 0;
  padding: 8px;
  border-radius: 999px;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s ease, color 0.15s ease;
}
.mc-drawer-close:hover {
  background: rgba(123, 88, 67, 0.08);
  color: var(--mc-text-primary);
}
:global(html.dark .mc-drawer-close:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: var(--mc-text-primary);
}

/* Body fills the panel and provides a flex-column shell so consumers
 * can use `flex: 1` to stretch content (e.g. takeover editors).
 * Padding, gap, and inner layout are intentionally the consumer's
 * call — keeps the override surface zero. */
.mc-drawer-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.mc-drawer-footer {
  padding: 14px 22px 18px;
  border-top: 1px solid rgba(123, 88, 67, 0.10);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
}
:global(html.dark .mc-drawer-footer) {
  border-top-color: rgba(255, 255, 255, 0.06);
}

.mc-drawer-fade-enter-active,
.mc-drawer-fade-leave-active {
  transition: opacity 0.22s ease;
}
.mc-drawer-fade-enter-from,
.mc-drawer-fade-leave-to {
  opacity: 0;
}

@media (max-width: 768px) {
  .mc-drawer-overlay {
    justify-content: stretch;
    align-items: flex-end;
  }
  .mc-drawer-panel,
  .mc-drawer-panel--lg {
    width: 100%;
    max-width: 100%;
    max-height: 92vh;
    border-left: 0;
    border-top-left-radius: 18px;
    border-top-right-radius: 18px;
    animation: mc-drawer-sheet-up 0.36s cubic-bezier(0.32, 0.72, 0, 1);
  }
  @keyframes mc-drawer-sheet-up {
    from { transform: translateY(100%); }
    to { transform: translateY(0); }
  }
}
</style>
