<template>
  <!-- RFC-090 Phase 4 — Jobs-style rewrite. Activity tells you who
       did what, in plain English. The old 7-column table dumped raw
       fields; the new layout reads like a feed. -->
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner activity-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('nav.activity') }}</div>
            <h1 class="mc-page-title">{{ t('security.activity.title') }}</h1>
            <p class="mc-page-desc">{{ t('security.activity.desc') }}</p>
          </div>
          <div class="header-actions">
            <button class="btn-ghost" @click="loadEvents" :disabled="loading">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="1 4 1 10 7 10"/>
                <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
              </svg>
              {{ loading ? t('security.activity.loading') : t('common.refresh') }}
            </button>
          </div>
        </div>

        <!-- Filter chips — minimal, source-only by default; extra
             filters tucked behind a kebab so the page stays uncluttered -->
        <div class="filter-chips">
          <button
            v-for="src in sourceChips"
            :key="src.value"
            class="filter-chip"
            :class="{ active: filters.source === src.value }"
            @click="setSourceFilter(src.value)"
          >
            <span v-if="src.dot" class="chip-dot" :class="`dot-${src.value || 'all'}`"></span>
            {{ src.label }}
          </button>
          <div class="filter-spacer"></div>
          <button class="filter-more" @click="showMoreFilters = !showMoreFilters">
            {{ showMoreFilters ? t('common.collapse') : t('security.activity.moreFilters') }}
          </button>
        </div>

        <transition name="fade">
          <div v-if="showMoreFilters" class="filter-extra">
            <select v-model="filters.action" class="filter-select" @change="filterEventsLocally">
              <option value="">{{ t('security.activity.allActions') }}</option>
              <option v-for="a in knownActions" :key="a" :value="a">{{ a }}</option>
            </select>
            <select v-model="filters.resourceType" class="filter-select" @change="filterEventsLocally">
              <option value="">{{ t('security.activity.allResources') }}</option>
              <option v-for="r in knownResources" :key="r" :value="r">{{ r }}</option>
            </select>
          </div>
        </transition>

        <!-- Event feed — grouped by day, each event is one sentence -->
        <div v-if="loading && groupedEvents.length === 0" class="feed-empty">
          {{ t('security.activity.loading') }}
        </div>
        <div v-else-if="groupedEvents.length === 0" class="feed-empty">
          <div class="empty-icon">📋</div>
          <p>{{ t('security.activity.noEvents') }}</p>
          <p class="empty-hint">{{ t('security.activity.emptyHint') }}</p>
        </div>

        <div v-else class="event-feed">
          <template v-for="group in groupedEvents" :key="group.label">
            <div class="day-divider">
              <span class="day-label">{{ group.label }}</span>
              <span class="day-count">{{ group.events.length }}</span>
            </div>
            <button
              v-for="event in group.events"
              :key="event.id"
              class="event-row"
              @click="openDetail(event)"
            >
              <span class="event-dot" :class="`dot-${event.source || 'audit'}`"></span>
              <div class="event-body">
                <div class="event-sentence">
                  <span class="event-actor">{{ event.username || '—' }}</span>
                  <span class="event-verb" :class="`verb-${(event.action || '').toLowerCase()}`">
                    {{ verbFor(event) }}
                  </span>
                  <span class="event-target">
                    <span class="target-type">{{ resourceLabel(event) }}</span>
                    <span v-if="event.resourceName || event.resourceId" class="target-name">
                      {{ event.resourceName || event.resourceId }}
                    </span>
                  </span>
                </div>
                <div v-if="event.summary" class="event-summary">{{ event.summary }}</div>
              </div>
              <span class="event-time" :title="absoluteTime(event)">{{ relativeTime(event) }}</span>
              <span class="event-chevron">›</span>
            </button>
          </template>
        </div>

        <!-- MateClaw frosted-pill pagination — replaces el-pagination
             so the activity feed reads in the same visual language as
             the rest of the app. -->
        <div v-if="total > pageSize" class="pagination">
          <McPagination
            v-model:page="page"
            v-model:size="pageSize"
            :total="total"
            :sizes="[20, 50, 100]"
            @change="loadEvents"
          />
        </div>
      </div>
    </div>

    <!-- Detail drawer — wider (720px), no internal max-height frame.
         Hero block at the top so the user knows in 0.5s what happened.
         JSON dump replaced by structured field rendering. -->
    <el-drawer
      v-model="detailVisible"
      :title="t('security.activity.detailTitle')"
      direction="rtl"
      :size="drawerSize"
      :destroy-on-close="true"
    >
      <div v-if="detailEvent" class="detail-shell">
        <div class="detail-hero" :class="`hero-${detailEvent.source || 'audit'}`">
          <span class="detail-dot" :class="`dot-${detailEvent.source || 'audit'}`"></span>
          <h2 class="detail-headline">
            <span class="detail-actor">{{ detailEvent.username || '—' }}</span>
            <span class="detail-verb" :class="`verb-${(detailEvent.action || '').toLowerCase()}`">
              {{ verbFor(detailEvent) }}
            </span>
            <span class="detail-target-type">{{ resourceLabel(detailEvent) }}</span>
          </h2>
          <div v-if="detailEvent.resourceName || detailEvent.resourceId" class="detail-target-name">
            {{ detailEvent.resourceName || detailEvent.resourceId }}
          </div>
          <div class="detail-meta">
            <span class="meta-time">{{ relativeTime(detailEvent) }}</span>
            <span class="meta-sep">·</span>
            <span class="meta-source-label">{{ sourceLabel(detailEvent) }}</span>
          </div>
        </div>

        <!-- Changes block — structured rendering of the detail map -->
        <section v-if="changeEntries.length > 0" class="detail-block">
          <h3 class="detail-block-title">{{ t('security.activity.changes') }}</h3>
          <div class="change-list">
            <div v-for="c in changeEntries" :key="c.key" class="change-item">
              <span class="change-key">{{ c.key }}</span>
              <span class="change-value" :class="{ 'is-empty': c.empty }">
                <template v-if="c.empty">{{ t('security.activity.noValue') }}</template>
                <template v-else-if="c.code"><code>{{ c.value }}</code></template>
                <template v-else>{{ c.value }}</template>
              </span>
            </div>
          </div>
        </section>

        <!-- Environment block — IP, user-agent, absolute time -->
        <section class="detail-block">
          <h3 class="detail-block-title">{{ t('security.activity.environment') }}</h3>
          <div class="change-list">
            <div class="change-item">
              <span class="change-key">{{ t('security.activity.absoluteTime') }}</span>
              <span class="change-value"><code>{{ absoluteTime(detailEvent) }}</code></span>
            </div>
            <div class="change-item">
              <span class="change-key">{{ t('security.activity.columns.user') }}</span>
              <span class="change-value">{{ detailEvent.username || '—' }}</span>
            </div>
            <div v-if="detailEvent.ipAddress" class="change-item">
              <span class="change-key">{{ t('security.activity.columns.ip') }}</span>
              <span class="change-value"><code>{{ detailEvent.ipAddress }}</code></span>
            </div>
            <div class="change-item">
              <span class="change-key">{{ t('security.activity.columns.action') }}</span>
              <span class="change-value"><code>{{ detailEvent.action || '—' }}</code></span>
            </div>
            <div class="change-item">
              <span class="change-key">{{ t('security.activity.columns.resource') }}</span>
              <span class="change-value">
                <code>{{ detailEvent.resourceType }}</code>
                <span v-if="detailEvent.resourceName || detailEvent.resourceId">
                  · {{ detailEvent.resourceName || detailEvent.resourceId }}
                </span>
              </span>
            </div>
          </div>
        </section>

        <!-- Raw payload, collapsed — power users only -->
        <details v-if="rawPayload" class="detail-raw">
          <summary>{{ t('security.activity.rawPayload') }}</summary>
          <pre class="detail-pre">{{ rawPayload }}</pre>
        </details>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useIsMobile } from '@/composables/useBreakpoint'
