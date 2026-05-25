<template>
  <Teleport to="body">
    <Transition name="drawer-fade">
      <div v-if="visible" class="drawer-overlay" @click.self="$emit('close')">
        <div class="drawer-panel">
          <div class="drawer-header">
            <div>
              <h3 class="drawer-title">{{ t('settings.model.addProviderDrawerTitle') }}</h3>
              <p class="drawer-subtitle">{{ t('settings.model.addProviderDrawerSubtitle') }}</p>
            </div>
            <button class="drawer-close" :title="t('common.close')" @click="$emit('close')">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <!-- Spotlight-style search: filters by name or id, both matter
               (Chinese name search vs. slug like "siliconflow"). -->
          <div class="drawer-search">
            <svg
              class="drawer-search__icon"
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2.2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <circle cx="11" cy="11" r="7" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
            <input
              ref="searchInputRef"
              v-model="searchQuery"
              type="text"
              class="drawer-search__input"
              :placeholder="t('settings.model.catalogSearchPlaceholder')"
              spellcheck="false"
              autocomplete="off"
              @keydown.escape.stop="onEscape"
            />
            <button
              v-if="searchQuery"
              class="drawer-search__clear"
              :title="t('common.clear')"
              type="button"
              @click="clearSearch"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>

          <div class="drawer-content">
            <!-- Cloud providers -->
            <section v-if="cloudGroup.length" class="drawer-group">
              <h4 class="drawer-group-title">{{ t('settings.model.cloudProviders') }}</h4>
              <div class="drawer-group__list">
                <ProviderCatalogRow
                  v-for="p in cloudGroup"
                  :key="p.id"
                  :provider="p"
                  :toggling-id="togglingId"
                  :get-provider-icon="getProviderIcon"
                  :on-icon-error="onIconError"
                  @enable="onEnable"
                />
              </div>
            </section>

            <!-- Local providers -->
            <section v-if="localGroup.length" class="drawer-group">
              <h4 class="drawer-group-title">{{ t('settings.model.localProviders') }}</h4>
              <div class="drawer-group__list">
                <ProviderCatalogRow
                  v-for="p in localGroup"
                  :key="p.id"
                  :provider="p"
                  :toggling-id="togglingId"
                  :get-provider-icon="getProviderIcon"
                  :on-icon-error="onIconError"
                  @enable="onEnable"
                />
              </div>
            </section>

            <!-- Empty states: search-aware. A live query that yields nothing
                 is a different experience from a wholly empty catalog. -->
            <div v-if="!cloudGroup.length && !localGroup.length" class="drawer-empty">
              <template v-if="searchQuery">
                {{ t('settings.model.catalogSearchEmpty', { query: searchQuery }) }}
              </template>
              <template v-else>
                {{ t('settings.model.catalogEmpty') }}
              </template>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import type { ProviderInfo } from '@/types'
import ProviderCatalogRow from './ProviderCatalogRow.vue'

const props = defineProps<{
  visible: boolean
  catalog: ProviderInfo[]
  togglingId: string | null
  getProviderIcon: (id: string) => string
  onIconError: (e: Event) => void
  enableProvider: (id: string) => Promise<unknown>
}>()

defineEmits<{
  close: []
}>()

const { t } = useI18n()

const searchQuery = ref('')
const searchInputRef = ref<HTMLInputElement | null>(null)

// Match against name OR id so users can search either by Chinese display
// name (e.g. "硅基流动") or by the slug they remember from docs (e.g.
// "siliconflow").
const filteredCatalog = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return props.catalog
  return props.catalog.filter(p =>
    p.name.toLowerCase().includes(q) || p.id.toLowerCase().includes(q)
  )
})

// Sort: enabled rows sink to the bottom of each group so the actionable
// (still-disabled) options sit at the top where the user lands.
const cloudGroup = computed(() =>
  filteredCatalog.value
    .filter(p => !p.isLocal)
    .sort((a, b) => Number(!!a.enabled) - Number(!!b.enabled) || a.name.localeCompare(b.name))
)
const localGroup = computed(() =>
  filteredCatalog.value
    .filter(p => p.isLocal)
    .sort((a, b) => Number(!!a.enabled) - Number(!!b.enabled) || a.name.localeCompare(b.name))
)

// Spotlight behavior: focus on open, clear on close.
watch(() => props.visible, async (isVisible) => {
  if (isVisible) {
    await nextTick()
    searchInputRef.value?.focus()
  } else {
    searchQuery.value = ''
  }
})

function clearSearch() {
  searchQuery.value = ''
  searchInputRef.value?.focus()
}

// First Esc clears a non-empty query; second Esc closes the drawer (handled
// upstream). Stops propagation only when there's something to clear.
function onEscape(event: KeyboardEvent) {
  if (searchQuery.value) {
    event.preventDefault()
    clearSearch()
  }
}

async function onEnable(p: ProviderInfo) {
  await props.enableProvider(p.id)
  mcToast.success(t('settings.model.enabledToast', { name: p.name }))
}
</script>

<style scoped>
/*
 * Frosted-glass drawer — depth via translucency rather than borders.
 * Backdrop blur on the overlay anchors the panel to the page behind it;
 * panel blur lets the page tone bleed through so it doesn't look pasted on.
 */
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(20, 14, 10, 0.32);
  backdrop-filter: blur(8px) saturate(140%);
  -webkit-backdrop-filter: blur(8px) saturate(140%);
  z-index: 1500;
  display: flex;
  justify-content: flex-end;
}
:global(html.dark .drawer-overlay) {
  /* Darker scrim in dark mode so the panel reads as a distinct surface
     against the already-dark page behind it. */
  background: rgba(0, 0, 0, 0.5);
}

