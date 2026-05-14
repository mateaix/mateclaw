<template>
  <div class="approvals-shell">
    <article class="panel mc-surface-card">
      <header class="panel-head">
        <div>
          <h3 class="panel-title">{{ t('enterprise.approvals.queueTitle') }}</h3>
          <p class="panel-desc">{{ t('enterprise.approvals.queueDesc') }}</p>
        </div>
        <div class="filter-row">
          <button v-for="f in filters" :key="f.key"
                  class="chip" :class="{ active: filter === f.key }"
                  @click="filter = f.key">
            {{ f.label }}
            <span class="chip-count">{{ f.count }}</span>
          </button>
        </div>
      </header>

      <ul class="approval-list">
        <li v-for="a in filteredItems" :key="a.id" class="approval-item">
          <div class="approval-main">
            <div class="approval-head-row">
              <span class="approval-kind" :class="`kind-${a.kind}`">{{ kindLabel(a.kind) }}</span>
              <span class="approval-risk" :class="`risk-${a.risk}`">{{ riskLabel(a.risk) }}</span>
              <span class="approval-eta">{{ a.sla }}</span>
            </div>
            <div class="approval-title">{{ a.title }}</div>
            <div class="approval-meta">
              <span><strong>{{ t('enterprise.approvals.requester') }}</strong> {{ a.requester }}</span>
              <span class="dot"></span>
              <span><strong>{{ t('enterprise.approvals.target') }}</strong> {{ a.target }}</span>
              <span class="dot"></span>
              <span><strong>{{ t('enterprise.approvals.reason') }}</strong> {{ a.reason }}</span>
            </div>
            <div class="approval-evidence">
              <span class="ev-label">{{ t('enterprise.approvals.evidence') }}</span>
              <span>{{ a.evidence }}</span>
            </div>
          </div>
          <div class="approval-actions">
            <button class="btn-action">{{ t('enterprise.approvals.btnApprove') }}</button>
            <button class="btn-action btn-action--ghost">{{ t('enterprise.approvals.btnReject') }}</button>
            <button class="btn-action btn-action--ghost">{{ t('enterprise.approvals.btnDelegate') }}</button>
          </div>
        </li>
      </ul>
    </article>

    <article class="panel mc-surface-card sla-panel">
      <header class="panel-head">
        <div>
          <h3 class="panel-title">{{ t('enterprise.approvals.slaTitle') }}</h3>
          <p class="panel-desc">{{ t('enterprise.approvals.slaDesc') }}</p>
        </div>
      </header>
      <div class="sla-grid">
        <div class="sla-card">
          <div class="sla-value">2.4h</div>
          <div class="sla-label">{{ t('enterprise.approvals.slaAvg') }}</div>
          <div class="sla-delta good">-18% vs 上周</div>
        </div>
        <div class="sla-card">
          <div class="sla-value">96%</div>
          <div class="sla-label">{{ t('enterprise.approvals.slaWithin') }}</div>
          <div class="sla-delta good">+3pp</div>
        </div>
        <div class="sla-card">
          <div class="sla-value">2</div>
          <div class="sla-label">{{ t('enterprise.approvals.slaOverdue') }}</div>
          <div class="sla-delta bad">+1</div>
        </div>
        <div class="sla-card">
          <div class="sla-value">7</div>
          <div class="sla-label">{{ t('enterprise.approvals.slaAutoApproved') }}</div>
          <div class="sla-delta">{{ t('enterprise.approvals.slaAutoNote') }}</div>
        </div>
      </div>
    </article>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
type FilterKey = 'mine' | 'all' | 'high'
const filter = ref<FilterKey>('mine')

const filters = computed<{ key: FilterKey; label: string; count: number }[]>(() => [
  { key: 'mine', label: t('enterprise.approvals.filterMine'), count: 3 },
  { key: 'all', label: t('enterprise.approvals.filterAll'), count: 5 },
  { key: 'high', label: t('enterprise.approvals.filterHigh'), count: 2 },
])

interface Item {
  id: string
  kind: 'contract' | 'tool' | 'transformation' | 'access'
  risk: 'high' | 'medium' | 'low'
  title: string
  requester: string
  target: string
  reason: string
  evidence: string
  sla: string
  mine: boolean
}