import { activityApi } from '@/api'
import McPagination from '@/components/common/McPagination.vue'

const { t } = useI18n()

interface ActivityEvent {
  id: string
  source?: string
  time?: string
  createTime?: string
  username?: string
  action?: string
  resourceType?: string
  resourceName?: string
  resourceId?: string
  ipAddress?: string
  summary?: string
  detail?: Record<string, any>
}

/**
 * Mobile detection — drives the el-drawer size and el-pagination
 * layout dynamically. CSS @media handles static layout, but EP
 * component props need a reactive value to switch from a 720px
 * drawer to full-width sheet, and to drop the size-switcher /
 * jumper when there's no horizontal room for them.
 */
const isMobile = useIsMobile()

const drawerSize = computed(() => isMobile.value ? '100%' : '720px')

const events = ref<ActivityEvent[]>([])
const loading = ref(false)
const page = ref(1)
// Element Plus pagination owns this ref; @size-change writes through.
// Default 20 to match the smallest entry in :page-sizes — anything
// off-list leaves the size dropdown showing a blank current value.
const pageSize = ref(20)
const total = ref(0)
const filters = reactive({ source: '', action: '', resourceType: '' })
const showMoreFilters = ref(false)

const detailVisible = ref(false)
const detailEvent = ref<ActivityEvent | null>(null)

