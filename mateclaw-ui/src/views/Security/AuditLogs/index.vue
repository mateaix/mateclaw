<template>
  <div class="settings-section">
    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('security.audit.title') }}</h2>
        <p class="section-desc">{{ t('security.audit.desc') }}</p>
      </div>
    </div>

    <!-- Audit Config -->
    <div class="config-panel">
      <h3 class="config-title">{{ t('security.audit.config.title') }}</h3>
      <div class="config-grid">
        <div class="config-item">
          <div class="config-label-row">
            <label>{{ t('security.audit.config.enabled') }}</label>
            <label class="toggle-switch">
              <input type="checkbox" v-model="auditConfig.auditEnabled" @change="saveAuditConfig" />
              <span class="toggle-slider"></span>
            </label>
          </div>
          <p class="config-hint">{{ t('security.audit.config.enabledHint') }}</p>
        </div>
        <div class="config-item">
          <label>{{ t('security.audit.config.minSeverity') }}</label>
          <select v-model="auditConfig.auditMinSeverity" @change="saveAuditConfig" class="filter-select">
            <option value="INFO">{{ t('security.severity.INFO') }}</option>
            <option value="LOW">{{ t('security.severity.LOW') }}</option>
            <option value="MEDIUM">{{ t('security.severity.MEDIUM') }}</option>
            <option value="HIGH">{{ t('security.severity.HIGH') }}</option>
            <option value="CRITICAL">{{ t('security.severity.CRITICAL') }}</option>
          </select>
          <p class="config-hint">{{ t('security.audit.config.minSeverityHint') }}</p>
        </div>
        <div class="config-item">
          <label>{{ t('security.audit.config.retentionDays') }}</label>
          <input
            type="number"
            v-model.number="auditConfig.auditRetentionDays"
            @change="saveAuditConfig"
            class="filter-input retention-input"
            min="0"
            max="3650"
          />
          <p class="config-hint">{{ t('security.audit.config.retentionDaysHint') }}</p>
        </div>
      </div>
    </div>

    <!-- Stats Cards -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-value">{{ auditStats.total || 0 }}</div>
        <div class="stat-label">{{ t('security.audit.stats.total') }}</div>
      </div>
      <div class="stat-card stat-blocked">
        <div class="stat-value">{{ auditStats.blocked || 0 }}</div>
        <div class="stat-label">{{ t('security.audit.stats.blocked') }}</div>
      </div>
      <div class="stat-card stat-approval">
        <div class="stat-value">{{ auditStats.needsApproval || 0 }}</div>
        <div class="stat-label">{{ t('security.audit.stats.needsApproval') }}</div>
      </div>
      <div class="stat-card stat-allowed">
        <div class="stat-value">{{ auditStats.allowed || 0 }}</div>
        <div class="stat-label">{{ t('security.audit.stats.allowed') }}</div>
      </div>
    </div>

    <!-- Filters -->
    <div class="filter-row">
      <input
        v-model="auditFilters.toolName"
        :placeholder="t('security.audit.filters.toolName')"
        class="filter-input"
      />
      <select v-model="auditFilters.decision" class="filter-select">
        <option value="">{{ t('security.audit.filters.decision') }}</option>
        <option value="ALLOW">{{ t('security.decision.ALLOW') }}</option>
        <option value="NEEDS_APPROVAL">{{ t('security.decision.NEEDS_APPROVAL') }}</option>
        <option value="BLOCK">{{ t('security.decision.BLOCK') }}</option>
      </select>
      <button class="btn-secondary" @click="loadAuditLogs">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="1 4 1 10 7 10"/>
          <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
        </svg>
      </button>
    </div>

    <!-- Audit Table -->
    <div class="rules-table-wrapper">
      <table class="rules-table">
        <thead>
          <tr>
            <th>{{ t('security.audit.columns.time') }}</th>
            <th>{{ t('security.audit.columns.tool') }}</th>
            <th>{{ t('security.audit.columns.decision') }}</th>
            <th>{{ t('security.audit.columns.severity') }}</th>
            <th>{{ t('security.audit.columns.conversationId') }}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="log in auditLogs" :key="log.id">
            <tr>
              <td class="cell-time">{{ formatTime(log.createTime) }}</td>
              <td><code class="tool-name-code">{{ log.toolName }}</code></td>
              <td>
                <span class="decision-badge" :class="'decision-' + log.decision?.toLowerCase()">
                  {{ t('security.decision.' + log.decision) || log.decision }}
                </span>
                <!-- Auto-approve resolution outcome: why this NEEDS_APPROVAL call
                     was (not) auto-approved. NULL on rows predating the column
                     or on decisions that never reach the auto-grant layer. -->
                <div
                  v-if="log.autoApproveOutcome"
                  class="outcome-chip"
                  :class="outcomeClass(log.autoApproveOutcome)"
                  :title="log.autoApproveOutcome"
                >
                  {{ outcomeText(log.autoApproveOutcome) }}
                </div>
              </td>
              <td>
                <span v-if="log.maxSeverity" class="severity-badge" :class="'severity-' + log.maxSeverity?.toLowerCase()">
                  {{ t('security.severity.' + log.maxSeverity) || log.maxSeverity }}
                </span>
              </td>
              <td class="cell-conv">{{ truncateConvId(log.conversationId) }}</td>
              <td>
                <button
                  v-if="log.findingsJson || log.autoApproveOutcome"
                  class="action-btn"
                  @click="toggleExpand(log.id)"
                  :title="t('security.audit.expandFindings')"
                >
                  <svg
                    width="14" height="14" viewBox="0 0 24 24" fill="none"
                    stroke="currentColor" stroke-width="2"
                    :style="{ transform: expandedRows.has(log.id) ? 'rotate(180deg)' : '' }"
                  >
                    <polyline points="6 9 12 15 18 9"/>
                  </svg>
                </button>
              </td>
            </tr>
            <tr v-if="expandedRows.has(log.id)" class="expanded-row">
              <td colspan="6">
                <div class="findings-detail">
                  <div v-for="(finding, idx) in parseFindings(log.findingsJson)" :key="idx" class="finding-item">
                    <span class="severity-badge severity-sm" :class="'severity-' + finding.severity?.toLowerCase()">
                      {{ finding.severity }}
                    </span>
                    <span class="finding-category">{{ finding.category }}</span>
                    <span class="finding-title">{{ finding.title }}</span>
                    <span v-if="finding.remediation" class="finding-remediation">{{ finding.remediation }}</span>
                  </div>
                  <!-- Fixable misses (no grant, or a grant with a too-low ceiling)
                       get a one-click jump to the grant form, prefilled with this
                       row's tool and actual severity. -->
                  <div v-if="canCreateGrantFrom(log)" class="outcome-actions">
                    <button class="btn-secondary btn-sm" @click="goCreateGrant(log)">
                      {{ t('security.audit.outcome.createGrant') }} →
                    </button>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
      <div v-if="!auditLogs.length" class="empty-state">{{ t('security.audit.noLogs') }}</div>
    </div>

    <!-- Pagination -->
    <div v-if="auditTotal > auditPageSize" class="pagination">
      <button
        class="btn-secondary btn-sm"
        :disabled="auditPage <= 1"
        @click="auditPage--; loadAuditLogs()"
      >&laquo;</button>
      <span class="page-info">{{ auditPage }} / {{ Math.ceil(auditTotal / auditPageSize) }}</span>
      <button
        class="btn-secondary btn-sm"
        :disabled="auditPage >= Math.ceil(auditTotal / auditPageSize)"
        @click="auditPage++; loadAuditLogs()"
      >&raquo;</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { securityApi } from '@/api'
