<template>
  <div
    class="mcp-card"
    :class="{ 'mcp-card--catalog': isCatalog }"
    role="button"
    tabindex="0"
    @click="onPrimaryAction"
    @keydown.enter.prevent="onPrimaryAction"
    @keydown.space.prevent="onPrimaryAction"
  >
    <McpServerIcon :name="displayName" :icon-key="iconKey" :variant="iconVariant" />

    <div class="mcp-card-body">
      <div class="mcp-card-name-row">
        <span v-if="!isCatalog" class="mcp-status-dot" :class="statusClass" />
        <span class="mcp-card-name">{{ displayName }}</span>
        <span class="mcp-transport-pill">
          <svg v-if="isHttpTransport" width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
            <circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/>
            <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
          </svg>
          <svg v-else width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
            <polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/>
          </svg>
          {{ transportLabel }}
        </span>
      </div>
      <div class="mcp-card-desc" :class="{ 'mcp-card-desc--error': hasError }">
        {{ descriptionText }}
      </div>
      <div v-if="!isCatalog" class="mcp-card-meta">
        {{ t('mcp.card.toolCount', { n: server!.toolCount || 0 }) }}
        <template v-if="lastSeen"> · {{ lastSeen }}</template>
        <button
          class="mcp-tier-pill"
          :class="{ 'mcp-tier-pill--ext': tier === 'extension' }"
          :title="tier === 'extension' ? t('mcp.tier.extensionHint') : t('mcp.tier.coreHint')"
          @click.stop="emit('setTier', server!, tier === 'extension' ? 'core' : 'extension')"
        >
          {{ tier === 'extension' ? t('mcp.tier.extension') : t('mcp.tier.core') }}
        </button>
      </div>
    </div>

    <!-- Always-visible enable toggle (installed only). Sits outside the
         hover-reveal actions strip so the user can flip it without
         hovering / opening the form, and the disabled state stays
         legible while the card is idle. -->
    <label
      v-if="!isCatalog"
      class="mcp-card-toggle"
      :class="{ 'mcp-card-toggle--on': server!.enabled }"
      :title="server!.enabled ? t('mcp.toggle.disable') : t('mcp.toggle.enable')"
      @click.stop
    >
      <input
        type="checkbox"
        :checked="server!.enabled"
        @change="emit('toggle', server!)"
      />
      <span class="mcp-card-toggle-track" />
    </label>

    <div class="mcp-card-actions" @click.stop>
      <template v-if="isCatalog">
        <button
          v-if="catalogEntry!.docsUrl"
          class="mcp-card-action"
          :title="t('mcp.card.docs')"
          :aria-label="t('mcp.card.docs')"
          @click="emit('docs', catalogEntry!.docsUrl)"
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
            <polyline points="15 3 21 3 21 9"/>
            <line x1="10" y1="14" x2="21" y2="3"/>
          </svg>
        </button>
        <button
          class="mcp-card-action"
          :title="t('mcp.card.add')"
          :aria-label="t('mcp.card.add')"
          @click="emit('add', catalogEntry!)"
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
        </button>
      </template>
      <template v-else>
        <button
          class="mcp-card-action"
          :title="t('mcp.actions.test')"
          :aria-label="t('mcp.actions.test')"
          :disabled="testing"
          @click="emit('test', server!)"
        >
          <svg v-if="!testing" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/>
            <polyline points="22 4 12 14.01 9 11.01"/>
          </svg>
          <svg v-else class="spin" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="2" x2="12" y2="6"/><line x1="12" y1="18" x2="12" y2="22"/>
            <line x1="4.93" y1="4.93" x2="7.76" y2="7.76"/><line x1="16.24" y1="16.24" x2="19.07" y2="19.07"/>
            <line x1="2" y1="12" x2="6" y2="12"/><line x1="18" y1="12" x2="22" y2="12"/>
          </svg>
        </button>
        <button
          class="mcp-card-action"
          :title="t('common.edit')"
          :aria-label="t('common.edit')"
          @click="emit('edit', server!)"
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
          </svg>
        </button>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { McpServer } from './types'
