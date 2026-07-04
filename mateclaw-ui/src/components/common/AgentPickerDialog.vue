<template>
  <div
    class="mc-agent-picker"
    :class="{ 'is-block': block, 'is-disabled': disabled }"
  >
    <button
      type="button"
      class="mc-agent-picker__trigger"
      :class="{
        'is-compact': compact,
        'is-empty': !selectedAgent && !isUnknown,
        'is-unknown': isUnknown,
        'is-disabled': disabled,
      }"
      :disabled="disabled"
      :title="triggerLabel"
      @click="openDialog"
    >
      <SkillIcon
        class="mc-agent-picker__trigger-icon"
        :style="{ color: agentIconColor(selectedAgent?.icon) }"
        :value="selectedAgent?.icon"
        :size="compact ? 24 : 22"
        fallback="🤖"
      />
      <template v-if="!compact">
        <span class="mc-agent-picker__trigger-name">{{ triggerLabel }}</span>
        <span
          v-if="clearable && (selectedAgent || isUnknown) && !disabled"
          class="mc-agent-picker__clear"
          role="button"
          :aria-label="t('common.clear')"
          @click.stop="clear"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </span>
        <svg
          class="mc-agent-picker__caret"
          width="12" height="12" viewBox="0 0 24 24"
          fill="none" stroke="currentColor" stroke-width="2"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </template>
    </button>

    <Teleport to="body">
      <Transition name="mc-agent-picker-fade">
        <div v-if="open" class="mc-agent-picker__overlay" @click.self="close">
          <div class="mc-agent-picker__panel" role="dialog" aria-modal="true">
            <div class="mc-agent-picker__head">
              <h2 class="mc-agent-picker__title">{{ t('agentContext.selectAgent') }}</h2>
              <button class="mc-agent-picker__close" :aria-label="t('common.close')" @click="close">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>

            <div v-if="searchable" class="mc-agent-picker__search">
              <svg
                class="mc-agent-picker__search-icon"
                width="14" height="14" viewBox="0 0 24 24"
                fill="none" stroke="currentColor" stroke-width="2"
              >
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              <input
                ref="searchRef"
                v-model="query"
                class="mc-agent-picker__search-input"
                :placeholder="t('common.search')"
                @keydown.esc.stop="close"
              />
            </div>

            <div class="mc-agent-picker__body">
              <button
                v-if="clearable && !query.trim()"
                type="button"
                class="mc-agent-picker__item mc-agent-picker__item--none"
                :class="{ active: !selectedAgent && !isUnknown }"
                @click="clear"
              >
                <span class="mc-agent-picker__none-icon">∅</span>
                <div class="mc-agent-picker__item-info">
                  <span class="mc-agent-picker__item-name">{{ t('common.none') }}</span>
                </div>
                <svg
                  v-if="!selectedAgent && !isUnknown"
                  class="mc-agent-picker__check"
                  width="16" height="16" viewBox="0 0 24 24"
                  fill="none" stroke="currentColor" stroke-width="2.5"
                >
                  <polyline points="20 6 9 17 4 12" />
                </svg>
              </button>

              <button
                v-for="agent in filteredAgents"
                :key="agent.id"
                type="button"
                class="mc-agent-picker__item"
                :class="{ active: isSelected(agent) }"
                @click="select(agent)"
              >
                <SkillIcon
                  class="mc-agent-picker__item-icon"
                  :style="{ color: agentIconColor(agent.icon) }"
                  :value="agent.icon"
                  :size="28"
                  fallback="🤖"
                />
                <div class="mc-agent-picker__item-info">
                  <span class="mc-agent-picker__item-name">{{ agent.name }}</span>
                  <span v-if="subtitle(agent)" class="mc-agent-picker__item-desc">{{ subtitle(agent) }}</span>
                </div>
                <svg
                  v-if="isSelected(agent)"
                  class="mc-agent-picker__check"
                  width="16" height="16" viewBox="0 0 24 24"
                  fill="none" stroke="currentColor" stroke-width="2.5"
                >
                  <polyline points="20 6 9 17 4 12" />
                </svg>
              </button>

              <div v-if="filteredAgents.length === 0" class="mc-agent-picker__empty">
                {{ query.trim() ? t('common.noResults') : t('common.noOptions') }}
              </div>
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
import SkillIcon from '@/components/common/SkillIcon.vue'
import { agentIconColor } from '@/utils/agentIconColor'