import { parseFindings, formatTime, truncateConvId } from '../composables/helpers'
import type { AuditStats } from '@/types'

const { t } = useI18n()
const router = useRouter()

const auditLogs = ref<any[]>([])
const auditStats = reactive<AuditStats>({ total: 0, blocked: 0, needsApproval: 0, allowed: 0 })
const auditPage = ref(1)
const auditPageSize = 20
const auditTotal = ref(0)
const auditFilters = reactive({ toolName: '', decision: '' })
const expandedRows = ref(new Set<number>())

// Audit config
const auditConfig = reactive({
  auditEnabled: true,
  auditMinSeverity: 'INFO',
  auditRetentionDays: 90,
})

async function loadAuditConfig() {
  try {
    const res: any = await securityApi.getGuardConfig()
    const data = res.data || {}
    auditConfig.auditEnabled = data.auditEnabled ?? true
    auditConfig.auditMinSeverity = data.auditMinSeverity || 'INFO'
    auditConfig.auditRetentionDays = data.auditRetentionDays ?? 90
  } catch {
    // ignore
  }
}

async function saveAuditConfig() {
  try {
    await securityApi.updateGuardConfig({
      auditEnabled: auditConfig.auditEnabled,
      auditMinSeverity: auditConfig.auditMinSeverity,
      auditRetentionDays: auditConfig.auditRetentionDays,
    })
  } catch {
    // ignore
  }
}

async function loadAuditLogs() {
  try {
    const params: any = {
      page: auditPage.value,
      size: auditPageSize,
    }
    if (auditFilters.toolName) params.toolName = auditFilters.toolName
    if (auditFilters.decision) params.decision = auditFilters.decision
    const res: any = await securityApi.listAuditLogs(params)
    auditLogs.value = res.data?.records || []
    auditTotal.value = res.data?.total || 0
  } catch {
    // ignore
  }
}

async function loadAuditStats() {
  try {
    const res: any = await securityApi.getAuditStats()
    Object.assign(auditStats, res.data || {})
  } catch {
    // ignore
  }
}

/**
 * Splits an auto_approve_outcome code into an i18n key plus params.
 * SEVERITY_CEILING carries detail as "SEVERITY_CEILING:LOW<HIGH";
 * FORCE_HUMAN carries the floor pattern as "FORCE_HUMAN:pattern".
 */
