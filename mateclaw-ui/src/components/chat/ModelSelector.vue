<template>
  <div class="model-selector-wrap" ref="triggerRef">
    <button
      class="model-select-trigger"
      :disabled="saving"
      @click="toggle"
    >
      <span class="model-select-trigger__name">{{ activeLabel || $t('chat.configModel') }}</span>
      <svg class="model-select-trigger__arrow" :class="{ open }" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
    </button>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="open" class="model-dropdown-backdrop" @click="open = false"></div>
      </Transition>

      <Transition name="model-dropdown">
        <div v-if="open" ref="dropdownRef" class="model-dropdown" :style="dropdownStyle">
          <!-- 搜索 -->
          <div v-if="totalCount > 5" class="model-search">
            <svg class="model-search__icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
            <input
              ref="searchRef"
              v-model="query"
              class="model-search__input"
              :placeholder="$t('chat.searchModel')"
              @keydown.esc.stop="open = false"
            />
          </div>

          <!-- 分组列表 -->
          <div class="model-groups" ref="listRef">
            <template v-for="group in filteredGroups" :key="group.provider.id">
              <div class="model-group-header">
                <span class="model-group-header__name" :title="group.provider.name">{{ group.provider.name }}</span>
                <span v-if="group.provider.isLocal" class="model-group-header__badge model-group-header__badge--local">Local</span>
                <!-- Liveness dot — UNPROBED = grey, COOLDOWN = amber, LIVE = none.
                     v2 R3: when showAllStates, also surface UNCONFIGURED / REMOVED chips
                     so the user can see what's wrong without leaving chat. -->
                <span
                  v-if="group.provider.liveness === 'UNPROBED'"
                  class="model-group-header__dot model-group-header__dot--unprobed"
                  :title="$t('provider.status.unprobed')"
                ></span>
                <span
                  v-else-if="group.provider.liveness === 'COOLDOWN'"
                  class="model-group-header__dot model-group-header__dot--cooldown"
                  :title="$t('provider.status.cooldown', { s: cooldownSeconds(group.provider) })"
                ></span>
                <span
                  v-if="group.provider.liveness === 'UNCONFIGURED'"
                  class="model-group-header__chip model-group-header__chip--warn"
                >{{ $t('provider.status.unconfigured') }}</span>
                <span
                  v-else-if="group.provider.liveness === 'REMOVED'"
                  class="model-group-header__chip model-group-header__chip--err"
                >{{ $t('provider.status.removed') }}</span>
                <span
                  v-else-if="group.provider.liveness === 'COOLDOWN'"
                  class="model-group-header__chip model-group-header__chip--info"
                >{{ $t('provider.status.cooldown', { s: cooldownSeconds(group.provider) }) }}</span>
                <button
                  v-if="!isSelectable(group.provider)"
                  class="model-group-header__fix"
                  @click.stop="emitNavigateFix(group.provider)"
                  type="button"
                >{{ $t('chat.promptAction.fixThis') }}</button>
              </div>
              <div
                v-for="item in group.models"
                :key="item.value"
                class="model-dropdown-item"
                :class="{
                  active: item.value === activeValue,
                  dimmed: !isSelectable(group.provider),
                }"
                @click="onItemClick(group.provider, item.value)"
              >
                <span class="model-dropdown-item__name">{{ item.name }}</span>
                <svg v-if="item.value === activeValue" class="model-dropdown-item__check" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
              </div>
            </template>

            <!-- 无结果 -->
            <div v-if="filteredGroups.length === 0" class="model-empty">
              <!-- RFC-074 PR-2: when there are zero usable models AND the user
                   isn't searching, this is the "no providers configured" empty
                   state. Push them into Settings/Models with the drawer
                   pre-opened via ?addProvider=1. -->
              <template v-if="query.trim() === '' && groups.length === 0">
                {{ emptyHint || $t('chat.noProvidersConfigured') }}
                <RouterLink class="model-empty__cta" to="/settings/models?addProvider=1" @click="open = false">
                  {{ $t('chat.goConfigure') }}
                </RouterLink>
              </template>
              <template v-else>
                {{ $t('chat.noMatchModel') }}
              </template>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, type CSSProperties } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { ProviderInfo } from '@/types'

const { t } = useI18n()

interface ModelItem {
  value: string
  name: string
  id: string
}

interface ModelGroup {
  provider: ProviderInfo
  models: ModelItem[]
}

const props = defineProps<{
  providers: ProviderInfo[]
  activeValue: string
  activeLabel: string
  saving?: boolean
  /**
   * v2 R3: when true, render UNCONFIGURED / REMOVED rows too (dimmed, with
   * status chip + Fix button) so users see what's wrong without leaving chat.
   * Defaults to false to keep the legacy filtered behavior for any other call site.
   */
  showAllStates?: boolean
  /** Optional override for the empty-state text. */
  emptyHint?: string
}>()

