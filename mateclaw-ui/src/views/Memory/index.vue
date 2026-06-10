<template>
  <div class="mc-page-shell memory-shell">
    <div class="mc-page-frame memory-frame">
      <div class="memory-layout mc-surface-card">
        <!-- Left: Timeline -->
        <div class="memory-sidebar">
          <div class="sidebar-header">
            <div class="sidebar-header-copy">
              <div class="panel-kicker">Memory</div>
              <h2 class="panel-title">{{ t('memory.title') }}</h2>
            </div>
            <button class="dream-trigger-btn" @click="startDream" title="Dream">
              <MemoryIcon name="moon" :size="16" />
            </button>
          </div>

          <!-- Employee selector -->
          <div class="agent-selector">
            <AgentPickerDialog
              v-model="selectedAgentId"
              :agents="agents"
              block
              :placeholder="t('memory.selectAgent')"
              @change="onAgentPicked"
            />
          </div>

          <!-- Tab nav (segment control) -->
          <div class="memory-nav">
            <button v-for="tab in tabs" :key="tab.key"
              class="memory-nav-btn" :class="{ active: activeTab === tab.key, disabled: tab.disabled }"
              :disabled="tab.disabled" @click="activeTab = tab.key">
              {{ tab.label }}
            </button>
          </div>

          <!-- Morning Card -->
          <MorningCard v-if="selectedAgentId" :agent-id="selectedAgentId" />

          <!-- Dream cards -->
          <div class="timeline-scroll">
            <MemorySkeleton v-if="store.loading" :count="4" />
            <MemoryEmptyState v-else-if="store.error" icon="alert" :text="store.error">
              <button class="retry-btn" @click="loadReports">{{ t('memory.retry') }}</button>
            </MemoryEmptyState>
            <MemoryEmptyState v-else-if="store.reports.length === 0" icon="moon" :text="t('memory.noReports')" />
            <template v-else>
              <div v-for="report in store.reports" :key="report.id"
                class="dream-card" :class="{ selected: selectedReportId === report.id }"
                @click="selectReport(report)">
                <div class="card-row-top">
                  <span class="status-dot" :class="report.status.toLowerCase()" />
                  <span class="card-mode">{{ report.mode === 'FOCUSED' ? t('memory.modeFocused') : t('memory.modeNightly') }}</span>
                  <span class="card-time">{{ relTime(report.startedAt) }}</span>
                </div>
                <div v-if="report.topic" class="card-topic">{{ report.topic }}</div>
                <div class="card-stats">
                  <span class="stat-up">+{{ report.promotedCount }}</span>
                  <span class="stat-down">-{{ report.rejectedCount }}</span>
                  <span class="stat-total">{{ report.candidateCount }} {{ t('memory.candidates') }}</span>
                </div>
              </div>
              <div v-if="store.total > 20" class="load-more">
                <button class="load-more-btn" @click="currentPage++; loadReports()">{{ t('memory.loadMore') }}</button>
              </div>
            </template>
          </div>
        </div>

        <!-- Right: Content panel -->
        <div class="memory-detail">
          <!-- Memory tab -->
          <template v-if="activeTab === 'memory' && selectedAgentId">
            <MemoryBrowser :agent-id="selectedAgentId" />
          </template>

          <!-- Facts tab -->
          <template v-else-if="activeTab === 'facts' && selectedAgentId">
            <FactList :agent-id="selectedAgentId" />
          </template>

          <!-- Timeline tab detail -->
          <template v-else-if="activeTab === 'timeline'">
          <!-- Inline dream input (replaces dialog) -->
          <Transition name="slide-down">
            <div v-if="dreamInputOpen" class="dream-input-area">
              <div class="dream-input-header">
                <MemoryIcon name="moon" :size="16" />
                <span>Dream</span>
                <button class="close-btn" @click="dreamInputOpen = false">&times;</button>
              </div>
              <p class="dream-input-desc">{{ t('memory.focused.desc') }}</p>
              <input v-model="dreamTopic" class="dream-topic-input" :placeholder="t('memory.focused.placeholder')" @keydown.enter="triggerDream" />
              <button class="dream-go-btn" :disabled="!dreamTopic.trim() || dreamRunning" @click="triggerDream">
                {{ dreamRunning ? '...' : t('memory.focused.trigger') }}
              </button>
            </div>
          </Transition>

          <!-- Report detail -->
          <template v-if="store.currentReport">
            <div class="detail-header">
              <div class="detail-mode-badge" :class="store.currentReport.mode.toLowerCase()">
                {{ store.currentReport.mode === 'FOCUSED' ? t('memory.modeFocused') : t('memory.modeNightly') }}
              </div>
              <span v-if="store.currentReport.topic" class="detail-topic">{{ store.currentReport.topic }}</span>
              <div class="detail-status" :class="store.currentReport.status.toLowerCase()">
                {{ t('memory.status.' + store.currentReport.status.toLowerCase()) }}
              </div>
            </div>

            <div class="detail-grid">
              <div class="detail-kv"><span class="kv-label">{{ t('memory.report.time') }}</span><span class="kv-value">{{ fmtTime(store.currentReport.startedAt) }}</span></div>
              <div class="detail-kv"><span class="kv-label">{{ t('memory.report.candidates') }}</span><span class="kv-value">{{ store.currentReport.candidateCount }}</span></div>
              <div class="detail-kv"><span class="kv-label">{{ t('memory.report.promoted') }}</span><span class="kv-value text-up">{{ store.currentReport.promotedCount }}</span></div>
              <div class="detail-kv"><span class="kv-label">{{ t('memory.report.rejected') }}</span><span class="kv-value text-down">{{ store.currentReport.rejectedCount }}</span></div>
              <div class="detail-kv"><span class="kv-label">{{ t('memory.report.trigger') }}</span><span class="kv-value">{{ store.currentReport.triggerSource }}</span></div>
            </div>

            <div v-if="store.currentReport.llmReason" class="detail-section">
              <div class="section-label">{{ t('memory.report.reason') }}</div>
              <p class="section-body">{{ store.currentReport.llmReason }}</p>
            </div>

            <div v-if="store.currentReport.memoryDiff" class="detail-section">
              <div class="section-label">{{ t('memory.report.diff') }}</div>
              <pre class="diff-block">{{ store.currentReport.memoryDiff }}</pre>
            </div>

            <div v-if="store.currentReport.errorMessage" class="detail-section error">
              <div class="section-label">Error</div>
              <p class="section-body">{{ store.currentReport.errorMessage }}</p>
            </div>
          </template>
          <template v-else>
            <MemoryEmptyState icon="memory" :text="t('memory.desc')" large />
          </template>
          </template><!-- /timeline tab -->
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { http } from '@/api'
import { useAgentStore } from '@/stores/useAgentStore'
import { useMemoryStore, type DreamReportItem } from '@/stores/useMemoryStore'
import AgentPickerDialog from '@/components/common/AgentPickerDialog.vue'
import MorningCard from './components/MorningCard.vue'
import FactList from './components/FactList.vue'
import MemoryBrowser from './components/MemoryBrowser.vue'
import MemoryIcon from './components/MemoryIcon.vue'
import MemorySkeleton from './components/MemorySkeleton.vue'
import MemoryEmptyState from './components/MemoryEmptyState.vue'

