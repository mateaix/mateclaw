<template>
  <div class="skill-slash-menu" role="listbox" :aria-label="t('chat.slashMenuTitle')">
    <div class="slash-menu__header">
      <span class="slash-menu__title">{{ t('chat.slashMenuTitle') }}</span>
      <span class="slash-menu__hint">{{ t('chat.slashMenuHint') }}</span>
    </div>

    <div class="slash-menu__search">
      <el-icon class="slash-menu__search-icon"><Search /></el-icon>
      <input
        ref="searchRef"
        v-model="keyword"
        class="slash-menu__search-input"
        type="text"
        autocomplete="off"
        spellcheck="false"
        :placeholder="t('chat.slashMenuSearchPlaceholder')"
        @keydown="onSearchKeydown"
        @blur="onSearchBlur"
      />
    </div>

    <div v-if="loading" class="slash-menu__state">{{ t('chat.slashMenuLoading') }}</div>
    <div v-else-if="filtered.length === 0" class="slash-menu__state">{{ t('chat.slashMenuEmpty') }}</div>

    <ul v-else class="slash-menu__list">
      <li
        v-for="(skill, idx) in filtered"
        :key="String(skill.id)"
        class="slash-menu__item"
        :class="{ active: idx === activeIndex }"
        role="option"
        :aria-selected="idx === activeIndex"
        @mouseenter="activeIndex = idx"
        @mousedown.prevent="choose(skill)"
      >
        <span class="slash-menu__icon"><SkillIcon :value="skill.icon" :size="18" :fallback="'🧩'" /></span>
        <span class="slash-menu__body">
          <span class="slash-menu__name">
            {{ displayName(skill) }}
            <code class="slash-menu__slug">{{ skill.name }}</code>
          </span>
          <span v-if="skill.description" class="slash-menu__desc">{{ skill.description }}</span>
        </span>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { Search } from '@element-plus/icons-vue'
import { skillApi } from '@/api/index'
import SkillIcon from '@/components/common/SkillIcon.vue'
import type { Skill } from '@/types/index'

// Short-lived, workspace-keyed cache. The menu remounts every time the user
// re-types "/", so without this each keystroke that re-opens it would re-hit
// the endpoint. Keyed by workspace because the listing is workspace-scoped.
const CACHE_TTL_MS = 30_000
let enabledCache: { key: string; ts: number; data: Skill[] } | null = null

async function loadEnabledSkills(): Promise<Skill[]> {
  const key = localStorage.getItem('mc-workspace-id') || 'default'
  const now = Date.now()
  if (enabledCache && enabledCache.key === key && now - enabledCache.ts < CACHE_TTL_MS) {
    return enabledCache.data
  }
  const res: any = await skillApi.listEnabled()
  const data = (res?.data ?? []) as Skill[]
  enabledCache = { key, ts: now, data }
  return data
}

const props = defineProps<{
  /** Initial query, seeded from the text typed after the leading "/". */
  query: string
}>()

const emit = defineEmits<{
  /** A skill was picked (click or Enter). */
  select: [skill: Skill]
  /** The menu requested to close (Escape, or focus left the menu). */
  close: []
}>()

const { t, locale } = useI18n()

const MAX_RESULTS = 8

const allSkills = ref<Skill[]>([])
const loading = ref(true)
const activeIndex = ref(0)

// The in-menu search box owns the filter. Seeded once from the slash query so
// "/da" carries the "da" into the box; afterwards it is edited independently
// (it tolerates spaces, which the slash trigger does not).
const searchRef = ref<HTMLInputElement | null>(null)
const keyword = ref(props.query)

const q = computed(() => keyword.value.trim().toLowerCase())

const filtered = computed<Skill[]>(() => {
  const query = q.value
  const list = allSkills.value
  const matched = query
    ? list.filter((s) =>
        [s.name, s.nameZh, s.nameEn, s.description].some(
          (f) => f && f.toLowerCase().includes(query),
        ),
      )
    : list
  return matched.slice(0, MAX_RESULTS)
})