const emit = defineEmits<{
  select: [value: string]
  'navigate-fix': [provider: ProviderInfo]
}>()

const open = ref(false)
const query = ref('')
const searchRef = ref<HTMLInputElement>()
const triggerRef = ref<HTMLElement>()
const dropdownRef = ref<HTMLElement>()
const listRef = ref<HTMLElement>()

// 下拉框定位（基于 trigger 按钮的位置）
const dropdownStyle = ref<CSSProperties>({})

function updatePosition() {
  const el = triggerRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  dropdownStyle.value = {
    position: 'fixed',
    top: `${rect.bottom + 6}px`,
    right: `${window.innerWidth - rect.right}px`,
  }
}

function toggle() {
  if (open.value) {
    open.value = false
  } else {
    updatePosition()
    open.value = true
  }
}

// 按 provider 分组，云端在前，本地在后
// v2 R3: when showAllStates is true, keep UNCONFIGURED/REMOVED visible (dimmed +
// status chip + Fix button) so the user can act in-place. Default behavior
// (showAllStates=false) preserves the original filter for non-popup contexts.
function isHidden(p: ProviderInfo): boolean {
  if (props.showAllStates) return false
  if (!p.liveness) return !p.available
  return p.liveness === 'UNCONFIGURED' || p.liveness === 'REMOVED'
}

function isSelectable(p: ProviderInfo): boolean {
  if (!p.liveness) return p.available === true
  return p.liveness === 'LIVE' || p.liveness === 'COOLDOWN'
}

const groups = computed<ModelGroup[]>(() => {
  const cloud: ModelGroup[] = []
  const local: ModelGroup[] = []

  for (const provider of props.providers) {
    if (isHidden(provider)) continue
    const allModels = [...(provider.models || []), ...(provider.extraModels || [])]
    // When showAllStates is on we still want UNCONFIGURED rows to appear even
    // if they have no models — synthesize one placeholder so the header chip +
    // Fix button render. Otherwise (legacy call site) skip empty groups.
    if (allModels.length === 0) {
      if (!props.showAllStates) continue
      const group: ModelGroup = {
        provider,
        models: [],
      }
      if (provider.isLocal) local.push(group)
      else cloud.push(group)
      continue
    }

    const group: ModelGroup = {
      provider,
      models: allModels.map(m => ({
        value: `${provider.id}::${m.id}`,
        name: m.name || m.id,
        id: m.id,
      })),
    }

    if (provider.isLocal) {
      local.push(group)
    } else {
      cloud.push(group)
    }
  }

  return [...cloud, ...local]
})

function cooldownSeconds(provider: ProviderInfo): number {
  return Math.max(1, Math.ceil((provider.cooldownRemainingMs || 0) / 1000))
}

const totalCount = computed(() =>
  groups.value.reduce((n, g) => n + g.models.length, 0)
)

// 搜索过滤
const filteredGroups = computed<ModelGroup[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return groups.value

  const result: ModelGroup[] = []
  for (const group of groups.value) {
    if (group.provider.name.toLowerCase().includes(q)) {
      result.push(group)
      continue
    }
    const matched = group.models.filter(
      m => m.name.toLowerCase().includes(q) || m.id.toLowerCase().includes(q)
    )
    if (matched.length > 0) {
      result.push({ ...group, models: matched })
    }
  }
  return result
})

function handleSelect(value: string) {
  open.value = false
  query.value = ''
  emit('select', value)
}

/**
 * v2 R3: a row is selectable iff its provider can serve a request now (LIVE) or
 * is just throttled (COOLDOWN — backend lets the request go and the chain
 * walker handles fallback). UNCONFIGURED / REMOVED / UNPROBED need user action,
 * so clicking those routes to the Fix flow instead of trying to switch.
 */
function onItemClick(provider: ProviderInfo, value: string) {
  if (isSelectable(provider)) {
    handleSelect(value)
  } else {
    emitNavigateFix(provider)
  }
}

function emitNavigateFix(provider: ProviderInfo) {
  open.value = false
  emit('navigate-fix', provider)
}

// 打开时聚焦搜索框 + 滚动到当前选中项
watch(open, async (isOpen) => {
  if (isOpen) {
    query.value = ''
    await nextTick()
    searchRef.value?.focus()
    await nextTick()
    const activeEl = listRef.value?.querySelector('.model-dropdown-item.active')
    if (activeEl) {
      activeEl.scrollIntoView({ block: 'center', behavior: 'instant' })
    }
  }
})
</script>

<style scoped>
.model-selector-wrap {
  position: relative;
}

.model-select-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  background: var(--mc-panel-raised);
  color: var(--mc-text-primary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
  max-width: 280px;
}

.model-select-trigger:hover {
  border-color: var(--mc-primary);
}