import type { McpCatalogEntry } from './catalog'
import McpServerIcon from './McpServerIcon.vue'

const props = defineProps<{
  server?: McpServer
  catalogEntry?: McpCatalogEntry
  testing?: boolean
}>()

const emit = defineEmits<{
  (e: 'edit', server: McpServer): void
  (e: 'test', server: McpServer): void
  (e: 'toggle', server: McpServer): void
  (e: 'setTier', server: McpServer, tier: 'core' | 'extension'): void
  (e: 'add', entry: McpCatalogEntry): void
  (e: 'docs', url: string): void
}>()

const { t } = useI18n()

const isCatalog = computed(() => !!props.catalogEntry)
// Whole-server disclosure tier; defaults to core (matches the DB default) so
// MCP tools stay directly callable until an admin moves the server to extension.
const tier = computed<'core' | 'extension'>(() =>
  props.server?.disclosureTier === 'extension' ? 'extension' : 'core',
)
const displayName = computed(() =>
  isCatalog.value ? props.catalogEntry!.name : props.server!.name,
)
// Catalog entries carry their iconKey explicitly. Installed entries reuse
// their `name` as the key so a server named `figma` or `github` picks up
// the same brand glyph as the catalog card it shipped from.
const iconKey = computed(() =>
  isCatalog.value ? props.catalogEntry!.iconKey : props.server!.name?.toLowerCase(),
)
const iconVariant = computed<'installed-ok' | 'installed-err' | 'installed' | 'brand'>(() => {
  if (isCatalog.value) return 'brand'
  const s = props.server!.lastStatus
  if (s === 'connected') return 'installed-ok'
  if (s === 'error') return 'installed-err'
  return 'installed'
})

const transportLabel = computed(() => {
  const tp = isCatalog.value ? props.catalogEntry!.config.transport : props.server!.transport
  if (tp === 'stdio') return 'STDIO'
  if (tp === 'sse') return 'SSE'
  return 'HTTP'
})
const isHttpTransport = computed(() => {
  const tp = isCatalog.value ? props.catalogEntry!.config.transport : props.server!.transport
  return tp === 'streamable_http' || tp === 'sse'
})

const statusClass = computed(() => {
  const s = props.server?.lastStatus
  if (s === 'connected') return 'mcp-status-dot--ok'
  if (s === 'error') return 'mcp-status-dot--err'
  return 'mcp-status-dot--off'
})

const hasError = computed(
  () => !isCatalog.value && props.server!.lastStatus === 'error' && !!props.server!.lastError,
)

const descriptionText = computed(() => {
  if (isCatalog.value) return props.catalogEntry!.description
  if (hasError.value) {
    const err = props.server!.lastError
    return err.length > 60 ? err.slice(0, 60) + '…' : err
  }
  return props.server!.description || ''
})

const lastSeen = computed(() => {
  if (isCatalog.value || !props.server!.lastConnectedTime) return ''
  return formatRelative(props.server!.lastConnectedTime)
})

function formatRelative(iso: string): string {
  const time = new Date(iso).getTime()
  if (!Number.isFinite(time)) return ''
  const diff = Date.now() - time
  if (diff < 0) return ''
  const min = Math.floor(diff / 60000)
  if (min < 1) return t('mcp.time.justNow')
  if (min < 60) return t('mcp.time.minutesAgo', { n: min })
  const hr = Math.floor(min / 60)
  if (hr < 24) return t('mcp.time.hoursAgo', { n: hr })
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function onPrimaryAction() {
  if (isCatalog.value) emit('add', props.catalogEntry!)
  else emit('edit', props.server!)
}
</script>

<style scoped>
.mcp-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
  outline: none;
}
.mcp-card:hover {
  border-color: var(--mc-border-strong);
  box-shadow: var(--mc-shadow-soft);
  transform: translateY(-1px);
}
.mcp-card:focus-visible {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px var(--mc-primary-bg);
}

