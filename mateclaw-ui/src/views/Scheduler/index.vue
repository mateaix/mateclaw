<template>
  <div class="sched-page">
    <header class="sched-header">
      <div class="sched-lead">
        <div class="sched-kicker">{{ t('scheduler.kicker') }}</div>
        <h1 class="sched-title">{{ t('scheduler.title') }}</h1>
        <p class="sched-desc">{{ t('scheduler.desc') }}</p>
      </div>
      <button class="sched-action" @click="onAction">
        <svg v-if="activeTab === 'history'" width="16" height="16" viewBox="0 0 24 24"
             fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M23 4v6h-6" /><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
        </svg>
        <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        {{ actionLabel }}
      </button>
    </header>

    <nav class="sched-tabs" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        role="tab"
        class="sched-tab"
        :class="{ active: activeTab === tab.id }"
        :aria-selected="activeTab === tab.id"
        @click="activeTab = tab.id"
      >
        {{ tab.label }}
        <span v-if="counts[tab.id]" class="tab-badge">{{ counts[tab.id] }}</span>
      </button>
    </nav>

    <div class="sched-body">
      <CronJobsPanel v-if="activeTab === 'jobs'" ref="jobsPanel" @count="counts.jobs = $event" />
      <TriggersPanel v-else-if="activeTab === 'triggers'" ref="triggersPanel" @count="counts.triggers = $event" />
      <RunHistoryPanel v-else ref="historyPanel" @count="counts.history = $event" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import CronJobsPanel from './CronJobsPanel.vue'
import TriggersPanel from './TriggersPanel.vue'
import RunHistoryPanel from './RunHistoryPanel.vue'

type TabId = 'jobs' | 'triggers' | 'history'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const TAB_IDS: TabId[] = ['jobs', 'triggers', 'history']

function isTabId(value: unknown): value is TabId {
  return typeof value === 'string' && (TAB_IDS as string[]).includes(value)
}

// The active tab is mirrored into the `?tab=` query so a reload — or a
// redirect from the legacy /settings/triggers route — lands on the right tab.
const activeTab = ref<TabId>(isTabId(route.query.tab) ? route.query.tab : 'jobs')

watch(activeTab, (tab) => {
  if (route.query.tab !== tab) {
    router.replace({ query: { ...route.query, tab } })
  }
})
watch(
  () => route.query.tab,
  (tab) => {
    if (isTabId(tab) && tab !== activeTab.value) activeTab.value = tab
  },
)

const tabs = computed(() => [
  { id: 'jobs' as TabId, label: t('scheduler.tabs.jobs') },
  { id: 'triggers' as TabId, label: t('scheduler.tabs.triggers') },
  { id: 'history' as TabId, label: t('scheduler.tabs.history') },
])

// Per-tab item counts shown as badges. Each panel emits `count` on mount and
// whenever its list size changes; the value sticks until the next emit.
const counts = ref<Record<TabId, number>>({ jobs: 0, triggers: 0, history: 0 })

const actionLabel = computed(() => {
  if (activeTab.value === 'history') return t('scheduler.actions.refresh')
  if (activeTab.value === 'triggers') return t('scheduler.actions.newTrigger')
  return t('scheduler.actions.newJob')
})

// The header button drives the active panel through the method it exposes:
// `openCreate` for the job/trigger tabs, `refresh` for the history tab. Each
// panel keeps its own ref so the call always lands on the mounted instance.
const jobsPanel = ref<InstanceType<typeof CronJobsPanel> | null>(null)
const triggersPanel = ref<InstanceType<typeof TriggersPanel> | null>(null)
const historyPanel = ref<InstanceType<typeof RunHistoryPanel> | null>(null)
function onAction() {
  if (activeTab.value === 'jobs') jobsPanel.value?.openCreate()
  else if (activeTab.value === 'triggers') triggersPanel.value?.openCreate()
  else historyPanel.value?.refresh()
}
</script>

<style scoped>
.sched-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.sched-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.sched-lead {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sched-kicker {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  padding: 6px 12px;
  border: 1px solid color-mix(in srgb, var(--mc-primary) 18%, transparent);
  border-radius: 999px;
  background: color-mix(in srgb, var(--mc-primary-bg) 72%, var(--mc-bg-elevated) 28%);
  color: var(--mc-primary-hover);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.sched-title {
  font-size: clamp(24px, 3vw, 34px);
  line-height: 1.05;
  font-weight: 800;
  color: var(--mc-text-primary);
  margin: 0;
}

.sched-desc {
  max-width: 620px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--mc-text-secondary);
  margin: 0;
}

.sched-action {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: var(--mc-primary);
  color: white;
  border: none;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
}
.sched-action:hover { background: var(--mc-primary-hover); }

.sched-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--mc-border);
}

.sched-tab {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border: none;
  background: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}
.sched-tab:hover { color: var(--mc-text-primary); }
.sched-tab.active {
  color: var(--mc-primary);
  border-bottom-color: var(--mc-primary);
}

.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
  font-size: 11px;
  font-weight: 700;
}
.sched-tab.active .tab-badge {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}

.sched-body {
  min-height: 0;
}

@media (max-width: 720px) {
  .sched-header {
    flex-direction: column;
    align-items: stretch;
  }
  .sched-action {
    justify-content: center;
  }
  .sched-tabs {
    overflow-x: auto;
  }
  .sched-tab {
    white-space: nowrap;
  }
}
</style>