const { t } = useI18n()
const agentStore = useAgentStore()
const store = useMemoryStore()

const agents = ref<any[]>([])
const selectedAgentId = ref<string | number>('')
const activeTab = ref('timeline')
const selectedReportId = ref<string | null>(null)
const currentPage = ref(1)

// Dream input (inline, no dialog)
const dreamInputOpen = ref(false)
const dreamTopic = ref('')
const dreamRunning = ref(false)

const tabs = computed(() => [
  { key: 'timeline', label: t('memory.tabTimeline'), disabled: false },
  { key: 'memory', label: t('memory.tabMemory'), disabled: false },
  { key: 'facts', label: t('memory.tabFacts'), disabled: false },
])

onMounted(async () => {
  await agentStore.fetchAgents()
  agents.value = agentStore.agents
  if (agents.value.length > 0) {
    selectedAgentId.value = agents.value[0].id
  }
})

watch(selectedAgentId, (id) => {
  if (id) { store.subscribeEvents(id); loadReports() }
  else store.unsubscribeEvents()
})

onUnmounted(() => store.unsubscribeEvents())

function onAgentPicked() {
  selectedReportId.value = null
  currentPage.value = 1
  store.currentReport = null
}

function loadReports() {
  if (selectedAgentId.value) store.fetchReports(selectedAgentId.value, currentPage.value, 20)
}

function selectReport(report: DreamReportItem) {
  selectedReportId.value = report.id
  if (selectedAgentId.value) store.fetchReport(selectedAgentId.value, report.id)
}