/** Minimal structural shape so this picker works with the full `Agent`
 *  entity as well as the lighter agent option lists (e.g. workflow steps). */
export interface PickableAgent {
  id: string | number
  name: string
  icon?: string | null
  description?: string
  agentType?: string
  title?: string
}

const props = withDefaults(defineProps<{
  /** v-model value: the selected agent id. Kept as a string to preserve Snowflake precision. */
  modelValue: string | number | null
  /** Agents to choose from. */
  agents: PickableAgent[]
  /** Trigger label shown when no agent is selected. Defaults to agentContext.selectAgent. */
  placeholder?: string
  /** Render trigger in disabled state (no dialog). */
  disabled?: boolean
  /** Icon-only trigger — no name, no caret. For collapsed sidebars. */
  compact?: boolean
  /** Full-width trigger, for form-field contexts. */
  block?: boolean
  /** Allow clearing the selection (shows a ✕ on the trigger and a "none" row). */
  clearable?: boolean
  /** Label shown when modelValue is set but the agent is not in `agents`
   *  (e.g. a workflow step referencing a since-deleted employee). */
  unknownLabel?: string
}>(), {
  disabled: false,
  compact: false,
  block: false,
  clearable: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string | number | null]
  change: [value: string | number | null, agent: PickableAgent | null]
}>()

const { t } = useI18n()

const open = ref(false)
const query = ref('')
const searchRef = ref<HTMLInputElement | null>(null)

const hasValue = computed(() =>
  props.modelValue !== '' && props.modelValue !== null && props.modelValue !== undefined,
)

const selectedAgent = computed<PickableAgent | null>(() => {
  if (!hasValue.value) return null
  return props.agents.find(a => String(a.id) === String(props.modelValue)) || null
})

/** modelValue is set but resolves to no known agent — the referenced
 *  employee was likely renamed or removed. */
const isUnknown = computed(() => hasValue.value && !selectedAgent.value)

const triggerLabel = computed(() => {
  if (selectedAgent.value) return selectedAgent.value.name
  if (isUnknown.value) return props.unknownLabel || String(props.modelValue)
  return props.placeholder || t('agentContext.selectAgent')
})

/** Show the search box once the list is long enough to warrant scanning. */
const searchable = computed(() => props.agents.length > 6)

const filteredAgents = computed<PickableAgent[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.agents
  return props.agents.filter(a =>
    `${a.name} ${a.description || ''} ${a.title || ''} ${a.agentType || ''}`.toLowerCase().includes(q),
  )
})

function subtitle(agent: PickableAgent): string {
  return agent.description || agent.title || agent.agentType || ''
}

function isSelected(agent: PickableAgent): boolean {
  return String(agent.id) === String(props.modelValue)
}

function openDialog() {
  if (props.disabled) return
  open.value = true
}

function close() {
  open.value = false
}

function select(agent: PickableAgent) {
  emit('update:modelValue', agent.id)
  emit('change', agent.id, agent)
  close()
}

