<template>
  <div class="audit-shell">
    <article class="panel mc-surface-card">
      <header class="panel-head">
        <div>
          <h3 class="panel-title">{{ t('enterprise.audit.title') }}</h3>
          <p class="panel-desc">{{ t('enterprise.audit.desc') }}</p>
        </div>
        <div class="filter-row">
          <button v-for="f in scopeFilters" :key="f.key"
                  class="chip" :class="{ active: scope === f.key }"
                  @click="scope = f.key">{{ f.label }}</button>
        </div>
      </header>

      <ol class="audit-trail">
        <li v-for="e in filteredEvents" :key="e.id" class="audit-event">
          <div class="event-time">
            <div class="time-stamp">{{ e.time }}</div>
            <div class="time-date">{{ e.date }}</div>
          </div>
          <div class="event-marker" :class="`marker-${e.kind}`"></div>
          <div class="event-body">
            <div class="event-head">
              <span class="event-kind" :class="`kind-${e.kind}`">{{ kindLabel(e.kind) }}</span>
              <span class="event-actor">{{ e.actor }}</span>
              <span v-if="e.system" class="event-system">{{ e.system }}</span>
            </div>
            <div class="event-summary">{{ e.summary }}</div>
            <div v-if="e.evidence" class="event-evidence">
              <span class="ev-label">{{ t('enterprise.audit.evidence') }}</span>
              <span>{{ e.evidence }}</span>
            </div>
          </div>
        </li>
      </ol>
    </article>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
type ScopeKey = 'all' | 'contract' | 'account' | 'tool'
const scope = ref<ScopeKey>('all')

const scopeFilters = computed<{ key: ScopeKey; label: string }[]>(() => [
  { key: 'all', label: t('enterprise.audit.scopeAll') },
  { key: 'contract', label: t('enterprise.audit.scopeContract') },
  { key: 'account', label: t('enterprise.audit.scopeAccount') },
  { key: 'tool', label: t('enterprise.audit.scopeTool') },
])

interface Event { id: string; time: string; date: string; kind: 'review' | 'approve' | 'reject' | 'tool' | 'access' | 'modify' | 'agent'; actor: string; system?: string; summary: string; evidence?: string }

const events: Event[] = [
  { id: '1', time: '11:24', date: '今天', kind: 'approve', actor: '法务总监 张律师', system: 'Acme MSA 续约',
    summary: '批准 Acme Corp MSA v3.2 续约（赔偿条款已修正为 12 个月服务费上限）',
    evidence: '关联运行：run #4 · 合同审查 case#msa-acme-2026q2 · 偏离 playbook 项已全部解决' },
  { id: '2', time: '11:18', date: '今天', kind: 'modify', actor: '李法务', system: 'Acme MSA 续约',
    summary: '修改第 8.2 条赔偿条款，采纳 AI 建议（阶梯式上限）',
    evidence: 'AI 建议引用：Zerto MSA 2025 案例 · Playbook v3.1 第 4.2 节' },
  { id: '3', time: '10:42', date: '今天', kind: 'review', actor: 'AI 法务审查员', system: 'Acme MSA 续约',
    summary: '完成条款级审查（3 条偏离，1 高 / 1 中 / 1 低）',
    evidence: '模型：Claude Sonnet 4.6 · 输入：Acme MSA v3.2.pdf 14 页 · 耗时 47s · token 12,830 / 3,210' },
  { id: '4', time: '10:30', date: '今天', kind: 'tool', actor: '销售情报员 Agent', system: 'Acme Corp 账户',
    summary: '调用 web_search 抓取 Acme 季报，提取上云战略关键句',
    evidence: '工具守卫批准 #T-2026-114 · 数据驻留：中国大陆' },
  { id: '5', time: '09:15', date: '今天', kind: 'agent', actor: '法务审查员 Agent', system: 'Vendor A NDA',
    summary: '识别 NDA 第 5.1 条非常规竞业条款（5 年），自动发起改写沟通邮件草稿',
    evidence: 'Playbook 规则：NDA 禁止竞业条款 · 1 次工具调用：draft_email_template' },
  { id: '6', time: '08:30', date: '今天', kind: 'review', actor: 'AI 法务审查员', system: 'Vendor A NDA',
    summary: '完成审查（1 条高风险）',
    evidence: '模型：Claude Sonnet 4.6 · 输入：vendor-a-nda-v1.pdf 4 页 · 耗时 18s' },
  { id: '7', time: '16:00', date: '昨天', kind: 'review', actor: 'AI 合同对比员', system: 'Q2 供应商对比',
    summary: '生成 5 家供应商横向对比报告，识别 3 处显著条款差异',
    evidence: '输入：5 份合同 · 输出 page#vendor-comparison-q2 · 耗时 2m 14s' },
  { id: '8', time: '15:42', date: '昨天', kind: 'access', actor: '周明', system: 'BlueWave 客户库',
    summary: '请求 BlueWave 客户库读取权限',
    evidence: '审批单：access #A-2026-0419 · 主管已批准' },
  { id: '9', time: '14:20', date: '昨天', kind: 'reject', actor: '王法务', system: 'FinChen NDA v0.9',
    summary: '驳回 FinChen NDA v0.9，要求重新修订仲裁地条款',
    evidence: 'AI 已审：3 条偏离 · 驳回理由：仲裁地不能为对方注册地' },
]