/* Icon container is owned by McpServerIcon.vue. */

.mcp-card-body {
  min-width: 0;
  flex: 1;
  /* When hover-reveal actions slide in (absolute, right of body), they
     otherwise float on top of the description's tail letters. Reserving
     right-padding on hover lets `text-overflow: ellipsis` trim the text
     before it reaches the action strip. */
  transition: padding-right 0.15s ease;
}
.mcp-card:hover .mcp-card-body,
.mcp-card:focus-within .mcp-card-body {
  padding-right: 64px;
}
/* Catalog cards have no toggle — actions sit flush right, so reserve less. */
.mcp-card--catalog:hover .mcp-card-body,
.mcp-card--catalog:focus-within .mcp-card-body {
  padding-right: 52px;
}
.mcp-card-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.mcp-card-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mcp-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.mcp-status-dot--ok {
  background: #34d399;
  box-shadow: 0 0 4px rgba(52, 211, 153, 0.45);
}
.mcp-status-dot--err {
  background: var(--mc-danger, #ef4444);
  box-shadow: 0 0 4px rgba(239, 68, 68, 0.4);
}
.mcp-status-dot--off { background: var(--mc-text-tertiary); opacity: 0.4; }

.mcp-transport-pill {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 6px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  flex-shrink: 0;
}

.mcp-card-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mcp-card-desc--error { color: var(--mc-danger, #ef4444); }

.mcp-card-meta {
  margin-top: 4px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}

.mcp-tier-pill {
  margin-left: 6px;
  padding: 1px 7px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.03em;
  text-transform: uppercase;
  cursor: pointer;
  transition: all 0.15s;
}
.mcp-tier-pill:hover { border-color: var(--mc-primary); color: var(--mc-primary); }
.mcp-tier-pill--ext { background: var(--mc-primary-bg); color: var(--mc-primary); border-color: transparent; }

/* Always-visible enable toggle on installed cards. Sits to the right
   of the card body; hover-reveal actions slide in to its left. */
.mcp-card-toggle {
  position: relative;
  display: inline-block;
  width: 32px;
  height: 18px;
  flex-shrink: 0;
  cursor: pointer;
}
.mcp-card-toggle input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  margin: 0;
  cursor: pointer;
}
.mcp-card-toggle-track {
  position: absolute;
  inset: 0;
  background: var(--mc-border);
  border-radius: 999px;
  transition: background 0.18s;
}
.mcp-card-toggle-track::before {
  content: '';
  position: absolute;
  width: 12px;
  height: 12px;
  left: 3px;
  top: 3px;
  background: var(--mc-bg-elevated);
  border-radius: 50%;
  transition: transform 0.18s;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.18);
}
.mcp-card-toggle--on .mcp-card-toggle-track { background: var(--mc-primary); }
.mcp-card-toggle--on .mcp-card-toggle-track::before { transform: translateX(14px); }

.mcp-card-actions {
  display: flex;
  gap: 2px;
  /* Sit to the left of the always-visible toggle (toggle 32px + gap 12px). */
  position: absolute;
  right: 56px;
  top: 50%;
  transform: translateY(-50%);
  opacity: 0;
  transition: opacity 0.15s;
}
/* Catalog cards have no toggle — actions go all the way right. */
.mcp-card--catalog .mcp-card-actions { right: 10px; }
.mcp-card:hover .mcp-card-actions,
.mcp-card:focus-within .mcp-card-actions { opacity: 1; }

.mcp-card-action {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--mc-text-tertiary);
  border-radius: 7px;
  cursor: pointer;
}
.mcp-card-action:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}
.mcp-card-action:disabled { cursor: not-allowed; opacity: 0.4; }

@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }
</style>