// Keep the highlighted row in range as the query narrows the result set.
watch(filtered, () => {
  if (activeIndex.value >= filtered.value.length) activeIndex.value = 0
})

function displayName(s: Skill): string {
  const zh = locale.value.startsWith('zh')
  return (zh ? s.nameZh : s.nameEn) || s.name
}

function choose(skill: Skill) {
  emit('select', skill)
}

// ---- Keyboard API consumed by the parent textarea handler ----
function next() {
  if (filtered.value.length) activeIndex.value = (activeIndex.value + 1) % filtered.value.length
}
function prev() {
  if (filtered.value.length)
    activeIndex.value = (activeIndex.value - 1 + filtered.value.length) % filtered.value.length
}
function confirm() {
  const skill = filtered.value[activeIndex.value]
  if (skill) emit('select', skill)
}
function count() {
  return filtered.value.length
}
defineExpose({ next, prev, confirm, count })

// ---- Search box: owns focus and keyboard while the menu is open ----
function onSearchKeydown(e: KeyboardEvent) {
  switch (e.key) {
    case 'ArrowDown':
      e.preventDefault()
      next()
      break
    case 'ArrowUp':
      e.preventDefault()
      prev()
      break
    case 'Enter':
      if (filtered.value.length) {
        e.preventDefault()
        confirm()
      }
      break
    case 'Tab':
      if (filtered.value.length) {
        e.preventDefault()
        confirm()
      }
      break
    case 'Escape':
      e.preventDefault()
      emit('close')
      break
  }
}

// Close when focus leaves the menu entirely. Item clicks use
// `@mousedown.prevent`, so picking a skill never blurs the search box.
function onSearchBlur(e: FocusEvent) {
  const next = e.relatedTarget as HTMLElement | null
  if (next && next.closest && next.closest('.skill-slash-menu')) return
  emit('close')
}

onMounted(async () => {
  try {
    allSkills.value = await loadEnabledSkills()
  } catch {
    allSkills.value = []
  } finally {
    loading.value = false
  }
  // Move focus into the search box so typing filters immediately. The parent
  // textarea's blur handler detects the focus landing inside the menu and
  // keeps the menu open.
  await nextTick()
  const el = searchRef.value
  if (el) {
    el.focus()
    const end = el.value.length
    el.setSelectionRange(end, end)
  }
})
</script>

<style scoped>
.skill-slash-menu {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  right: 0;
  z-index: 30;
  background: var(--mc-input-bg, #ffffff);
  border-radius: 14px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.14), 0 0 0 1px rgba(0, 0, 0, 0.06);
  overflow: hidden;
  max-height: 320px;
  display: flex;
  flex-direction: column;
}

.slash-menu__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}

.slash-menu__title {
  font-weight: 600;
}

.slash-menu__hint {
  font-size: 11px;
  opacity: 0.8;
}

.slash-menu__search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px;
  padding: 6px 10px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.04);
  border: 1px solid transparent;
}

.slash-menu__search:focus-within {
  border-color: rgba(217, 119, 87, 0.5);
  background: var(--mc-input-bg, #ffffff);
}

.slash-menu__search-icon {
  flex-shrink: 0;
  font-size: 14px;
  color: var(--el-text-color-secondary, #909399);
}

.slash-menu__search-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: var(--el-text-color-primary, #303133);
}

.slash-menu__search-input::placeholder {
  color: var(--el-text-color-secondary, #909399);
}

.slash-menu__state {
  padding: 16px 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
  text-align: center;
}

.slash-menu__list {
  list-style: none;
  margin: 0;
  padding: 4px;
  overflow-y: auto;
}

.slash-menu__item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  cursor: pointer;
}

.slash-menu__item.active {
  background: rgba(217, 119, 87, 0.12);
}

.slash-menu__icon {
  flex-shrink: 0;
  margin-top: 1px;
}

.slash-menu__body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.slash-menu__name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}

.slash-menu__slug {
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-secondary, #909399);
  background: rgba(0, 0, 0, 0.05);
  padding: 1px 5px;
  border-radius: 5px;
}

.slash-menu__desc {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}
</style>