.model-select-trigger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.model-select-trigger__name {
  overflow: hidden;
  text-overflow: ellipsis;
}

.model-select-trigger__arrow {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
  transition: transform 0.2s;
}

.model-select-trigger__arrow.open {
  transform: rotate(180deg);
}
</style>

<style>
/* Teleport 到 body 的元素不能用 scoped */

.model-dropdown-backdrop {
  position: fixed;
  inset: 0;
  z-index: 4000;
}

.model-dropdown {
  z-index: 4001;
  min-width: 280px;
  max-width: 360px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  padding: 6px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
  max-height: 420px;
  display: flex;
  flex-direction: column;
}

/* ---- Search ---- */

.model-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--mc-border);
}

.model-search__icon {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
}

.model-search__input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  color: var(--mc-text-primary);
  font-size: 13px;
  line-height: 1.6;
}

.model-search__input::placeholder {
  color: var(--mc-text-quaternary);
}

/* ---- Groups ---- */

.model-groups {
  overflow-y: auto;
  flex: 1;
}

.model-group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  user-select: none;
}

/* The provider name takes the remaining width and truncates; the status
   chip / Fix button keep their natural size on the same line. Without
   min-width:0 the long name would squeeze the chip and wrap its text. */
.model-group-header__name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.model-group-header__badge {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  height: 16px;
  padding: 0 5px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  white-space: nowrap;
}

.model-group-header__badge--local {
  background: var(--mc-success-bg, rgba(52, 199, 89, 0.12));
  color: var(--mc-success, #34c759);
}

/* RFC-073 liveness dot — sits next to the provider name */
.model-group-header__dot {
  display: inline-block;
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  cursor: help;
}
.model-group-header__dot--unprobed {
  background: var(--mc-text-quaternary, #c0c4cc);
  animation: model-dot-pulse 1.6s ease-in-out infinite;
}
.model-group-header__dot--cooldown {
  background: #f59e0b;
}
@keyframes model-dot-pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}

/* Liveness status chips — surfaced when ModelSelector is in show-all-states mode. */
.model-group-header__chip {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  height: 16px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: none;
  white-space: nowrap;
}
.model-group-header__chip--warn {
  background: rgba(245, 158, 11, 0.15);
  color: #b45309;
}
.model-group-header__chip--err {
  background: rgba(239, 68, 68, 0.15);
  color: #b91c1c;
}
.model-group-header__chip--info {
  background: rgba(59, 130, 246, 0.15);
  color: #1d4ed8;
}
.dark .model-group-header__chip--warn { color: #fbbf24; }
.dark .model-group-header__chip--err  { color: #fca5a5; }
.dark .model-group-header__chip--info { color: #93c5fd; }

.model-group-header__fix {
  flex: 0 0 auto;
  height: 18px;
  padding: 0 8px;
  border-radius: 4px;
  border: 1px solid var(--mc-border);
  background: transparent;
  color: var(--mc-text-secondary);
  font-size: 11px;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.12s, color 0.12s, border-color 0.12s;
}
.model-group-header__fix:hover {
  background: var(--mc-primary);
  color: #fff;
  border-color: var(--mc-primary);
}

/* ---- Items ---- */

.model-dropdown-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.12s;
}

.model-dropdown-item:hover {
  background: var(--mc-bg-sunken);
}

.model-dropdown-item.active {
  background: var(--mc-primary-bg);
}

/* RFC-073: cooldown / unprobed models render dimmed but still selectable. */
.model-dropdown-item.dimmed {
  opacity: 0.55;
}
.model-dropdown-item.dimmed:hover {
  opacity: 0.85;
}

.model-dropdown-item__name {
  font-size: 13px;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-dropdown-item__check {
  flex-shrink: 0;
  color: var(--mc-primary);
}

/* ---- Empty ---- */

.model-empty {
  padding: 20px 10px;
  text-align: center;
  font-size: 13px;
  color: var(--mc-text-quaternary);
}
.model-empty__cta {
  display: inline-block;
  margin-top: 8px;
  padding: 6px 12px;
  border-radius: 8px;
  background: var(--mc-primary);
  color: white;
  font-size: 12px;
  font-weight: 600;
  text-decoration: none;
}
.model-empty__cta:hover { background: var(--mc-primary-hover, var(--mc-primary)); }

/* ---- Transition ---- */

.model-dropdown-enter-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.model-dropdown-leave-active {
  transition: opacity 0.1s ease, transform 0.1s ease;
}
.model-dropdown-enter-from {
  opacity: 0;
  transform: translateY(-6px);
}
.model-dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* ---- Dark mode ---- */

.dark .model-dropdown {
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
}

/* ---- Mobile ---- */

@media (max-width: 768px) {
  .model-dropdown {
    min-width: 240px;
    max-width: calc(100vw - 40px);
  }
}
</style>
