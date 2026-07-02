<template>
  <div class="cap-card">
    <div class="cap-head">
      <svg v-if="kind === 'skills'" class="cap-head-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><path d="M12 3l9 5-9 5-9-5 9-5z"/><path d="M3 13l9 5 9-5"/></svg>
      <svg v-else class="cap-head-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="8" x2="20" y2="8"/><line x1="4" y1="16" x2="20" y2="16"/><circle cx="9" cy="8" r="2"/><circle cx="15" cy="16" r="2"/></svg>
      <span class="cap-title">{{ title }}</span>
      <span v-if="badge" class="cap-badge">{{ badge }}</span>
      <button v-if="items.length" type="button" class="cap-add" @click="open = !open">
        {{ open ? collapseLabel : addLabel }}
      </button>
    </div>

    <!-- The answer first: selected capabilities as compact removable chips. -->
    <div v-if="selectedItems.length" class="cap-chips">
      <span v-for="it in selectedItems" :key="it.key" class="cap-chip" :title="it.desc || it.name">
        {{ it.name }}
        <button type="button" class="cap-chip-x" :aria-label="removeLabel" @click="remove(it.key)">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </span>
    </div>
    <p v-else class="cap-empty">{{ emptyText }}</p>

    <!-- The menu, on demand: a searchable wrap of toggle chips. -->
    <div v-if="open" class="cap-catalog">
      <input v-model="search" class="cap-search" :placeholder="searchPlaceholder" />
      <div class="cap-picks">
        <button
          v-for="it in filtered"
          :key="it.key"
          type="button"
          class="cap-pick"
          :class="{ on: isSelected(it.key) }"
          :title="it.desc || it.name"
          @click="toggle(it.key)"
        >{{ it.name }}</button>
        <p v-if="!filtered.length" class="cap-empty cap-empty--compact">{{ emptyText }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

interface CapabilityItem {
  /** Stable identity used both as the v-model value and the list key. */
  key: string
  name: string
  desc?: string
  /** Tools only: reserved for source-specific affordances. */
  mcp?: boolean
}

const props = defineProps<{
  /** Selected keys (v-model). Strings to stay clear of Snowflake precision loss. */
  modelValue: string[]
  /** Full catalog to choose from. */
  items: CapabilityItem[]
  /** Drives the header glyph. */
  kind: 'skills' | 'tools'
  title: string
  /** Pre-formatted "N selected" text; empty hides the badge. */
  badge?: string
  emptyText: string
  addLabel: string
  collapseLabel: string
  searchPlaceholder: string
  removeLabel?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const open = ref(false)
const search = ref('')

const selectedKeys = computed(() => new Set(props.modelValue.map(String)))
function isSelected(key: string): boolean {
  return selectedKeys.value.has(String(key))
}

const selectedItems = computed(() => props.items.filter((it) => isSelected(it.key)))

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return props.items
  return props.items.filter((it) => `${it.name} ${it.desc ?? ''}`.toLowerCase().includes(q))
})

function remove(key: string) {
  emit('update:modelValue', props.modelValue.filter((k) => String(k) !== String(key)))
}

function toggle(key: string) {
  if (isSelected(key)) remove(key)
  else emit('update:modelValue', [...props.modelValue, key])
}
</script>

<style scoped>
.cap-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px;
  padding: 16px 18px; box-shadow: var(--mc-shadow-soft); margin-bottom: 14px; }

.cap-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.cap-head-icon { width: 17px; height: 17px; color: var(--mc-primary); flex-shrink: 0; }
.cap-title { font-size: 14px; font-weight: 700; color: var(--mc-text-primary); }
.cap-badge { font-size: 11px; font-weight: 600; color: var(--mc-primary-hover); background: var(--mc-primary-bg);
  padding: 2px 8px; border-radius: 999px; }
.cap-add { margin-left: auto; padding: 4px 10px; background: transparent; border: 1px solid var(--mc-border);
  border-radius: 999px; font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); cursor: pointer;
  font-family: inherit; }
.cap-add:hover { border-color: var(--mc-primary); color: var(--mc-primary); }

/* Selected set — compact filled chips that wrap. */
.cap-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.cap-chip { display: inline-flex; align-items: center; gap: 4px; padding: 4px 6px 4px 11px;
  background: var(--mc-primary-bg); border: 1px solid var(--mc-primary-light); border-radius: 999px;
  font-size: 13px; font-weight: 600; color: var(--mc-primary-hover); max-width: 100%; }
.cap-chip-x { display: inline-flex; align-items: center; justify-content: center; width: 16px; height: 16px;
  padding: 0; border: none; background: transparent; color: var(--mc-primary-hover); cursor: pointer; opacity: 0.7; }
.cap-chip-x:hover { opacity: 1; }
.cap-chip-x svg { width: 12px; height: 12px; }

.cap-empty { font-size: 13px; color: var(--mc-text-tertiary); margin: 2px 0 0; }
.cap-empty--compact { padding: 4px 2px; width: 100%; }

/* On-demand catalog — outline toggle chips, selected ones filled. */
.cap-catalog { margin-top: 12px; }
.cap-search { width: 100%; box-sizing: border-box; padding: 8px 12px; border: 1px solid var(--mc-border);
  border-radius: 10px; background: var(--mc-input-bg); font-size: 14px; color: var(--mc-text-primary);
  font-family: inherit; outline: none; margin-bottom: 10px; }
.cap-search:focus { border-color: var(--mc-primary); }
.cap-picks { display: flex; flex-wrap: wrap; gap: 8px; max-height: 168px; overflow-y: auto; }
.cap-pick { padding: 5px 12px; background: transparent; border: 1px solid var(--mc-border-light);
  border-radius: 999px; font-size: 13px; color: var(--mc-text-secondary); cursor: pointer; font-family: inherit;
  white-space: nowrap; }
.cap-pick:hover { border-color: var(--mc-border-strong); color: var(--mc-text-primary); }
.cap-pick.on { background: var(--mc-primary-bg); border-color: var(--mc-primary-light); color: var(--mc-primary-hover);
  font-weight: 600; }
</style>