const sourceChips = computed(() => [
  { value: '', label: t('security.activity.allSources'), dot: false },
  { value: 'audit', label: t('security.activity.sourceAudit'), dot: true },
  { value: 'approval', label: t('security.activity.sourceApproval'), dot: true },
])

const knownActions = [
  'CREATE', 'UPDATE', 'DELETE', 'ENABLE', 'DISABLE',
  'LOGIN', 'LOGOUT',
  'APPROVAL_GRANTED', 'APPROVAL_DENIED', 'APPROVAL_PENDING',
]
const knownResources = [
  'AGENT', 'CHANNEL', 'SKILL', 'WIKI', 'MEMBER', 'WORKSPACE', 'TOOL_APPROVAL',
]

const filteredEvents = computed(() => {
  return events.value.filter(ev => {
    if (filters.action && ev.action !== filters.action) return false
    if (filters.resourceType && ev.resourceType !== filters.resourceType) return false
    return true
  })
})

/**
 * RFC-090 §4.5 — group events by relative day so the eye gets a
 * rhythm (Today / Yesterday / weekday) instead of a flat 30-row
 * timestamp soup.
 */
const groupedEvents = computed(() => {
  const groups: { label: string; events: ActivityEvent[] }[] = []
  const today = startOfDay(new Date())
  const yesterday = new Date(today.getTime() - 86_400_000)
  const weekAgo = new Date(today.getTime() - 7 * 86_400_000)

  function bucket(d: Date): string {
    const day = startOfDay(d)
    if (day.getTime() === today.getTime()) return t('security.activity.today')
    if (day.getTime() === yesterday.getTime()) return t('security.activity.yesterday')
    if (day.getTime() > weekAgo.getTime()) {
      return d.toLocaleDateString(undefined, { weekday: 'long' })
    }
    return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
  }

  let lastLabel = ''
  for (const ev of filteredEvents.value) {
    const ts = ev.time || ev.createTime
    if (!ts) continue
    const d = new Date(ts)
    const label = bucket(d)
    if (label !== lastLabel) {
      groups.push({ label, events: [] })
      lastLabel = label
    }
    groups[groups.length - 1].events.push(ev)
  }
  return groups
})

/** Decompose detail map into structured [{key, value, code, empty}] rows
 *  while filtering out fields that are already in the hero block (action,
 *  resource, time) and string-encoded JSON noise from the audit pipeline. */