.drawer-panel {
  width: 460px;
  max-width: 92vw;
  height: 100%;
  background: rgba(255, 250, 245, 0.72);
  backdrop-filter: blur(48px) saturate(180%);
  -webkit-backdrop-filter: blur(48px) saturate(180%);
  border-left: 1px solid rgba(255, 255, 255, 0.4);
  box-shadow: -24px 0 60px rgba(25, 14, 8, 0.16);
  display: flex;
  flex-direction: column;
  /* iOS-style spring easing — gives the slide a natural settle. */
  animation: drawer-slide 0.36s cubic-bezier(0.32, 0.72, 0, 1);
}

:global(html.dark .drawer-panel) {
  /* Higher opacity (0.78 vs 0.62) — at high blur the dark page bleed
     made the panel look washed-out grey at 0.62. */
  background: rgba(32, 26, 22, 0.78);
  border-left-color: rgba(255, 255, 255, 0.10);
  box-shadow: -24px 0 60px rgba(0, 0, 0, 0.5);
}

@keyframes drawer-slide {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}

.drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 24px 28px 20px;
  border-bottom: 1px solid rgba(123, 88, 67, 0.10);
}
:global(html.dark .drawer-header) {
  border-bottom-color: rgba(255, 255, 255, 0.06);
}

.drawer-title {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--mc-text-primary);
}
.drawer-subtitle {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--mc-text-tertiary);
}

.drawer-close {
  background: transparent;
  border: 0;
  padding: 8px;
  border-radius: 999px;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
  transition: background 0.15s ease, color 0.15s ease;
}
.drawer-close:hover {
  background: rgba(123, 88, 67, 0.08);
  color: var(--mc-text-primary);
}
:global(html.dark .drawer-close:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: var(--mc-text-primary);
}

/*
 * Search bar — Spotlight-inspired pill, frosted to match the panel.
 * Sits between header and scrollable content so it stays visible while
 * the catalog scrolls underneath.
 */
.drawer-search {
  margin: 14px 20px 4px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border-radius: 999px;
  background: rgba(123, 88, 67, 0.06);
  transition: background 0.15s ease, box-shadow 0.15s ease;
}
.drawer-search:focus-within {
  background: rgba(255, 255, 255, 0.65);
  box-shadow:
    inset 0 0 0 1px rgba(217, 119, 87, 0.3),
    0 0 0 3px rgba(217, 119, 87, 0.10);
}
:global(html.dark .drawer-search) {
  /* Higher contrast vs the panel so the search input is clearly clickable. */
  background: rgba(255, 255, 255, 0.08);
}
:global(html.dark .drawer-search:focus-within) {
  background: rgba(255, 255, 255, 0.14);
  box-shadow:
    inset 0 0 0 1px rgba(217, 119, 87, 0.6),
    0 0 0 3px rgba(217, 119, 87, 0.20);
}

.drawer-search__icon {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
}

.drawer-search__input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: transparent;
  padding: 9px 4px;
  font-size: 14px;
  color: var(--mc-text-primary);
  outline: none;
  letter-spacing: -0.005em;
}
.drawer-search__input::placeholder {
  color: var(--mc-text-tertiary);
}

.drawer-search__clear {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  background: transparent;
  border: 0;
  border-radius: 999px;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  transition: background 0.15s ease, color 0.15s ease;
}
.drawer-search__clear:hover {
  background: rgba(123, 88, 67, 0.10);
  color: var(--mc-text-primary);
}
:global(html.dark .drawer-search__clear:hover) {
  background: rgba(255, 255, 255, 0.10);
  color: var(--mc-text-primary);
}

.drawer-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px 32px;
}

.drawer-group + .drawer-group { margin-top: 24px; }

.drawer-group-title {
  margin: 0 4px 8px;
  font-size: 12px;
  font-weight: 500;
  text-transform: none;
  letter-spacing: 0;
  color: var(--mc-text-tertiary);
}

/*
 * Apple System-Settings style: one rounded card containing N rows
 * with hairline dividers between them — instead of N bordered cards.
 */
.drawer-group__list {
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.5);
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(25, 14, 8, 0.04);
}
:global(html.dark .drawer-group__list) {
  /* Slightly more visible card surface in dark — at 0.04 the rounded
     container blended into the panel and lost its "card" affordance. */
  background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
}

.drawer-empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--mc-text-tertiary);
}

.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 0.22s ease;
}
.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}

/* Mobile: bottom sheet with rounded top + softer slide. */
@media (max-width: 768px) {
  .drawer-overlay {
    justify-content: stretch;
    align-items: flex-end;
  }
  .drawer-panel {
    width: 100%;
    max-width: 100%;
    height: 92vh;
    border-left: 0;
    border-top: 1px solid rgba(255, 255, 255, 0.4);
    border-top-left-radius: 20px;
    border-top-right-radius: 20px;
    animation: drawer-slide-up 0.32s cubic-bezier(0.32, 0.72, 0, 1);
  }
  :global(html.dark .drawer-panel) {
    border-top-color: rgba(255, 255, 255, 0.08);
  }
  @keyframes drawer-slide-up {
    from { transform: translateY(100%); }
    to { transform: translateY(0); }
  }
}
</style>
