<script lang="ts">
export interface DropdownMenuItem {
  /** Identifier emitted via @select. Omit for dividers. */
  key?: string
  label?: string
  /** Render with the danger (destructive) color. */
  danger?: boolean
  /** Render a thin separator line instead of a clickable row. */
  divider?: boolean
  /** Disable the row. */
  disabled?: boolean
}
</script>

<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'

const props = withDefaults(defineProps<{
  /** Whether the menu is shown. */
  open: boolean
  /** Trigger element the menu anchors to. */
  anchor: HTMLElement | null
  items: DropdownMenuItem[]
  /** Horizontal alignment of the menu against the anchor. */
  align?: 'left' | 'right'
  /** Menu width in px. */
  width?: number
}>(), {
  align: 'right',
  width: 188,
})

const emit = defineEmits<{
  (e: 'select', item: DropdownMenuItem): void
  (e: 'close'): void
}>()

const menuStyle = ref<Record<string, string>>({})

/** Position the menu against the anchor, flipping above when it would overflow. */
function reposition() {
  const anchor = props.anchor
  if (!anchor) return
  const rect = anchor.getBoundingClientRect()
  const rows = props.items.filter(i => !i.divider).length
  const dividers = props.items.filter(i => i.divider).length
  const estHeight = rows * 38 + dividers * 5 + 8

  let left = props.align === 'right' ? rect.right - props.width : rect.left
  left = Math.max(8, Math.min(left, window.innerWidth - props.width - 8))

  let top = rect.bottom + 4
  if (top + estHeight > window.innerHeight - 8) top = rect.top - estHeight - 4
  top = Math.max(8, top)

  menuStyle.value = { top: `${top}px`, left: `${left}px`, width: `${props.width}px` }
}

function onSelect(item: DropdownMenuItem) {
  if (item.disabled) return
  emit('select', item)
  emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}

// A scroll or resize while open detaches the menu from its anchor — close it.
function onViewportChange() {
  emit('close')
}

watch(() => props.open, (isOpen) => {
  if (isOpen) {
    reposition()
    window.addEventListener('keydown', onKeydown)
    window.addEventListener('scroll', onViewportChange, true)
    window.addEventListener('resize', onViewportChange)
  } else {
    window.removeEventListener('keydown', onKeydown)
    window.removeEventListener('scroll', onViewportChange, true)
    window.removeEventListener('resize', onViewportChange)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('scroll', onViewportChange, true)
  window.removeEventListener('resize', onViewportChange)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="dropdown-fade">
      <div
        v-if="open"
        class="dropdown-backdrop"
        @click="emit('close')"
        @contextmenu.prevent="emit('close')"
      ></div>
    </Transition>
    <Transition name="dropdown-pop">
      <div v-if="open" class="dropdown-menu" :style="menuStyle">
        <template v-for="(item, i) in items" :key="item.key ?? `_d${i}`">
          <div v-if="item.divider" class="dropdown-divider"></div>
          <button
            v-else
            class="dropdown-item"
            :class="{ 'is-danger': item.danger, 'is-disabled': item.disabled }"
            :disabled="item.disabled"
            @click="onSelect(item)"
          >
            <span v-if="$slots['item-icon']" class="dropdown-item-icon">
              <slot name="item-icon" :item="item" />
            </span>
            <span class="dropdown-item-label">{{ item.label }}</span>
          </button>
        </template>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.dropdown-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
}

.dropdown-menu {
  position: fixed;
  z-index: 1001;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  padding: 4px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.14);
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 9px 11px;
  border: none;
  background: none;
  border-radius: 8px;
  font-size: 13px;
  color: var(--mc-text-primary);
  cursor: pointer;
  transition: background 0.12s, color 0.12s;
}

.dropdown-item:hover {
  background: var(--mc-bg-sunken);
}

.dropdown-item.is-danger {
  color: var(--mc-danger);
}

.dropdown-item.is-danger:hover {
  background: var(--mc-danger-bg);
}

.dropdown-item.is-disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.dropdown-item.is-disabled:hover {
  background: none;
}

.dropdown-item-icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
}

.dropdown-item.is-danger .dropdown-item-icon {
  color: var(--mc-danger);
}

.dropdown-item-label {
  flex: 1;
  text-align: left;
}

.dropdown-divider {
  height: 1px;
  background: var(--mc-border-light);
  margin: 2px 8px;
}

.dropdown-fade-enter-active,
.dropdown-fade-leave-active {
  transition: opacity 0.12s ease;
}
.dropdown-fade-enter-from,
.dropdown-fade-leave-to {
  opacity: 0;
}

.dropdown-pop-enter-active {
  transition: all 0.13s ease-out;
}
.dropdown-pop-leave-active {
  transition: all 0.1s ease-in;
}
.dropdown-pop-enter-from {
  opacity: 0;
  transform: translateY(-6px) scale(0.97);
}
.dropdown-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.98);
}
</style>