const changeEntries = computed<Array<{ key: string; value: string; code: boolean; empty: boolean }>>(() => {
  const ev = detailEvent.value
  if (!ev || !ev.detail) return []
  const skipKeys = new Set([
    'workspaceId', 'userAgent', 'detailJson',
    'time', 'createTime', 'action', 'resourceType', 'resourceName', 'resourceId',
    'username', 'ipAddress',
  ])
  const out: Array<{ key: string; value: string; code: boolean; empty: boolean }> = []

  // The audit pipeline historically nests JSON inside a string-typed
  // `detailJson` field. Try to expand it so the change list stays
  // first-class structured, not "open the raw payload to read".
  const merged: Record<string, any> = { ...ev.detail }
  const nested = ev.detail.detailJson
  if (typeof nested === 'string' && nested.trim().startsWith('{')) {
    try {
      const parsed = JSON.parse(nested)
      Object.assign(merged, parsed)
    } catch { /* leave detailJson as-is */ }
  }

  for (const [key, raw] of Object.entries(merged)) {
    if (skipKeys.has(key)) continue
    if (raw === undefined || raw === null || raw === '') {
      out.push({ key, value: '', code: false, empty: true })
      continue
    }
    if (typeof raw === 'object') {
      out.push({ key, value: JSON.stringify(raw), code: true, empty: false })
    } else {
      const s = String(raw)
      const code = s.length < 80 && (s.startsWith('{') || s.startsWith('[') || /^[\w./:_-]+$/.test(s))
      out.push({ key, value: s, code, empty: false })
    }
  }
  return out
})

/** Power-user payload (only when there's actually a detail map). */
const rawPayload = computed(() => {
  const ev = detailEvent.value
  if (!ev || !ev.detail) return ''
  return JSON.stringify(ev.detail, null, 2)
})

function startOfDay(d: Date): Date {
  const x = new Date(d)
  x.setHours(0, 0, 0, 0)
  return x
}

function setSourceFilter(value: string) {
  filters.source = value
  page.value = 1
  loadEvents()
}

async function loadEvents() {
  loading.value = true
  try {
    const res: any = await activityApi.feed({
      source: filters.source || undefined,
      page: page.value,
      size: pageSize.value,
    })
    events.value = res.data?.records || []
    // Force Number coercion — backend sometimes serializes Long as
    // string (Jackson big-number safety), and 'total > pageSize'
    // would fall back to string comparison ('30' > 20 → true by
    // accident, '5' > 20 → also true!). Keep arithmetic numeric.
    total.value = Number(res.data?.total ?? 0) || 0
  } catch {
    events.value = []
  } finally {
    loading.value = false
  }
}

function filterEventsLocally() { /* trigger recompute via reactive filter */ }

function openDetail(event: ActivityEvent) {
  detailEvent.value = event
  detailVisible.value = true
}

/** Natural-language verb. Audit actions get a clean past-tense translation;
 *  approval actions get domain-appropriate verbs. Falls back to the raw
 *  action so future enums don't silently render as "—". */
function verbFor(event: ActivityEvent): string {
  const a = (event.action || '').toUpperCase()
  const map: Record<string, string> = {
    CREATE: t('security.activity.verbs.create'),
    UPDATE: t('security.activity.verbs.update'),
    DELETE: t('security.activity.verbs.delete'),
    ENABLE: t('security.activity.verbs.enable'),
    DISABLE: t('security.activity.verbs.disable'),
    LOGIN: t('security.activity.verbs.login'),
    LOGOUT: t('security.activity.verbs.logout'),
    APPROVAL_GRANTED: t('security.activity.verbs.approvalGranted'),
    APPROVAL_DENIED: t('security.activity.verbs.approvalDenied'),
    APPROVAL_PENDING: t('security.activity.verbs.approvalPending'),
  }
  return map[a] || (event.action || '—')
}

function resourceLabel(event: ActivityEvent): string {
  const r = (event.resourceType || '').toUpperCase()
  const map: Record<string, string> = {
    AGENT: t('security.activity.resources.agent'),
    CHANNEL: t('security.activity.resources.channel'),
    SKILL: t('security.activity.resources.skill'),
    WIKI: t('security.activity.resources.wiki'),
    MEMBER: t('security.activity.resources.member'),
    WORKSPACE: t('security.activity.resources.workspace'),
    TOOL_APPROVAL: t('security.activity.resources.toolApproval'),
  }
  return map[r] || (event.resourceType || '')
}