const filteredEvents = computed(() => {
  switch (scope.value) {
    case 'contract': return events.filter(e => e.system?.includes('MSA') || e.system?.includes('NDA') || e.system?.includes('对比'))
    case 'account': return events.filter(e => e.system?.includes('Corp') || e.system?.includes('客户库') || e.system?.includes('账户'))
    case 'tool': return events.filter(e => e.kind === 'tool' || e.kind === 'agent')
    default: return events
  }
})

function kindLabel(k: Event['kind']): string { return t(`enterprise.audit.kind.${k}`) }
</script>

<style scoped>
.audit-shell {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
  padding-bottom: 16px;
}
/* Stop the audit panel from being shrunk to fit the body. */
.audit-shell > .panel { flex-shrink: 0; }

.panel { padding: 18px 20px; display: flex; flex-direction: column; gap: 14px; }
.panel-head { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; flex-wrap: wrap; }
.panel-title { font-size: 16px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 2px; }
.panel-desc { font-size: 12px; color: var(--mc-text-secondary); margin: 0; max-width: 480px; line-height: 1.5; }

.filter-row { display: flex; gap: 6px; flex-wrap: wrap; }
.chip {
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font-size: 12px;
  padding: 5px 10px;
  border-radius: 999px;
  cursor: pointer;
}
.chip.active { background: var(--mc-primary-bg); color: var(--mc-primary-hover); border-color: var(--mc-primary); }

/* === audit trail === */
.audit-trail { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.audit-event {
  display: grid;
  grid-template-columns: 72px 16px 1fr;
  gap: 14px;
  padding: 14px 0;
  border-bottom: 1px solid var(--mc-border-light);
  position: relative;
}
.audit-event:last-child { border-bottom: none; }
.audit-event::before {
  content: '';
  position: absolute;
  left: 79px;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--mc-border-light);
}
.audit-event:first-child::before { top: 14px; }
.audit-event:last-child::before { bottom: calc(100% - 24px); }

.event-time { text-align: right; padding-top: 2px; }
.time-stamp { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); font-family: var(--mc-font-mono); }
.time-date { font-size: 11px; color: var(--mc-text-tertiary); }

.event-marker {
  width: 12px; height: 12px;
  border-radius: 50%;
  margin-top: 6px;
  border: 3px solid var(--mc-bg);
  position: relative;
  z-index: 1;
}
.marker-review { background: var(--mc-text-secondary); }
.marker-approve { background: #15803d; }
.marker-reject { background: #b91c1c; }
.marker-tool { background: #f59e0b; }
.marker-access { background: #1e40af; }
.marker-modify { background: var(--mc-primary); }
.marker-agent { background: var(--mc-accent); }

.event-body { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.event-head { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.event-kind {
  font-size: 10px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 4px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.kind-review { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.kind-approve { background: #dcfce7; color: #15803d; }
.kind-reject { background: #fee2e2; color: #b91c1c; }
.kind-tool { background: #fef3c7; color: #b45309; }
.kind-access { background: #dbeafe; color: #1e40af; }
.kind-modify { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.kind-agent { background: var(--mc-accent-soft); color: var(--mc-accent); }

.event-actor { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }
.event-system { font-size: 12px; color: var(--mc-text-tertiary); padding: 2px 8px; background: var(--mc-bg-muted); border-radius: 4px; }

.event-summary { font-size: 13px; color: var(--mc-text-primary); line-height: 1.55; }
.event-evidence { font-size: 11px; color: var(--mc-text-tertiary); display: flex; gap: 6px; align-items: baseline; line-height: 1.5; flex-wrap: wrap; }
.ev-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; color: var(--mc-text-secondary); white-space: nowrap; }
</style>
