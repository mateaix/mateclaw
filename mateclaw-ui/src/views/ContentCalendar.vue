<template>
  <div class="content-calendar">
    <div class="page-header">
      <h2>{{ t('contentCalendar.title') }}</h2>
      <p class="subtitle">{{ t('contentCalendar.subtitle') }}</p>
    </div>

    <!-- Status summary cards -->
    <div class="summary-cards">
      <div
        v-for="card in summaryCards"
        :key="card.key"
        class="summary-card"
        :class="{ active: statusFilter === card.key }"
        @click="toggleStatus(card.key)"
      >
        <div class="card-count">{{ card.count }}</div>
        <div class="card-label">{{ card.label }}</div>
      </div>
    </div>

    <!-- Filters -->
    <div class="filters">
      <el-select v-model="platformFilter" :placeholder="t('contentCalendar.allPlatforms')" clearable @change="reload">
        <el-option label="公众号" value="gzh" />
        <el-option label="小红书" value="xhs" />
      </el-select>
      <el-button :icon="RefreshIcon" @click="reload">{{ t('contentCalendar.refresh') }}</el-button>
    </div>

    <!-- Table -->
    <el-table :data="rows" v-loading="loading" stripe class="calendar-table">
      <el-table-column :label="t('contentCalendar.platform')" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.platform === 'gzh' ? 'success' : 'danger'">
            {{ row.platform === 'gzh' ? '公众号' : '小红书' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('contentCalendar.colTitle')" min-width="220">
        <template #default="{ row }">
          <a v-if="row.previewUrl" :href="row.previewUrl" target="_blank" rel="noopener" class="title-link">
            {{ row.title || '(无标题)' }}
          </a>
          <span v-else>{{ row.title || '(无标题)' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="topic" :label="t('contentCalendar.topic')" min-width="140" show-overflow-tooltip />
      <el-table-column :label="t('contentCalendar.status')" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('contentCalendar.createTime')" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column :label="t('contentCalendar.publishTime')" width="170">
        <template #default="{ row }">{{ formatTime(row.publishTime) || '—' }}</template>
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
</template>

<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import { useI18n } from 'vue-i18n'
import { contentItemApi } from '@/api'

const { t } = useI18n()

// Minimal inline refresh icon (avoids an extra import).
const RefreshIcon = () =>
  h('svg', { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2 }, [
    h('polyline', { points: '23 4 23 10 17 10' }),
    h('path', { d: 'M20.49 15a9 9 0 1 1-2.12-9.36L23 10' }),
  ])

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
  { key: '', label: t('contentCalendar.total'), count: summary.value.total ?? 0 },
  { key: 'packaged', label: t('contentCalendar.packaged'), count: summary.value.packaged ?? 0 },
  { key: 'draft', label: t('contentCalendar.draft'), count: summary.value.draft ?? 0 },
  { key: 'published', label: t('contentCalendar.published'), count: summary.value.published ?? 0 },
])

function statusLabel(s?: string) {
  return t(`contentCalendar.st_${s || 'unknown'}`)
}
function statusTagType(s?: string) {
  return s === 'published' ? 'success' : s === 'draft' ? 'info' : s === 'failed' ? 'danger' : 'warning'
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
    // Degrade gracefully (e.g. backend not yet restarted with the new endpoint).
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
.content-calendar { padding: 20px; max-width: 1200px; margin: 0 auto; }
.page-header h2 { margin: 0 0 4px; font-size: 20px; }
.subtitle { margin: 0 0 16px; color: var(--el-text-color-secondary); font-size: 13px; }
.summary-cards { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.summary-card {
  flex: 1; min-width: 120px; padding: 14px 16px; border-radius: 10px;
  background: var(--el-fill-color-light); cursor: pointer; transition: all .15s;
  border: 1px solid transparent;
}
.summary-card:hover { background: var(--el-fill-color); }
.summary-card.active { border-color: var(--el-color-primary); }
.card-count { font-size: 24px; font-weight: 700; }
.card-label { font-size: 13px; color: var(--el-text-color-secondary); margin-top: 2px; }
.filters { display: flex; gap: 10px; margin-bottom: 12px; }
.calendar-table { width: 100%; }
.title-link { color: var(--el-color-primary); text-decoration: none; }
.title-link:hover { text-decoration: underline; }
.pager { margin-top: 14px; display: flex; justify-content: flex-end; }
</style>