function sourceLabel(event: ActivityEvent): string {
  if (event.source === 'approval') return t('security.activity.sourceApproval')
  if (event.source === 'audit') return t('security.activity.sourceAudit')
  return event.source || ''
}

function relativeTime(event: ActivityEvent): string {
  const ts = event.time || event.createTime
  if (!ts) return '—'
  const now = Date.now()
  const then = new Date(ts).getTime()
  const diffSec = Math.max(0, Math.floor((now - then) / 1000))
  if (diffSec < 60) return t('security.activity.justNow')
  const diffMin = Math.floor(diffSec / 60)
  if (diffMin < 60) return t('security.activity.minutesAgo', { n: diffMin })
  const diffHr = Math.floor(diffMin / 60)
  if (diffHr < 24) return t('security.activity.hoursAgo', { n: diffHr })
  const diffDay = Math.floor(diffHr / 24)
  if (diffDay < 7) return t('security.activity.daysAgo', { n: diffDay })
  return new Date(ts).toLocaleDateString()
}

function absoluteTime(event: ActivityEvent): string {
  const ts = event.time || event.createTime
  return ts ? new Date(ts).toLocaleString() : '—'
}

onMounted(() => {
  loadEvents()
})
</script>

<style scoped>
/* Layout — sit inside mc-page-inner's 28px padding; just stack our
   own sections with breathing room. The earlier `padding: 0` override
   collapsed the inset and let cards bleed into the frame border. */
.activity-page { gap: 18px; display: flex; flex-direction: column; }
.mc-page-header { margin-bottom: 6px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-shrink: 0; }
.btn-ghost {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 12px; border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated); color: var(--mc-text-secondary);
  border-radius: 999px; font-size: 12px; font-weight: 500; cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
  font-family: inherit;
}
.btn-ghost:hover:not(:disabled) { background: var(--mc-bg-muted); color: var(--mc-text-primary); border-color: var(--mc-border); }
.btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }

