<template>
  <div class="page-container">
    <div class="page-shell">
      <div class="page-header">
        <div class="page-lead">
          <div class="page-kicker">{{ t('tokenUsage.kicker') }}</div>
          <h1 class="page-title">{{ t('tokenUsage.title') }}</h1>
          <p class="page-desc">{{ t('tokenUsage.desc') }}</p>
        </div>
        <div class="header-actions">
          <div class="date-range">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="–"
              :start-placeholder="t('tokenUsage.startDate')"
              :end-placeholder="t('tokenUsage.endDate')"
              format="YYYY-MM-DD"
              value-format="YYYY-MM-DD"
              :clearable="false"
              size="default"
            />
          </div>
          <button class="action-btn" :disabled="loading" @click="fetchData">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="23 4 23 10 17 10"/>
              <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
            </svg>
            {{ t('tokenUsage.refresh') }}
          </button>
        </div>
      </div>

      <div class="page-body">
        <div v-if="loading && !data" class="loading-state">
          <div class="spinner"></div>
          <p>{{ t('common.loading') }}</p>
        </div>

        <div v-else-if="error" class="empty-state">
          <span class="empty-icon">⚠️</span>
          <p>{{ error }}</p>
          <button class="action-btn" @click="fetchData">{{ t('tokenUsage.refresh') }}</button>
        </div>

        <template v-else-if="data">
          <div v-if="data.totalMessages > 0" class="summary-cards">
            <div class="summary-card">
              <div class="card-kicker">{{ t('tokenUsage.promptTokens') }}</div>
              <div class="card-value">{{ formatNumber(data.totalPromptTokens) }}</div>
            </div>
            <div class="summary-card">
              <div class="card-kicker">{{ t('tokenUsage.completionTokens') }}</div>
              <div class="card-value">{{ formatNumber(data.totalCompletionTokens) }}</div>
            </div>
            <div class="summary-card">
              <div class="card-kicker">{{ t('tokenUsage.assistantMessages') }}</div>
              <div class="card-value">{{ formatNumber(data.totalMessages) }}</div>
            </div>
          </div>

          <div v-if="data.totalMessages === 0" class="empty-state">
            <span class="empty-icon">📊</span>
            <p>{{ t('tokenUsage.noData') }}</p>
          </div>

          <div v-if="data.byModel && data.byModel.length > 0" class="table-section">
            <h2 class="section-title">{{ t('tokenUsage.byModel') }}</h2>
            <div class="table-wrap">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>{{ t('tokenUsage.provider') }}</th>
                    <th>{{ t('tokenUsage.model') }}</th>
                    <th class="num-col">{{ t('tokenUsage.promptTokens') }}</th>
                    <th class="num-col">{{ t('tokenUsage.completionTokens') }}</th>
                    <th class="num-col">{{ t('tokenUsage.messageCount') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(item, idx) in data.byModel" :key="idx">
                    <td><span class="mono-text mono-text--truncate" :title="item.runtimeProvider || '-'">{{ item.runtimeProvider || '-' }}</span></td>
                    <td><span class="mono-text mono-text--truncate mono-text--wide" :title="item.runtimeModel || '-'">{{ item.runtimeModel || '-' }}</span></td>
                    <td class="num-col">{{ formatNumber(item.promptTokens) }}</td>
                    <td class="num-col">{{ formatNumber(item.completionTokens) }}</td>
                    <td class="num-col">{{ formatNumber(item.messageCount) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div v-if="data.byDate && data.byDate.length > 0" class="table-section">
            <h2 class="section-title">{{ t('tokenUsage.byDate') }}</h2>
            <div class="table-wrap">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>{{ t('tokenUsage.date') }}</th>
                    <th class="num-col">{{ t('tokenUsage.promptTokens') }}</th>
                    <th class="num-col">{{ t('tokenUsage.completionTokens') }}</th>
                    <th class="num-col">{{ t('tokenUsage.messageCount') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in data.byDate" :key="item.date">
                    <td>{{ item.date }}</td>
                    <td class="num-col">{{ formatNumber(item.promptTokens) }}</td>
                    <td class="num-col">{{ formatNumber(item.completionTokens) }}</td>
                    <td class="num-col">{{ formatNumber(item.messageCount) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { tokenUsageApi } from '@/api/index'
import type { TokenUsageSummary } from '@/types/tokenUsage'

const { t } = useI18n()
const loading = ref(false)
const error = ref<string | null>(null)
const data = ref<TokenUsageSummary | null>(null)

const today = new Date()
const thirtyDaysAgo = new Date(today)
thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30)

function toDateStr(d: Date): string {
  return d.toISOString().slice(0, 10)
}

const dateRange = ref<[string, string]>([toDateStr(thirtyDaysAgo), toDateStr(today)])

function formatNumber(n: number): string {
  if (n == null) return '0'
  return n.toLocaleString()
}

async function fetchData() {
  loading.value = true
  error.value = null
  try {
    const res: any = await tokenUsageApi.getSummary({
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
    })
    data.value = res.data || null
  } catch (e: any) {
    const msg = t('tokenUsage.loadFailed')
    mcToast.error(msg)
    error.value = msg
    data.value = null
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)
</script>

<style scoped>
.page-container {
  height: 100%;
  overflow-y: auto;
  padding: 0;
  background: transparent;
}

.page-shell {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 0;
  background: transparent;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  padding: 24px 24px 0;
}

.page-lead {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.page-kicker {
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

.page-title {
  font-size: clamp(28px, 4vw, 40px);
  line-height: 0.95;
  font-weight: 800;
  color: var(--mc-text-primary);
  margin: 0;
}

.page-desc {
  max-width: 620px;
  font-size: 15px;
  line-height: 1.55;
  color: var(--mc-text-secondary);
  margin: 0;
}

.page-body {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 0 24px 24px;
}

.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  color: var(--mc-text-primary);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}

.action-btn:hover {
  background: var(--mc-bg-sunken);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.summary-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.summary-card {
  background: linear-gradient(180deg, color-mix(in srgb, var(--mc-bg-elevated) 92%, white 8%) 0%, var(--mc-bg-elevated) 100%);
  border: 1px solid var(--mc-border);
  border-radius: 16px;
  padding: 18px 20px;
  box-shadow: 0 16px 40px rgba(152, 93, 63, 0.06);
}

.card-kicker {
  margin-bottom: 10px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--mc-text-tertiary);
}

.card-value {
  font-size: clamp(28px, 4vw, 48px);
  line-height: 1;
  font-weight: 800;
  color: var(--mc-text-primary);
  font-variant-numeric: tabular-nums;
}

.table-section {
  margin-bottom: 20px;
}

.section-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0 0 10px;
}

.table-wrap {
  width: 100%;
  background: linear-gradient(180deg, color-mix(in srgb, var(--mc-bg-elevated) 92%, white 8%) 0%, var(--mc-bg-elevated) 100%);
  border: 1px solid var(--mc-border);
  border-radius: 24px;
  overflow-x: auto;
  overflow-y: hidden;
  box-shadow: 0 20px 48px rgba(128, 84, 60, 0.08);
}

.data-table {
  width: 100%;
  min-width: 0;
  border-collapse: collapse;
  table-layout: fixed;
}

.data-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 12px 16px;
  text-align: left;
  font-size: 11px;
  font-weight: 700;
  color: var(--mc-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  background: color-mix(in srgb, var(--mc-bg-sunken) 86%, white 14%);
  border-bottom: 1px solid var(--mc-border);
}

.data-table td {
  padding: 14px 16px;
  font-size: 14px;
  color: var(--mc-text-primary);
  border-bottom: 1px solid var(--mc-border-light);
}

.data-table tr:last-child td {
  border-bottom: none;
}

.data-table tr:hover td {
  background: var(--mc-bg-sunken);
}

.num-col {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.mono-text {
  font-family: monospace;
  font-size: 13px;
}

.mono-text--truncate {
  display: inline-block;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.mono-text--wide {
  max-width: 220px;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 0;
  gap: 12px;
  color: var(--mc-text-tertiary);
}

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--mc-border);
  border-top-color: var(--mc-primary, #D97757);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 60px 0;
  color: var(--mc-text-tertiary);
}

.empty-icon {
  font-size: 32px;
}

.empty-state p {
  font-size: 14px;
  margin: 0;
}

.date-range :deep(.el-date-editor) {
  --el-date-editor-width: 260px;
}

@media (max-width: 768px) {
  .summary-cards {
    grid-template-columns: 1fr;
  }
  .page-header {
    flex-direction: column;
    padding: 16px 16px 0;
  }
  .page-body {
    padding: 0 16px 16px;
  }
  .header-actions {
    width: 100%;
  }
  .date-range {
    width: 100%;
  }
  .date-range :deep(.el-date-editor) {
    width: 100%;
  }
  .action-btn {
    width: 100%;
    justify-content: center;
  }
}
</style>
