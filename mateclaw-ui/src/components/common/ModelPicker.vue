<template>
  <div ref="triggerRef" class="model-picker">
    <button
      type="button"
      class="model-picker__trigger"
      :class="{ 'is-open': open, 'is-empty': !selectedOption, 'is-disabled': disabled }"
      :disabled="disabled"
      @click="toggle"
    >
      <span class="model-picker__trigger-text">
        <template v-if="selectedOption">
          <span class="model-picker__provider">{{ selectedOption.provider }}</span>
          <span class="model-picker__sep">/</span>
          <span class="model-picker__model-name">{{ selectedOption.modelName }}</span>
        </template>
        <template v-else>{{ placeholder }}</template>
      </span>
      <span v-if="clearable && selectedOption && !disabled" class="model-picker__clear" @click.stop="clear">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <line x1="18" y1="6" x2="6" y2="18"/>
          <line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </span>
      <svg class="model-picker__caret" :class="{ open }" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </button>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="open" class="model-picker__backdrop" @click="open = false"></div>
      </Transition>

      <Transition name="picker-pop">
        <div
          v-if="open"
          ref="popRef"
          class="model-picker__pop"
          :style="popStyle"
          role="listbox"
        >
          <div v-if="searchable && totalCount > 5" class="model-picker__search">
            <svg class="model-picker__search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8"/>
              <line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input
              ref="searchRef"
              v-model="query"
              class="model-picker__search-input"
              :placeholder="searchPlaceholder || t('common.search')"
              @keydown.esc.stop="open = false"
            />
          </div>

          <div class="model-picker__list">
            <template v-for="group in filteredGroups" :key="group.provider">
              <div class="model-picker__group-header">{{ group.provider }}</div>
              <button
                v-for="m in group.items"
                :key="m.id"
                type="button"
                class="model-picker__item"
                :class="{ active: String(m.id) === String(modelValue) }"
                role="option"
                :aria-selected="String(m.id) === String(modelValue)"
                @click="select(m)"
              >
                <span class="model-picker__item-name">{{ m.modelName }}</span>
                <span v-if="m.name && m.name !== m.modelName" class="model-picker__item-alias">{{ m.name }}</span>
                <span v-if="badgeText && m.modalityCapable" class="model-picker__item-badge">{{ badgeText }}</span>
                <svg
                  v-if="String(m.id) === String(modelValue)"
                  class="model-picker__check"
                  width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
                >
                  <polyline points="20 6 9 17 4 12"/>
                </svg>
              </button>
            </template>

            <div v-if="filteredGroups.length === 0" class="model-picker__empty">
              {{ query.trim() ? t('common.noResults') : (emptyText || t('common.noOptions')) }}
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

interface ModelOption {
  id: string | number
  name?: string
  provider: string
  modelName: string
  /** When true and `badgeText` is set, the item renders a small capability badge. */
  modalityCapable?: boolean
}

const props = withDefaults(defineProps<{
  /** v-model value: the selected model id (string | number | null). */
  modelValue: string | number | null
  /** Available options. Grouped internally by `provider`. */
  models: ModelOption[]
  /** Trigger placeholder shown when no model is selected. */
  placeholder?: string
  /** Text inside the popover when no models match the search / there are no models at all. */
  emptyText?: string
  /** Search box placeholder. Defaults to common.search. */
  searchPlaceholder?: string
  /** Show search box when total options exceed 5. */
  searchable?: boolean
  /** Show inline ✕ button on the trigger to clear the current selection. */
  clearable?: boolean
  /** Render trigger in disabled state (no popover, no clear). */
  disabled?: boolean
  /** When set, items whose `modalityCapable` is true show this short badge label. */
  badgeText?: string
}>(), {
  searchable: true,
  clearable: true,
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string | number | null]
  change: [value: string | number | null, option: ModelOption | null]
}>()

const { t } = useI18n()

const open = ref(false)
const query = ref('')
const triggerRef = ref<HTMLElement | null>(null)
const popRef = ref<HTMLElement | null>(null)
const searchRef = ref<HTMLInputElement | null>(null)
const popStyle = ref<Record<string, string>>({})

const selectedOption = computed<ModelOption | null>(() => {
  if (props.modelValue === null || props.modelValue === undefined || props.modelValue === '') return null
  return props.models.find(m => String(m.id) === String(props.modelValue)) || null
})

const totalCount = computed(() => props.models.length)

interface ProviderGroup { provider: string; items: ModelOption[] }