/* Filter bar — frosted glass surface */
.filter-chips {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 10px 14px;
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(14px) saturate(1.1);
  -webkit-backdrop-filter: blur(14px) saturate(1.1);
}
:root.dark .filter-chips {
  background: rgba(34, 26, 22, 0.55);
}
.filter-chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 12px; border: 1px solid var(--mc-border-light);
  background: rgba(255, 255, 255, 0.7); color: var(--mc-text-secondary);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border-radius: 999px; font-size: 12px; font-weight: 500; cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
  font-family: inherit;
}
:root.dark .filter-chip { background: rgba(42, 32, 26, 0.6); }
.filter-chip:hover { border-color: var(--mc-border); color: var(--mc-text-primary); }
/* Active chip — brand-coloured, glass-tinted */
.filter-chip.active {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  border-color: var(--mc-primary);
  font-weight: 600;
}
.chip-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.dot-all { background: var(--mc-text-tertiary); }
.dot-audit { background: #f59e0b; }
.dot-approval { background: #6366f1; }
.dot-tool { background: #10b981; }

.filter-spacer { flex: 1; }
.filter-more {
  background: none; border: none; color: var(--mc-text-tertiary);
  font-size: 12px; cursor: pointer; padding: 4px 8px;
  font-family: inherit;
}
.filter-more:hover { color: var(--mc-text-primary); }

.filter-extra { display: flex; gap: 8px; flex-wrap: wrap; }
.filter-select {
  padding: 6px 10px; border: 1px solid var(--mc-border-light);
  border-radius: 8px; background: var(--mc-bg-elevated);
  color: var(--mc-text-primary); font-size: 12px;
  font-family: inherit;
  min-width: 140px;
}

/* Fade transition — drop the brittle max-height clamp; rely on opacity
   only. The earlier 60px ceiling truncated the dropdown row when both
   selects wrapped. */
.fade-enter-active, .fade-leave-active { transition: opacity 0.18s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* Event feed — vertical stack, each row a self-contained card */
.event-feed { display: flex; flex-direction: column; gap: 0; }

.day-divider {
  display: flex; align-items: center; gap: 10px;
  padding: 18px 2px 8px;
  font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em;
  color: var(--mc-text-tertiary);
}
.day-divider:first-child { padding-top: 4px; }
.day-label { display: inline-block; }
.day-count {
  display: inline-block;
  padding: 1px 8px; border-radius: 999px;
  background: var(--mc-bg-sunken); color: var(--mc-text-tertiary);
  font-size: 10px; font-weight: 600; letter-spacing: 0;
  text-transform: none;
}

.event-row {
  width: 100%;
  display: flex; align-items: flex-start; gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  /* Frosted glass: semi-transparent surface + blur of whatever sits
     behind. Cheaper than a full opaque card and visually cohesive
     with the parent mc-page-frame which already uses backdrop-filter. */
  background: rgba(255, 255, 255, 0.62);
  backdrop-filter: blur(12px) saturate(1.08);
  -webkit-backdrop-filter: blur(12px) saturate(1.08);
  cursor: pointer;
  text-align: left;
  transition: background 0.18s, border-color 0.18s, box-shadow 0.18s;
  margin-bottom: 8px;
  font-family: inherit;
  font-size: inherit;
  color: inherit;
}
:root.dark .event-row { background: rgba(42, 32, 26, 0.55); }
.event-row:last-child { margin-bottom: 0; }
.event-row:hover {
  border-color: var(--mc-border);
  background: rgba(255, 255, 255, 0.85);
  box-shadow: var(--mc-shadow-soft);
}
:root.dark .event-row:hover { background: rgba(56, 42, 34, 0.7); }
/* Don't translateY on hover — the parent mc-page-frame has rounded
   corners + a glow ::before overlay; lifted cards visibly poked
   through the rounded border. */

.event-dot {
  width: 8px; height: 8px; border-radius: 50%;
  margin-top: 7px; flex-shrink: 0;
}

.event-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.event-sentence {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  font-size: 14px; line-height: 1.5;
  color: var(--mc-text-primary);
}
.event-actor { font-weight: 600; color: var(--mc-text-primary); }
.event-verb {
  font-weight: 500; padding: 1px 8px; border-radius: 6px;
  font-size: 12px;
}
.event-target { display: inline-flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.target-type { color: var(--mc-text-tertiary); font-size: 13px; }
.target-name {
  color: var(--mc-text-primary); font-weight: 500;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  overflow: hidden; text-overflow: ellipsis; max-width: 320px; white-space: nowrap;
}
.event-summary {
  font-size: 12px; color: var(--mc-text-tertiary);
  line-height: 1.5;
}
.event-time {
  font-size: 12px; color: var(--mc-text-tertiary);
  white-space: nowrap; flex-shrink: 0; padding-top: 2px;
}
.event-chevron {
  color: var(--mc-text-tertiary); font-size: 18px; line-height: 1;
  padding-top: 0; flex-shrink: 0; opacity: 0;
  transition: opacity 0.15s, transform 0.15s;
}
.event-row:hover .event-chevron { opacity: 1; transform: translateX(2px); }

/* Verb tinting — green = create/grant, red = delete/deny, etc. */
.verb-create, .verb-enable, .verb-approval_granted { color: #16a34a; background: rgba(34, 197, 94, 0.10); }
.verb-update { color: #2563eb; background: rgba(59, 130, 246, 0.10); }
.verb-delete, .verb-approval_denied { color: #dc2626; background: rgba(239, 68, 68, 0.10); }
.verb-disable, .verb-approval_pending { color: #d97706; background: rgba(245, 158, 11, 0.10); }
.verb-login, .verb-logout { color: #7c3aed; background: rgba(139, 92, 246, 0.10); }

:root.dark .verb-create, :root.dark .verb-enable, :root.dark .verb-approval_granted { color: #4ade80; background: rgba(34, 197, 94, 0.18); }
:root.dark .verb-update { color: #60a5fa; background: rgba(59, 130, 246, 0.18); }
:root.dark .verb-delete, :root.dark .verb-approval_denied { color: #fca5a5; background: rgba(239, 68, 68, 0.18); }
:root.dark .verb-disable, :root.dark .verb-approval_pending { color: #fbbf24; background: rgba(245, 158, 11, 0.18); }
:root.dark .verb-login, :root.dark .verb-logout { color: #c4b5fd; background: rgba(139, 92, 246, 0.18); }

.feed-empty {
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  padding: 60px 20px;
  color: var(--mc-text-tertiary); font-size: 14px;
  text-align: center;
  border: 1px dashed var(--mc-border-light);
  border-radius: 12px;
  background: var(--mc-bg-muted);
}
.feed-empty p { margin: 0; }
.empty-icon { font-size: 32px; }
.empty-hint { font-size: 12px; max-width: 320px; line-height: 1.6; }

.pagination {
  display: flex; align-items: center; justify-content: center;
  margin-top: 14px;
  padding: 8px 14px;
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(14px) saturate(1.1);
  -webkit-backdrop-filter: blur(14px) saturate(1.1);
}
:root.dark .pagination { background: rgba(34, 26, 22, 0.55); }
.page-info { font-size: 12px; color: var(--mc-text-tertiary); }

/* Element Plus pagination — keep EP defaults for buttons/numbers so
   they stay readable; only inherit our font and tint the active page
   with the brand color. The earlier overrides set button text to
   secondary-grey on a translucent white pill, which against the
   glass container effectively rendered numbers invisible. */
.pagination :deep(.el-pagination) {
  font-family: inherit;
  --el-color-primary: var(--mc-primary);
}

/* Detail drawer — wider, no internal max-height frame */
.detail-shell {
  padding: 24px 28px 32px;
  display: flex; flex-direction: column; gap: 22px;
}

.detail-hero {
  /* Glass hero block. No negative margin — el-drawer body has
     overflow constraints and a -8px outdent caused the rounded
     border to clip on the sides. */
  position: relative;
  padding: 18px 18px 22px;
  border-radius: 16px;
  border: 1px solid var(--mc-border-light);
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(16px) saturate(1.15);
  -webkit-backdrop-filter: blur(16px) saturate(1.15);
}
:root.dark .detail-hero { background: rgba(42, 32, 26, 0.55); }
.detail-dot {
  position: absolute; left: 18px; top: 24px;
  width: 10px; height: 10px; border-radius: 50%;
  box-shadow: 0 0 0 4px rgba(255, 255, 255, 0.4);
}
:root.dark .detail-dot { box-shadow: 0 0 0 4px rgba(255, 255, 255, 0.08); }
.detail-headline {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin: 0 0 6px;
  padding-left: 22px;  /* leave room for the dot at left: 18px */
  font-size: 22px; font-weight: 700; line-height: 1.3;
  color: var(--mc-text-primary);
  letter-spacing: -0.01em;
}
.detail-target-name { margin-left: 22px; }
.detail-meta { padding-left: 22px; }
.detail-actor { font-weight: 700; }
.detail-verb {
  font-size: 16px; font-weight: 600;
  padding: 2px 10px; border-radius: 8px;
}
.detail-target-type { color: var(--mc-text-secondary); font-weight: 500; font-size: 18px; }
.detail-target-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 15px; color: var(--mc-text-primary);
  background: var(--mc-bg-sunken);
  padding: 4px 10px; border-radius: 6px;
  display: inline-block; margin-top: 4px;
  word-break: break-all;
}
.detail-meta {
  display: flex; align-items: center; gap: 8px;
  margin-top: 10px;
  font-size: 12px; color: var(--mc-text-tertiary);
}
.meta-sep { opacity: 0.5; }

.detail-block { display: flex; flex-direction: column; gap: 10px; }
.detail-block-title {
  font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em;
  color: var(--mc-text-tertiary);
  margin: 0;
}
.change-list { display: flex; flex-direction: column; gap: 0; border-top: 1px solid var(--mc-border-light); }
.change-item {
  display: flex; align-items: flex-start; gap: 16px;
  padding: 10px 0;
  border-bottom: 1px solid var(--mc-border-light);
  font-size: 13px;
  line-height: 1.5;
}
.change-item:last-child { border-bottom: none; }
.change-key {
  width: 140px; flex-shrink: 0;
  color: var(--mc-text-tertiary); font-size: 12px; font-weight: 600;
}
.change-value {
  flex: 1; min-width: 0;
  color: var(--mc-text-primary);
  word-break: break-word;
}
.change-value code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: var(--mc-bg-sunken);
  padding: 2px 6px; border-radius: 4px;
}
.change-value.is-empty { color: var(--mc-text-tertiary); font-style: italic; }

.detail-raw {
  margin-top: 4px;
  border-top: 1px solid var(--mc-border-light);
  padding-top: 14px;
}
.detail-raw summary {
  cursor: pointer; font-size: 11px; font-weight: 700;
  text-transform: uppercase; letter-spacing: 0.08em;
  color: var(--mc-text-tertiary);
  padding: 4px 0;
}
.detail-raw summary:hover { color: var(--mc-text-secondary); }
.detail-pre {
  margin-top: 8px;
  background: var(--mc-bg-sunken);
  padding: 14px; border-radius: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px; line-height: 1.6;
  color: var(--mc-text-secondary);
  white-space: pre-wrap; word-break: break-word;
}

/* ============ Mobile / narrow viewport (≤ 768px) ============ */
@media (max-width: 768px) {
  /* Header — stack title and refresh button vertically */
  .activity-page :deep(.mc-page-header) {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
  }
  .header-actions { justify-content: flex-end; }

  /* Filter chips — drop the "More filters" link out of one row */
  .filter-chips { padding: 8px 10px; gap: 6px; }
  .filter-chip { padding: 5px 10px; font-size: 11px; }
  .filter-spacer { flex-basis: 100%; height: 0; }

  /* Event row — let target wrap naturally; time on a second line */
  .event-row {
    padding: 12px 14px;
    flex-wrap: wrap;
    gap: 10px;
  }
  .event-body { flex: 1 1 100%; min-width: 0; }
  .event-sentence {
    font-size: 13px;
    line-height: 1.55;
  }
  .target-name {
    max-width: 100%;
    white-space: normal;
    word-break: break-all;
  }
  .event-time {
    /* Push time to the right edge of its own row under the body */
    flex: 0 0 auto;
    margin-left: auto;
    font-size: 11px;
    padding-top: 0;
  }
  .event-chevron { display: none; }
  .event-dot { margin-top: 5px; }

  /* Day divider — tighter rhythm on mobile */
  .day-divider { padding: 14px 2px 6px; }

  /* Detail drawer — drawer goes full-width via :size binding;
     here we collapse the inner padding and headline scale. */
  .detail-shell { padding: 16px 16px 24px; gap: 18px; }
  .detail-hero { padding: 14px 14px 18px; }
  .detail-dot { left: 14px; top: 20px; }
  .detail-headline {
    font-size: 18px;
    padding-left: 18px;
    gap: 6px;
  }
  .detail-verb { font-size: 13px; padding: 1px 8px; }
  .detail-target-type { font-size: 14px; }
  .detail-target-name {
    font-size: 13px;
    margin-left: 18px;
    word-break: break-all;
  }
  .detail-meta { padding-left: 18px; flex-wrap: wrap; }
  .change-key { width: 100px; font-size: 11px; }
  .change-item { gap: 10px; padding: 8px 0; font-size: 12px; }

  /* Pagination — :small=true on EP shrinks; container loosens padding */
  .pagination { padding: 6px 8px; margin-top: 10px; }
}

/* ============ Very small phones (≤ 480px) ============ */
@media (max-width: 480px) {
  .filter-chips { padding: 6px 8px; }
  .event-row { padding: 10px 12px; }
  .event-sentence { font-size: 12px; gap: 4px; }
  .event-actor { font-weight: 700; }
  .target-name { font-size: 12px; }
  .detail-headline { font-size: 16px; }
  .detail-target-name { font-size: 12px; }
  .change-key { width: 84px; }
}
</style>