function startDream() { dreamInputOpen.value = true }

async function triggerDream() {
  if (!dreamTopic.value.trim() || !selectedAgentId.value) return
  dreamRunning.value = true
  try {
    await http.post(`/memory/${selectedAgentId.value}/dreaming/focused`, { topic: dreamTopic.value.trim() })
    mcToast.success(t('memory.focused.success'))
    dreamTopic.value = ''
    dreamInputOpen.value = false
    loadReports()
  } catch (e: any) {
    mcToast.error(e.message || 'Dream failed')
  } finally {
    dreamRunning.value = false
  }
}

function relTime(iso: string) {
  if (!iso) return ''
  const ms = Date.now() - new Date(iso).getTime()
  const h = Math.floor(ms / 3600000)
  if (h < 1) return t('memory.time.justNow')
  if (h < 24) return t('memory.time.hoursAgo', { n: h })
  const d = Math.floor(h / 24)
  if (d < 7) return t('memory.time.daysAgo', { n: d })
  return new Date(iso).toLocaleDateString()
}

function fmtTime(iso: string) {
  return iso ? new Date(iso).toLocaleString() : ''
}
</script>

<style scoped>
/* ========== Shell ========== */
.memory-frame { max-width: 1200px; }
.memory-layout {
  display: flex;
  min-height: calc(100vh - 120px);
  overflow: hidden;
}

/* ========== Left sidebar ========== */
.memory-sidebar {
  width: 340px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--mc-border-light);
  overflow: hidden;
}
.sidebar-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 20px 16px 12px;
}
.sidebar-header-copy { display: flex; flex-direction: column; }
.panel-kicker {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  color: var(--mc-text-tertiary);
}
.panel-title {
  font-size: 18px;
  font-weight: 700;
  margin: 2px 0 0;
  color: var(--mc-text-primary);
}
.dream-trigger-btn {
  width: 32px; height: 32px;
  display: flex; align-items: center; justify-content: center;
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: all 0.15s;
}
.dream-trigger-btn:hover {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
  background: var(--mc-bg-elevated);
}

/* Employee selector */
.agent-selector { position: relative; padding: 0 12px 8px; }

/* Segment control */
.memory-nav {
  display: flex; gap: 2px; margin: 4px 12px 8px; padding: 3px;
  background: var(--mc-bg-sunken); border-radius: 10px; width: fit-content;
}
.memory-nav-btn {
  padding: 5px 14px; border: none; border-radius: 8px; background: transparent;
  font-size: 12px; font-weight: 500; color: var(--mc-text-tertiary);
  cursor: pointer; transition: all 0.15s;
}
.memory-nav-btn:hover:not(.disabled) { color: var(--mc-text-primary); }
.memory-nav-btn.active {
  background: var(--mc-bg-elevated); color: var(--mc-text-primary);
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}
.memory-nav-btn.disabled { opacity: 0.35; cursor: not-allowed; }

/* Timeline scroll */
.timeline-scroll { flex: 1; overflow-y: auto; padding: 0 8px 12px; }
.dream-card {
  padding: 12px 14px; margin-bottom: 6px; border-radius: 12px;
  cursor: pointer; transition: all 0.12s; border: 1px solid transparent;
}
.dream-card:hover { background: var(--mc-bg-sunken); }
.dream-card.selected { background: var(--mc-primary-bg); border-color: var(--mc-primary); }
.card-row-top { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--mc-text-tertiary); }
.status-dot { width: 7px; height: 7px; border-radius: 50%; }
.status-dot.success { background: #34c759; }
.status-dot.failed { background: #ff3b30; }
.status-dot.skipped { background: #ff9f0a; }
.card-mode { font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }
.card-time { margin-left: auto; }
.card-topic { margin-top: 4px; font-size: 13px; font-weight: 500; color: var(--mc-text-primary); }
.card-stats { margin-top: 4px; display: flex; gap: 8px; font-size: 11px; }
.stat-up { color: #34c759; font-weight: 600; }
.stat-down { color: var(--mc-text-tertiary); }
.stat-total { color: var(--mc-text-tertiary); }
.load-more { padding: 8px; text-align: center; }
.load-more-btn {
  padding: 6px 16px; border: 1px solid var(--mc-border); border-radius: 8px;
  background: transparent; font-size: 12px; color: var(--mc-text-secondary);
  cursor: pointer; transition: all 0.15s;
}
.load-more-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); }

/* Timeline retry button (slotted into MemoryEmptyState) */
.retry-btn {
  margin-top: 8px; padding: 6px 16px; border: 1px solid var(--mc-border); border-radius: 8px;
  background: transparent; font-size: 12px; color: var(--mc-text-secondary); cursor: pointer;
}
.retry-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); }