function parseOutcome(oc: string): { key: string; params: Record<string, string> } {
  if (oc.startsWith('SEVERITY_CEILING:')) {
    const [ceiling, actual] = oc.slice('SEVERITY_CEILING:'.length).split('<')
    return { key: 'SEVERITY_CEILING', params: { ceiling: ceiling || '?', actual: actual || '?' } }
  }
  if (oc.startsWith('FORCE_HUMAN')) {
    return { key: 'FORCE_HUMAN', params: {} }
  }
  return { key: oc, params: {} }
}

function outcomeText(oc: string): string {
  const { key, params } = parseOutcome(oc)
  const i18nKey = `security.audit.outcome.${key}`
  const txt = t(i18nKey, params)
  // vue-i18n echoes the key back when no message exists — fall back to the raw code.
  return txt === i18nKey ? oc : txt
}

function outcomeClass(oc: string): string {
  const { key } = parseOutcome(oc)
  if (key === 'AUTO_GRANT') return 'outcome-granted'
  if (key === 'HARD_BLOCK') return 'outcome-blocked'
  return 'outcome-denied'
}

/** Only misses fixable by creating a grant get the shortcut. */
function canCreateGrantFrom(log: any): boolean {
  if (!log.autoApproveOutcome) return false
  const { key } = parseOutcome(log.autoApproveOutcome)
  return key === 'SEVERITY_CEILING' || key === 'NO_GRANT'
}

function goCreateGrant(log: any) {
  router.push({
    name: 'SecurityAutoApprove',
    query: {
      create: '1',
      tool: log.toolName || '',
      severity: log.maxSeverity || '',
    },
  })
}

function toggleExpand(id: number) {
  if (expandedRows.value.has(id)) {
    expandedRows.value.delete(id)
  } else {
    expandedRows.value.add(id)
  }
  expandedRows.value = new Set(expandedRows.value)
}

onMounted(async () => {
  await Promise.all([loadAuditConfig(), loadAuditLogs(), loadAuditStats()])
})
</script>

<style>
@import '../shared.css';
</style>

<style scoped>
/* Config Panel */
.config-panel {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  padding: 20px;
  margin-bottom: 24px;
}

.config-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0 0 16px;
}

.config-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.config-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.config-item label {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-primary);
}

.config-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.config-hint {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin: 0;
}

.retention-input {
  max-width: 120px;
}

/* Toggle Switch */
.toggle-switch {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 22px;
}

.toggle-switch input { opacity: 0; width: 0; height: 0; }

.toggle-slider {
  position: absolute;
  cursor: pointer;
  inset: 0;
  background: var(--mc-border);
  border-radius: 22px;
  transition: 0.2s;
}

.toggle-slider::before {
  content: '';
  position: absolute;
  width: 16px;
  height: 16px;
  left: 3px;
  bottom: 3px;
  background: white;
  border-radius: 50%;
  transition: 0.2s;
}

.toggle-switch input:checked + .toggle-slider {
  background: var(--mc-accent, #3b82f6);
}

.toggle-switch input:checked + .toggle-slider::before {
  transform: translateX(18px);
}

/* Stats Grid */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  padding: 16px 20px;
  text-align: center;
}

.stat-value { font-size: 28px; font-weight: 700; color: var(--mc-text-primary); }
.stat-label { font-size: 12px; color: var(--mc-text-tertiary); margin-top: 4px; }
.stat-blocked .stat-value { color: #ef4444; }
.stat-approval .stat-value { color: #f59e0b; }
.stat-allowed .stat-value { color: #10b981; }

/* Filters */
.filter-row {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.filter-input {
  padding: 6px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  font-size: 13px;
  flex: 1;
  max-width: 200px;
}

.filter-select {
  padding: 6px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  font-size: 13px;
}

/* Audit table specific */
.cell-time { font-size: 12px; color: var(--mc-text-tertiary); white-space: nowrap; }
.cell-conv { font-size: 12px; font-family: 'SF Mono', monospace; color: var(--mc-text-tertiary); }
.tool-name-code {
  padding: 2px 6px;
  background: var(--mc-bg-sunken);
  border-radius: 4px;
  font-size: 12px;
}

.expanded-row td {
  padding: 0 14px 14px;
  background: var(--mc-bg-sunken);
}

.findings-detail {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.finding-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.finding-category {
  font-family: 'SF Mono', monospace;
  color: var(--mc-text-tertiary);
  font-size: 11px;
}

.finding-title { color: var(--mc-text-primary); }
.finding-remediation { color: var(--mc-text-tertiary); font-style: italic; }

/* Auto-approve outcome chip — sits under the decision badge. Three tones:
   granted (auto-approved), denied (fell back to human), blocked (safety floor). */
.outcome-chip {
  display: inline-block;
  margin-top: 4px;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 1.6;
  white-space: nowrap;
}
.outcome-granted { background: rgba(16, 185, 129, 0.12); color: #10b981; }
.outcome-denied  { background: rgba(245, 158, 11, 0.12); color: #b45309; }
.outcome-blocked { background: rgba(239, 68, 68, 0.12); color: #ef4444; }

.outcome-actions {
  margin-top: 8px;
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 16px;
}

.page-info { font-size: 13px; color: var(--mc-text-tertiary); }
</style>