const filteredGroups = computed<ProviderGroup[]>(() => {
  const q = query.value.trim().toLowerCase()
  const groups = new Map<string, ModelOption[]>()
  for (const m of props.models) {
    if (q) {
      const hay = (m.provider + ' ' + m.modelName + ' ' + (m.name || '')).toLowerCase()
      if (!hay.includes(q)) continue
    }
    if (!groups.has(m.provider)) groups.set(m.provider, [])
    groups.get(m.provider)!.push(m)
  }
  return [...groups.entries()].map(([provider, items]) => ({ provider, items }))
})

function toggle() {
  if (props.disabled) return
  open.value = !open.value
}

function select(model: ModelOption) {
  emit('update:modelValue', model.id)
  emit('change', model.id, model)
  open.value = false
}

function clear() {
  if (props.disabled) return
  emit('update:modelValue', null)
  emit('change', null, null)
}

function position() {
  const el = triggerRef.value
  if (!el) return
  const r = el.getBoundingClientRect()
  // Anchor below the trigger; if there isn't enough room, flip above.
  const popHeight = 320
  const spaceBelow = window.innerHeight - r.bottom
  const flip = spaceBelow < popHeight && r.top > popHeight
  popStyle.value = {
    position: 'fixed',
    left: `${r.left}px`,
    top: flip ? `${r.top - popHeight - 4}px` : `${r.bottom + 4}px`,
    width: `${Math.max(r.width, 320)}px`,
    zIndex: '2200',
  }
}

watch(open, (val) => {
  if (val) {
    query.value = ''
    nextTick(() => {
      position()
      searchRef.value?.focus()
    })
    window.addEventListener('resize', position)
    window.addEventListener('scroll', position, true)
  } else {
    window.removeEventListener('resize', position)
    window.removeEventListener('scroll', position, true)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', position)
  window.removeEventListener('scroll', position, true)
})
</script>

<style scoped>
.model-picker {
  position: relative;
  width: 100%;
}

.model-picker__trigger {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  text-align: left;
  transition: border-color 120ms ease, box-shadow 120ms ease;
}
.model-picker__trigger:hover:not(.is-disabled):not(.is-open) {
  border-color: color-mix(in srgb, var(--mc-primary) 50%, var(--mc-border));
}
.model-picker__trigger.is-open {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 15%, transparent);
}
.model-picker__trigger.is-disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.model-picker__trigger.is-empty .model-picker__trigger-text {
  color: var(--mc-text-tertiary);
  font-family: inherit;
}

.model-picker__trigger-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
}
.model-picker__provider {
  color: var(--mc-text-secondary);
}
.model-picker__sep {
  margin: 0 4px;
  color: var(--mc-text-tertiary);
}
.model-picker__model-name {
  color: var(--mc-text-primary);
  font-weight: 500;
}

.model-picker__clear {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: var(--mc-text-tertiary);
  transition: background 120ms ease, color 120ms ease;
}
.model-picker__clear:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}

.model-picker__caret {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
  transition: transform 120ms ease;
}
.model-picker__caret.open {
  transform: rotate(180deg);
  color: var(--mc-primary);
}

.model-picker__backdrop {
  position: fixed;
  inset: 0;
  z-index: 2199;
  background: transparent;
}

.model-picker__pop {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.15);
  display: flex;
  flex-direction: column;
  max-height: 320px;
  overflow: hidden;
}

.model-picker__search {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--mc-border);
  background: var(--mc-bg-sunken);
}
.model-picker__search-icon {
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
}
.model-picker__search-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: var(--mc-text-primary);
}
.model-picker__search-input::placeholder {
  color: var(--mc-text-tertiary);
}

.model-picker__list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}

.model-picker__group-header {
  padding: 6px 12px 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.model-picker__item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border: none;
  background: transparent;
  color: var(--mc-text-primary);
  cursor: pointer;
  font-size: 13px;
  text-align: left;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  transition: background 80ms ease;
}
.model-picker__item:hover {
  background: var(--mc-bg-sunken);
}
.model-picker__item.active {
  background: color-mix(in srgb, var(--mc-primary) 8%, transparent);
  color: var(--mc-primary);
}
.model-picker__item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model-picker__item-alias {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  font-family: inherit;
}
.model-picker__item-badge {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 999px;
  font-family: inherit;
  letter-spacing: 0.02em;
  background: color-mix(in srgb, var(--mc-primary) 12%, transparent);
  color: var(--mc-primary);
}
.model-picker__check {
  flex-shrink: 0;
  color: var(--mc-primary);
}

.model-picker__empty {
  padding: 24px 12px;
  text-align: center;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

/* Transitions */
.fade-enter-active, .fade-leave-active { transition: opacity 120ms ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.picker-pop-enter-active, .picker-pop-leave-active { transition: opacity 140ms ease, transform 140ms ease; }
.picker-pop-enter-from, .picker-pop-leave-to { opacity: 0; transform: translateY(-4px); }
</style>