/* ========== Right detail ========== */
.memory-detail {
  flex: 1; min-width: 0; padding: 24px; overflow-y: auto;
}

/* Inline dream input */
.dream-input-area {
  padding: 16px; margin-bottom: 20px; border-radius: 14px;
  background: var(--mc-bg-sunken); border: 1px solid var(--mc-border-light);
}
.dream-input-header {
  display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 600;
  color: var(--mc-text-primary); margin-bottom: 8px;
}
.close-btn {
  margin-left: auto; background: none; border: none; font-size: 18px;
  color: var(--mc-text-tertiary); cursor: pointer; line-height: 1;
}
.dream-input-desc { font-size: 12px; color: var(--mc-text-tertiary); margin: 0 0 10px; line-height: 1.4; }
.dream-topic-input {
  width: 100%; padding: 10px 12px; border: 1px solid var(--mc-border); border-radius: 10px;
  background: var(--mc-bg-elevated); font-size: 13px; color: var(--mc-text-primary);
  outline: none; transition: border-color 0.15s;
}
.dream-topic-input:focus { border-color: var(--mc-primary); }
.dream-topic-input::placeholder { color: var(--mc-text-tertiary); }
.dream-go-btn {
  margin-top: 10px; padding: 8px 20px; border: none; border-radius: 10px;
  background: var(--mc-primary); color: #fff; font-size: 13px; font-weight: 500;
  cursor: pointer; transition: opacity 0.15s;
}
.dream-go-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* Detail header */
.detail-header { display: flex; align-items: center; gap: 10px; margin-bottom: 20px; flex-wrap: wrap; }
.detail-mode-badge {
  padding: 4px 10px; border-radius: 8px; font-size: 11px; font-weight: 600;
  text-transform: uppercase; letter-spacing: 0.5px;
}
.detail-mode-badge.nightly { background: rgba(88,86,214,0.12); color: #5856d6; }
.detail-mode-badge.focused { background: rgba(255,159,10,0.12); color: #ff9f0a; }
.detail-topic { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); }
.detail-status { margin-left: auto; font-size: 11px; font-weight: 500; padding: 3px 8px; border-radius: 6px; }
.detail-status.success { background: rgba(52,199,89,0.12); color: #34c759; }
.detail-status.failed { background: rgba(255,59,48,0.12); color: #ff3b30; }
.detail-status.skipped { background: rgba(255,159,10,0.12); color: #ff9f0a; }

/* KV grid */
.detail-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px; margin-bottom: 24px;
}
.detail-kv {
  padding: 12px 14px; border-radius: 12px; background: var(--mc-bg-sunken);
  display: flex; flex-direction: column; gap: 4px;
}
.kv-label { font-size: 11px; color: var(--mc-text-tertiary); font-weight: 500; }
.kv-value { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); }
.kv-value.text-up { color: #34c759; }
.kv-value.text-down { color: #ff3b30; }

/* Sections */
.detail-section { margin-bottom: 20px; }
.section-label { font-size: 12px; font-weight: 600; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 6px; }
.section-body { font-size: 13px; color: var(--mc-text-secondary); line-height: 1.6; margin: 0; }
.diff-block {
  margin: 0; padding: 12px 14px; border-radius: 10px; background: var(--mc-bg-sunken);
  font-size: 12px; font-family: 'SF Mono', Menlo, monospace; color: var(--mc-text-secondary);
  white-space: pre-wrap; line-height: 1.5; border: 1px solid var(--mc-border-light);
}
.detail-section.error .section-body { color: #ff3b30; }

/* ========== Transitions ========== */
.slide-down-enter-active, .slide-down-leave-active { transition: all 0.2s ease; }
.slide-down-enter-from, .slide-down-leave-to { opacity: 0; transform: translateY(-8px); }

/* ========== Responsive ========== */
@media (max-width: 768px) {
  .memory-layout { flex-direction: column; }
  .memory-sidebar { width: 100%; max-height: 50vh; border-right: none; border-bottom: 1px solid var(--mc-border-light); }
}
</style>