const items: Item[] = [
  { id: '1', kind: 'contract', risk: 'high', title: 'Acme MSA 续约审批',
    requester: '李法务', target: 'Acme Corp (买方)', reason: '赔偿条款偏离 Playbook',
    evidence: 'AI 已审：3 条偏离 · Legal Playbook v3.1', sla: 'SLA 4h 内', mine: true },
  { id: '2', kind: 'tool', risk: 'high', title: 'web_search 调用授权',
    requester: '销售情报员 Agent', target: 'Acme Corp 公司信息抓取', reason: '触发金融行业敏感关键词',
    evidence: '工具守卫规则 R-082 · 历史误报率 < 2%', sla: 'SLA 1h 内', mine: true },
  { id: '3', kind: 'transformation', risk: 'medium', title: '风险点提取 transformation 批量应用',
    requester: '王法务', target: '37 份新增合同', reason: '批量任务超过 10 份阈值',
    evidence: '历史成功率 94% · 单次平均耗时 23s', sla: 'SLA 2h 内', mine: true },
  { id: '4', kind: 'access', risk: 'low', title: 'BlueWave KB 读取权限',
    requester: '新员工 周明', target: 'BlueWave 客户库（含 PII）', reason: '团队入职流程',
    evidence: '入职单 #2026-0421 · 主管 王经理已 OK', sla: 'SLA 1d 内', mine: false },
  { id: '5', kind: 'contract', risk: 'medium', title: 'FinChen NDA 审批',
    requester: '李法务', target: 'FinChen 金融', reason: '仲裁地条款',
    evidence: 'AI 已审：1 条偏离', sla: 'SLA 8h 内', mine: false },
]

const filteredItems = computed(() => {
  if (filter.value === 'mine') return items.filter(i => i.mine)
  if (filter.value === 'high') return items.filter(i => i.risk === 'high')
  return items
})

function kindLabel(k: Item['kind']): string { return t(`enterprise.approvals.kind.${k}`) }
function riskLabel(r: 'high' | 'medium' | 'low'): string {
  return r === 'high' ? t('enterprise.risk.high')
       : r === 'medium' ? t('enterprise.risk.medium')
       : t('enterprise.risk.low')
}
</script>

<style scoped>
.approvals-shell {
  display: flex;
  flex-direction: column;
  gap: 14px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
  padding-bottom: 16px;
}
/* Stop the panels from being shrunk to fit the body — let them overflow
   into the scroll area instead. */
.approvals-shell > .panel { flex-shrink: 0; }

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
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.chip.active { background: var(--mc-primary-bg); color: var(--mc-primary-hover); border-color: var(--mc-primary); }
.chip-count { font-size: 11px; opacity: 0.7; }

/* === approval list === */
.approval-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.approval-item {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 16px;
  padding: 16px;
  background: var(--mc-bg-muted);
  border-radius: 12px;
  border: 1px solid var(--mc-border-light);
}

.approval-main { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.approval-head-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.approval-kind {
  font-size: 10px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 4px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.kind-contract { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.kind-tool { background: #fef3c7; color: #b45309; }
.kind-transformation { background: var(--mc-accent-soft); color: var(--mc-accent); }
.kind-access { background: #dbeafe; color: #1e40af; }

.approval-risk {
  font-size: 10px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 999px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.risk-high { background: #fee2e2; color: #b91c1c; }
.risk-medium { background: #fef3c7; color: #b45309; }
.risk-low { background: #dcfce7; color: #15803d; }

.approval-eta { font-size: 11px; color: var(--mc-text-tertiary); font-family: var(--mc-font-mono); margin-left: auto; }

.approval-title { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); line-height: 1.45; }
.approval-meta { font-size: 12px; color: var(--mc-text-secondary); display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.approval-meta strong { font-weight: 600; color: var(--mc-text-tertiary); margin-right: 4px; }
.dot { width: 3px; height: 3px; background: currentColor; border-radius: 50%; opacity: 0.5; }
.approval-evidence {
  font-size: 12px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-elevated);
  padding: 8px 10px;
  border-radius: 8px;
  margin-top: 4px;
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.ev-label { font-size: 10px; font-weight: 700; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.05em; }

.approval-actions { display: flex; flex-direction: column; gap: 6px; align-self: center; }
.btn-action {
  background: var(--mc-primary);
  color: white;
  border: none;
  font-size: 12px;
  font-weight: 600;
  padding: 7px 16px;
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
}
.btn-action:hover { background: var(--mc-primary-hover); }
.btn-action--ghost { background: transparent; color: var(--mc-text-secondary); border: 1px solid var(--mc-border); }
.btn-action--ghost:hover { background: var(--mc-bg-muted); }

/* === SLA panel === */
.sla-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.sla-card {
  padding: 14px 16px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sla-value { font-size: 24px; font-weight: 700; color: var(--mc-text-primary); line-height: 1; }
.sla-label { font-size: 11px; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.05em; margin-top: 4px; }
.sla-delta { font-size: 11px; color: var(--mc-text-secondary); margin-top: 4px; }
.sla-delta.good { color: #15803d; }
.sla-delta.bad { color: #b91c1c; }

@media (max-width: 1000px) {
  .sla-grid { grid-template-columns: repeat(2, 1fr); }
  .approval-item { grid-template-columns: 1fr; }
  .approval-actions { flex-direction: row; }
}
</style>
