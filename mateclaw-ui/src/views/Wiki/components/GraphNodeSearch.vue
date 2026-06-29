<template>
  <div class="gns">
    <div class="gns__box">
      <svg class="gns__icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="11" cy="11" r="7" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
      </svg>
      <input
        ref="inputEl"
        v-model="q"
        class="gns__input"
        :placeholder="t('wiki.graph.searchPlaceholder')"
        spellcheck="false"
        @focus="open = true"
        @input="onInput"
        @keydown.down.prevent="move(1)"
        @keydown.up.prevent="move(-1)"
        @keydown.enter.prevent="choose(active)"
        @keydown.esc.prevent="onEsc"
        @blur="onBlur"
      />
      <button v-if="q" class="gns__clear" :title="t('wiki.graph.searchClear')" @mousedown.prevent="clearQuery">×</button>
    </div>

    <ul v-if="open && hasQuery && matches.length" class="gns__list">
      <li
        v-for="(m, i) in matches"
        :key="m.id"
        class="gns__item"
        :class="{ 'is-active': i === active }"
        @mousedown.prevent="choose(i)"
        @mousemove="active = i"
      >
        <span class="gns__dot" :style="{ background: m.color || 'var(--mc-text-tertiary)' }" />
        <span class="gns__name">{{ m.name }}</span>
        <span v-if="m.type" class="gns__type">{{ m.type }}</span>
      </li>
    </ul>
    <div v-else-if="open && hasQuery && !matches.length" class="gns__empty">
      {{ t('wiki.graph.searchNoMatch') }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'

interface SearchNode { id: string; name: string; type?: string; color?: string }

const props = defineProps<{ nodes: SearchNode[] }>()
const emit = defineEmits<{
  (e: 'focus', id: string): void
  (e: 'clear'): void
}>()

const { t } = useI18n()

const q = ref('')
const open = ref(false)
const active = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

const hasQuery = computed(() => q.value.trim().length > 0)

// Substring match, ranked: exact name > prefix > earliest-occurring > shortest >
// alphabetical. Capped so the dropdown stays small on large graphs.
const matches = computed<SearchNode[]>(() => {
  const query = q.value.trim().toLowerCase()
  if (!query) return []
  const scored: { n: SearchNode; rank: number; idx: number; len: number }[] = []
  for (const n of props.nodes) {
    const name = (n.name || '').toLowerCase()
    const idx = name.indexOf(query)
    if (idx < 0) continue
    const rank = name === query ? 0 : idx === 0 ? 1 : 2
    scored.push({ n, rank, idx, len: name.length })
  }
  scored.sort((a, b) => a.rank - b.rank || a.idx - b.idx || a.len - b.len || a.n.name.localeCompare(b.n.name))
  return scored.slice(0, 10).map(s => s.n)
})

function onInput() {
  open.value = true
  active.value = 0
}

function move(d: number) {
  if (!matches.value.length) return
  open.value = true
  active.value = (active.value + d + matches.value.length) % matches.value.length
}

function choose(i: number) {
  const m = matches.value[i]
  if (!m) return
  q.value = m.name
  open.value = false
  emit('focus', m.id)
}

function clearQuery() {
  q.value = ''
  open.value = false
  active.value = 0
  emit('clear')
  inputEl.value?.focus()
}

function onEsc() {
  if (open.value && hasQuery.value) open.value = false
  else clearQuery()
}

// Close the dropdown after a click lands; the items use mousedown.prevent so a
// pick still registers before this fires.
function onBlur() {
  setTimeout(() => { open.value = false }, 120)
}
</script>

<style scoped>
.gns {
  position: relative;
  width: 220px;
  max-width: 60vw;
  font-size: 12.5px;
}
.gns__box {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-radius: 8px;
  background: var(--mc-bg-elevated, #fff);
  border: 1px solid var(--mc-border, #e5e7eb);
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.08);
}
.gns__icon {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
}
.gns__input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--mc-text-primary);
  font-size: 12.5px;
}
.gns__input::placeholder { color: var(--mc-text-quaternary, #b3a395); }
.gns__clear {
  flex-shrink: 0;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  color: var(--mc-text-tertiary);
  padding: 0 2px;
}
.gns__clear:hover { color: var(--mc-text-secondary); }

.gns__list {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  margin: 0;
  padding: 4px;
  list-style: none;
  max-height: 260px;
  overflow-y: auto;
  border-radius: 8px;
  background: var(--mc-bg-elevated, #fff);
  border: 1px solid var(--mc-border, #e5e7eb);
  box-shadow: 0 6px 22px rgba(0, 0, 0, 0.14);
  z-index: 2;
}
.gns__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
}
.gns__item.is-active { background: var(--mc-bg-hover, #f3f4f6); }
.gns__dot {
  flex-shrink: 0;
  width: 9px;
  height: 9px;
  border-radius: 50%;
}
.gns__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--mc-text-primary);
}
.gns__type {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}
.gns__empty {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--mc-bg-elevated, #fff);
  border: 1px solid var(--mc-border, #e5e7eb);
  box-shadow: 0 6px 22px rgba(0, 0, 0, 0.14);
  color: var(--mc-text-tertiary);
  font-size: 12px;
  z-index: 2;
}
</style>