function clear() {
  emit('update:modelValue', null)
  emit('change', null, null)
  close()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

watch(open, (val) => {
  if (val) {
    query.value = ''
    window.addEventListener('keydown', onKeydown)
    nextTick(() => searchRef.value?.focus())
  } else {
    window.removeEventListener('keydown', onKeydown)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.mc-agent-picker {
  display: inline-flex;
  min-width: 0;
}
.mc-agent-picker.is-block {
  display: flex;
  width: 100%;
}

/* ===== Trigger ===== */
.mc-agent-picker__trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 240px;
  padding: 9px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  font-size: 14px;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.is-block .mc-agent-picker__trigger {
  width: 100%;
  min-width: 0;
}
.mc-agent-picker__trigger.is-compact {
  min-width: 0;
  width: 100%;
  justify-content: center;
  padding: 8px;
}
.mc-agent-picker__trigger:hover:not(.is-disabled) {
  border-color: color-mix(in srgb, var(--mc-primary) 50%, var(--mc-border));
}
.mc-agent-picker__trigger:focus-visible {
  outline: none;
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 15%, transparent);
}
.mc-agent-picker__trigger.is-disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.mc-agent-picker__trigger-icon {
  flex-shrink: 0;
}
.mc-agent-picker__trigger-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mc-agent-picker__trigger.is-empty .mc-agent-picker__trigger-name {
  color: var(--mc-text-tertiary);
}
.mc-agent-picker__trigger.is-unknown .mc-agent-picker__trigger-name {
  color: var(--mc-warning, #f59e0b);
}
.mc-agent-picker__clear {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: var(--mc-text-tertiary);
  transition: background 0.12s ease, color 0.12s ease;
}
.mc-agent-picker__clear:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}
.mc-agent-picker__caret {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
}

/* ===== Dialog ===== */
.mc-agent-picker__overlay {
  position: fixed;
  inset: 0;
  z-index: 2400;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.4);
}

.mc-agent-picker__panel {
  width: 100%;
  max-width: 420px;
  max-height: 72vh;
  display: flex;
  flex-direction: column;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 18px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.22);
  animation: mc-agent-picker-pop 0.26s cubic-bezier(0.32, 0.72, 0, 1.2);
}
@keyframes mc-agent-picker-pop {
  from { opacity: 0; transform: scale(0.94) translateY(6px); }
  to   { opacity: 1; transform: scale(1) translateY(0); }
}

.mc-agent-picker-fade-enter-active,
.mc-agent-picker-fade-leave-active { transition: opacity 0.18s ease; }
.mc-agent-picker-fade-enter-from,
.mc-agent-picker-fade-leave-to { opacity: 0; }

.mc-agent-picker__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px 12px;
  border-bottom: 1px solid var(--mc-border-light);
}
.mc-agent-picker__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--mc-text-primary);
}
.mc-agent-picker__close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.18s ease, color 0.18s ease;
}
.mc-agent-picker__close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }

.mc-agent-picker__search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 16px 0;
  padding: 7px 10px;
  border: 1px solid var(--mc-border);
  border-radius: 9px;
  background: var(--mc-bg-sunken);
}
.mc-agent-picker__search-icon { color: var(--mc-text-tertiary); flex-shrink: 0; }
.mc-agent-picker__search-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: var(--mc-text-primary);
}
.mc-agent-picker__search-input::placeholder { color: var(--mc-text-tertiary); }

.mc-agent-picker__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px;
}
.mc-agent-picker__body::-webkit-scrollbar { width: 4px; }
.mc-agent-picker__body::-webkit-scrollbar-thumb { background: var(--mc-border); border-radius: 2px; }

.mc-agent-picker__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background 0.12s ease;
}
.mc-agent-picker__item:hover { background: var(--mc-bg-sunken); }
.mc-agent-picker__item.active {
  background: var(--mc-primary-bg);
}
.mc-agent-picker__item-icon { flex-shrink: 0; }
.mc-agent-picker__none-icon {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  color: var(--mc-text-tertiary);
}
.mc-agent-picker__item-info {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}
.mc-agent-picker__item-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mc-agent-picker__item-desc {
  font-size: 11.5px;
  color: var(--mc-text-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mc-agent-picker__check { flex-shrink: 0; color: var(--mc-primary); }

.mc-agent-picker__empty {
  padding: 28px 12px;
  text-align: center;
  font-size: 13px;
  color: var(--mc-text-tertiary);
}

@media (max-width: 600px) {
  .mc-agent-picker__panel { max-width: 100%; max-height: 86vh; }
}
</style>
