<template>
  <div class="content-calendar">
    <div class="page-header">
      <h2>{{ t('contentCalendar.title') }}</h2>
      <p class="subtitle">{{ t('contentCalendar.subtitle') }}</p>
    </div>

    <!-- Status summary cards (glass) -->
    <div class="summary-cards">
      <button
        v-for="card in summaryCards"
        :key="card.key"
        class="summary-card glass"
        :class="[{ active: statusFilter === card.key }, `accent-${card.tone}`]"
        @click="toggleStatus(card.key)"
      >
        <span class="card-dot" :class="`dot-${card.tone}`"></span>
        <div class="card-count">{{ card.count }}</div>
        <div class="card-label">{{ card.label }}</div>
      </button>
    </div>

    <!-- Filter bar (glass) -->
    <div class="filters glass">
      <el-select v-model="platformFilter" :placeholder="t('contentCalendar.allPlatforms')" clearable @change="reload" class="platform-select">
        <el-option label="公众号" value="gzh" />
        <el-option label="小红书" value="xhs" />
      </el-select>
      <button class="refresh-btn" @click="reload">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
        {{ t('contentCalendar.refresh') }}
      </button>
    </div>

    <!-- Table (glass card) -->
    <div class="table-card glass">
      <el-table :data="rows" v-loading="loading" class="calendar-table" :show-header="true">
        <el-table-column :label="t('contentCalendar.platform')" width="110">
          <template #default="{ row }">
            <span class="platform-pill" :class="row.platform === 'gzh' ? 'pill-gzh' : 'pill-xhs'">
              {{ row.platform === 'gzh' ? '公众号' : '小红书' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('contentCalendar.colTitle')" min-width="240">
          <template #default="{ row }">
            <a v-if="row.previewUrl" :href="row.previewUrl" target="_blank" rel="noopener" class="title-link">
              {{ row.title || '(无标题)' }}
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="ext-icon"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
            </a>
            <span v-else>{{ row.title || '(无标题)' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="topic" :label="t('contentCalendar.topic')" min-width="150" show-overflow-tooltip>
          <template #default="{ row }"><span class="topic-text">{{ row.topic || '—' }}</span></template>
        </el-table-column>
        <el-table-column :label="t('contentCalendar.status')" width="120">
          <template #default="{ row }">
            <span class="status-chip" :class="`chip-${row.status || 'unknown'}`">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('contentCalendar.createTime')" width="160">
          <template #default="{ row }"><span class="time-text">{{ formatTime(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('contentCalendar.publishTime')" width="160">
          <template #default="{ row }"><span class="time-text">{{ formatTime(row.publishTime) || '—' }}</span></template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="prev, pager, next, total"
          :total="total"
          :page-size="pageSize"
          :current-page="page"
          @current-change="onPageChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { contentItemApi } from '@/api'

const { t } = useI18n()

interface ContentItem {
  id: string | number
  platform: string
  topic?: string
  title?: string
  status?: string
  previewUrl?: string
  createTime?: string
  publishTime?: string
}

const rows = ref<ContentItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const platformFilter = ref('')
const statusFilter = ref('')
const summary = ref<Record<string, number>>({})

const summaryCards = computed(() => [
  { key: '', label: t('contentCalendar.total'), count: summary.value.total ?? 0, tone: 'primary' },
  { key: 'packaged', label: t('contentCalendar.packaged'), count: summary.value.packaged ?? 0, tone: 'amber' },
  { key: 'draft', label: t('contentCalendar.draft'), count: summary.value.draft ?? 0, tone: 'gray' },
  { key: 'published', label: t('contentCalendar.published'), count: summary.value.published ?? 0, tone: 'green' },
])

function statusLabel(s?: string) {
  return t(`contentCalendar.st_${s || 'unknown'}`)
}
function formatTime(v?: string) {
  if (!v) return ''
  return v.replace('T', ' ').slice(0, 16)
}

async function loadSummary() {
  try {
    const res: any = await contentItemApi.summary()
    summary.value = res.data || res || {}
  } catch {
    summary.value = {}
  }
}

async function reload() {
  loading.value = true
  try {
    const res: any = await contentItemApi.list({
      page: page.value,
      size: pageSize.value,
      platform: platformFilter.value || undefined,
      status: statusFilter.value || undefined,
    })
    const data = res.data || res
    rows.value = data.records || data.list || []
    total.value = Number(data.total ?? rows.value.length)
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function toggleStatus(key: string) {
  statusFilter.value = statusFilter.value === key ? '' : key
  page.value = 1
  reload()
}
function onPageChange(p: number) {
  page.value = p
  reload()
}

onMounted(() => {
  loadSummary()
  reload()
})
</script>

<style scoped>
.content-calendar { padding: 24px 24px 40px; max-width: 1180px; margin: 0 auto; }
.page-header h2 { margin: 0 0 4px; font-size: 21px; font-weight: 700; color: var(--mc-text-primary); }
.subtitle { margin: 0 0 20px; color: var(--mc-text-tertiary); font-size: 13px; }

/* Frosted-glass surface shared by cards, filter bar, and table wrapper. */
.glass {
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(16px) saturate(1.15);
  -webkit-backdrop-filter: blur(16px) saturate(1.15);
  border: 1px solid var(--mc-border);
  box-shadow: var(--mc-shadow-soft);
}
html.dark .glass { background: rgba(255, 255, 255, 0.045); border-color: rgba(255, 255, 255, 0.12); }

/* Summary cards */
.summary-cards { display: flex; gap: 14px; margin-bottom: 16px; flex-wrap: wrap; }
.summary-card {
  position: relative; flex: 1; min-width: 130px; padding: 16px 18px; border-radius: 16px;
  cursor: pointer; text-align: left; font-family: inherit;
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
  overflow: hidden;
}
.summary-card:hover { transform: translateY(-3px); box-shadow: var(--mc-shadow-medium); }
.summary-card.active { border-color: var(--mc-primary); box-shadow: 0 0 0 3px var(--mc-primary-bg), var(--mc-shadow-medium); }
.card-dot { position: absolute; top: 16px; right: 16px; width: 9px; height: 9px; border-radius: 50%; }
.dot-primary { background: var(--mc-primary); }
.dot-amber { background: #f59e0b; }
.dot-gray { background: var(--mc-text-tertiary); }
.dot-green { background: #22a06b; }
.card-count { font-size: 28px; font-weight: 800; line-height: 1.1; color: var(--mc-text-primary); }
.card-label { font-size: 13px; color: var(--mc-text-secondary); margin-top: 4px; }
/* Subtle tinted glow per tone, layered under the glass */
.summary-card.accent-primary::before,
.summary-card.accent-amber::before,
.summary-card.accent-gray::before,
.summary-card.accent-green::before {
  content: ''; position: absolute; inset: 0; opacity: 0.5; pointer-events: none;
  background: radial-gradient(120% 90% at 100% 0%, var(--tone-glow, transparent), transparent 60%);
}
.accent-primary { --tone-glow: rgba(217, 119, 87, 0.14); }
.accent-amber { --tone-glow: rgba(245, 158, 11, 0.13); }
.accent-gray { --tone-glow: rgba(120, 120, 120, 0.1); }
.accent-green { --tone-glow: rgba(34, 160, 107, 0.13); }

/* Filter bar */
.filters { display: flex; gap: 10px; align-items: center; padding: 10px 12px; border-radius: 14px; margin-bottom: 16px; }
.platform-select { width: 170px; }
.refresh-btn {
  display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 10px;
  border: 1px solid var(--mc-border); background: var(--mc-bg-muted); color: var(--mc-text-primary);
  font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.15s; font-family: inherit;
}
.refresh-btn:hover { background: var(--mc-primary-bg); border-color: var(--mc-primary-light); color: var(--mc-primary); }

/* Table card */
.table-card { border-radius: 16px; padding: 8px 8px 14px; }
.calendar-table { width: 100%; background: transparent; }
.calendar-table :deep(.el-table__inner-wrapper::before) { display: none; }
.calendar-table :deep(.el-table),
.calendar-table :deep(.el-table__body-wrapper),
.calendar-table :deep(.el-table tr),
.calendar-table :deep(.el-table th.el-table__cell),
.calendar-table :deep(.el-table td.el-table__cell) { background: transparent; }
.calendar-table :deep(.el-table th.el-table__cell) {
  font-weight: 600; color: var(--mc-text-tertiary); font-size: 12.5px; border-bottom: 1px solid var(--mc-border-light);
}
.calendar-table :deep(.el-table td.el-table__cell) { border-bottom: 1px solid var(--mc-border-light); }
.calendar-table :deep(.el-table__row:hover > td.el-table__cell) { background: var(--mc-primary-bg) !important; }

/* Platform pills — gradient */
.platform-pill { display: inline-block; padding: 3px 11px; border-radius: 20px; font-size: 12px; font-weight: 600; color: #fff; white-space: nowrap; }
.pill-gzh { background: linear-gradient(135deg, #2e8b6a, #184a45); }
.pill-xhs { background: linear-gradient(135deg, #ff6a86, #e0345a); }

/* Status chips */
.status-chip { display: inline-block; padding: 3px 10px; border-radius: 20px; font-size: 12px; font-weight: 500; }
.chip-packaged { color: #b26a00; background: rgba(245, 158, 11, 0.14); }
.chip-published { color: #157a52; background: rgba(34, 160, 107, 0.15); }
.chip-draft { color: var(--mc-text-tertiary); background: var(--mc-bg-sunken); }
.chip-failed { color: var(--mc-danger); background: var(--mc-danger-bg); }
.chip-unknown { color: var(--mc-text-tertiary); background: var(--mc-bg-sunken); }

.title-link { display: inline-flex; align-items: center; gap: 5px; color: var(--mc-text-primary); text-decoration: none; font-weight: 500; }
.title-link:hover { color: var(--mc-primary); }
.title-link .ext-icon { opacity: 0; transition: opacity 0.15s; }
.title-link:hover .ext-icon { opacity: 0.7; }
.topic-text { color: var(--mc-text-secondary); font-size: 13px; }
.time-text { color: var(--mc-text-tertiary); font-size: 12.5px; }

.pager { margin-top: 14px; display: flex; justify-content: flex-end; padding: 0 8px; }
</style>
