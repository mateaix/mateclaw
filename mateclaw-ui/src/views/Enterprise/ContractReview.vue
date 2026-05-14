<template>
  <div class="review-shell">
    <aside class="review-list mc-surface-card">
      <header class="list-head">
        <h3 class="list-title">{{ t('enterprise.contract.listTitle') }}</h3>
        <div class="list-filters">
          <button v-for="f in listFilters" :key="f.key"
                  class="chip" :class="{ active: listFilter === f.key }"
                  @click="listFilter = f.key">
            {{ f.label }}
            <span v-if="f.count != null" class="chip-count">{{ f.count }}</span>
          </button>
        </div>
      </header>

      <ul class="case-list">
        <li v-for="c in filteredCases" :key="c.id"
            class="case-item" :class="{ active: c.id === selectedId }"
            @click="selectedId = c.id">
          <span class="risk-pill" :class="`risk-${c.risk}`">{{ riskLabel(c.risk) }}</span>
          <div class="case-main">
            <div class="case-title">{{ c.title }}</div>
            <div class="case-meta">
              <span>{{ c.counterparty }}</span>
              <span class="dot"></span>
              <span>{{ c.version }}</span>
            </div>
          </div>
        </li>
      </ul>
    </aside>

    <section class="review-center mc-surface-card">
      <header class="center-head">
        <div class="center-headline">
          <h2 class="center-title">{{ selectedCase.title }}</h2>
          <div class="center-sub">
            <span>{{ selectedCase.counterparty }}</span>
            <span class="dot"></span>
            <span>{{ selectedCase.value }}</span>
            <span class="dot"></span>
            <span>{{ selectedCase.line }}</span>
          </div>
        </div>
        <div class="center-status-row">
          <span class="status-chip" :class="`status-${selectedCase.status}`">
            {{ statusLabel(selectedCase.status) }}
          </span>
          <span class="status-counter">{{ selectedCase.clauses.length }} {{ t('enterprise.contract.clausesFound') }}</span>
        </div>
      </header>

      <div class="clause-list">
        <article v-for="cl in selectedCase.clauses" :key="cl.id"
                 class="clause-card" :class="`risk-${cl.risk}`"
                 :data-active="cl.id === activeClauseId"
                 @click="activeClauseId = cl.id">
          <div class="clause-head">
            <span class="risk-pill" :class="`risk-${cl.risk}`">{{ riskLabel(cl.risk) }}</span>
            <span class="clause-type">{{ cl.type }}</span>
            <span class="clause-loc">{{ cl.location }}</span>
          </div>
          <blockquote class="clause-quote">「{{ cl.quote }}」</blockquote>
          <div class="clause-deviation">
            <span class="dev-label">{{ t('enterprise.contract.deviation') }}</span>
            <span>{{ cl.deviation }}</span>
          </div>
          <div class="clause-suggest">
            <span class="suggest-label">{{ t('enterprise.contract.suggestion') }}</span>
            <div class="suggest-text">{{ cl.suggestion }}</div>
          </div>
        </article>
      </div>

      <footer class="approval-chain">
        <div class="chain-title">{{ t('enterprise.contract.approvalChain') }}</div>
        <ol class="chain-list">
          <li v-for="(step, i) in selectedCase.chain" :key="i"
              class="chain-step" :class="{ done: step.done, active: !step.done && firstActiveChain === i }">
            <div class="chain-bullet">{{ step.done ? '✓' : i + 1 }}</div>
            <div class="chain-body">
              <div class="chain-who">{{ step.who }}</div>
              <div class="chain-note">{{ step.note }}</div>
              <div v-if="step.at" class="chain-at">{{ step.at }}</div>
            </div>
          </li>
        </ol>
        <div class="chain-actions">
          <button class="btn-secondary chain-btn">{{ t('enterprise.contract.btnReject') }}</button>
          <button class="btn-secondary chain-btn">{{ t('enterprise.contract.btnRequestRevision') }}</button>
          <button class="btn-primary chain-btn">{{ t('enterprise.contract.btnApprove') }}</button>
        </div>
      </footer>
    </section>

    <aside class="review-evidence mc-surface-card">
      <header class="evidence-head">
        <h3 class="list-title">{{ t('enterprise.contract.evidenceTitle') }}</h3>
        <p class="evidence-sub">{{ activeClause.type }} · {{ activeClause.location }}</p>
      </header>

      <div class="evidence-stack">
        <div class="evidence-block">
          <div class="block-label">{{ t('enterprise.contract.sourceContract') }}</div>
          <div class="block-quote">「{{ activeClause.quote }}」</div>
          <div class="block-loc">{{ selectedCase.title }} · {{ activeClause.location }}</div>
        </div>

        <div class="evidence-block">
          <div class="block-label">{{ t('enterprise.contract.playbookRule') }}</div>
          <div class="block-quote">「{{ activeClause.playbookQuote }}」</div>
          <div class="block-loc">{{ activeClause.playbookRef }}</div>
        </div>

        <div v-if="activeClause.precedents.length" class="evidence-block">
          <div class="block-label">{{ t('enterprise.contract.precedents') }}</div>
          <ul class="precedent-list">
            <li v-for="p in activeClause.precedents" :key="p.contract">
              <div class="precedent-headline">{{ p.contract }}</div>
              <div class="precedent-outcome">{{ p.outcome }}</div>
            </li>
          </ul>
        </div>
      </div>

      <footer class="evidence-foot">
        <div class="foot-meta">
          <span class="foot-label">{{ t('enterprise.contract.confidence') }}</span>
          <span class="foot-value">{{ activeClause.confidence }}</span>
        </div>
        <div class="foot-meta">
          <span class="foot-label">{{ t('enterprise.contract.model') }}</span>
          <span class="foot-value">{{ activeClause.model }}</span>
        </div>
      </footer>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const props = defineProps<{ focus: string | null }>()

type ListFilterKey = 'all' | 'high' | 'pending'
const listFilter = ref<ListFilterKey>('all')

const listFilters = computed<{ key: ListFilterKey; label: string; count: number }[]>(() => [
  { key: 'all', label: t('enterprise.contract.filterAll'), count: 23 },
  { key: 'high', label: t('enterprise.contract.filterHigh'), count: 7 },
  { key: 'pending', label: t('enterprise.contract.filterPending'), count: 12 },
])

interface Clause {
  id: string
  type: string
  risk: 'high' | 'medium' | 'low'
  location: string
  quote: string
  deviation: string
  suggestion: string
  playbookQuote: string
  playbookRef: string
  precedents: { contract: string; outcome: string }[]
  confidence: string
  model: string
}

interface ChainStep { who: string; note: string; at: string | null; done: boolean }

interface Case {
  id: string
  title: string
  counterparty: string
  version: string
  value: string
  line: string
  risk: 'high' | 'medium' | 'low'
  status: 'ai_reviewed' | 'pending_legal' | 'approved'
  clauses: Clause[]
  chain: ChainStep[]
}

const cases: Case[] = [
  {
    id: 'msa-acme-2026q2',
    title: 'Acme Corp - MSA 续约',
    counterparty: 'Acme Corp (买方)',
    version: 'v3.2 · 2026-Q2',
    value: '合同价值 ¥12M / 3 年',
    line: '业务线：企业版 SaaS',
    risk: 'high',
    status: 'ai_reviewed',
    clauses: [
      {
        id: 'c1',
        type: '赔偿条款',
        risk: 'high',
        location: '第 8.2 条 · 第 14 页',
        quote: '乙方应赔偿甲方因履行本协议产生的全部直接和间接损失，包括但不限于利润损失、商誉损失和惩罚性赔偿，无上限',
        deviation: '我司 Legal Playbook 要求赔偿上限不超过 12 个月服务费，且明确排除惩罚性赔偿与间接损失',
        suggestion: '改为：乙方对甲方的赔偿责任总额不超过本协议项下最近 12 个月支付的服务费总额；任何一方均不对另一方的间接损失、利润损失或惩罚性赔偿承担责任。',
        playbookQuote: '所有商务合同的赔偿条款必须包含责任上限（不超过 12 个月服务费）和间接损失排除条款',
        playbookRef: 'Legal Playbook v3.1 · 第 4.2 节',
        precedents: [
          { contract: 'Zerto MSA 2025', outcome: '采用阶梯式上限（前 12 月 = 服务费 100%，后续 = 50%）' },
          { contract: 'BlueWave SLA 2024', outcome: '客户接受 12 个月服务费上限 + 惩罚性赔偿排除' },
        ],
        confidence: '高（4 项证据）',
        model: 'Claude Sonnet 4.6',
      },
      {
        id: 'c2',
        type: '数据保护',
        risk: 'medium',
        location: '第 11.4 条 · 第 19 页',
        quote: '任何数据泄露，乙方应在 4 小时内通过书面方式通知甲方',
        deviation: '我司 Playbook 要求 72 小时通知期，4 小时不切实际且容易触发误报',
        suggestion: '建议改为 72 小时（与 GDPR 一致），并将"书面"扩展为"任何可追溯的方式（包括邮件、Slack 受控渠道）"',
        playbookQuote: '数据泄露通知期 = 72 小时，与 GDPR Article 33 对齐',
        playbookRef: 'Legal Playbook v3.1 · 第 5.1 节',
        precedents: [
          { contract: 'EuroSoft DPA 2025', outcome: '客户接受 72 小时通知期' },
        ],
        confidence: '中（2 项证据）',
        model: 'Claude Sonnet 4.6',
      },
      {
        id: 'c3',
        type: '自动续约',
        risk: 'low',
        location: '第 2.3 条 · 第 3 页',
        quote: '本协议自动续约一年，除非任何一方在到期前 30 天书面通知',
        deviation: '我司 Playbook 推荐 60 天通知期以匹配预算审批流程',
        suggestion: '修改通知期为 60 天',
        playbookQuote: '自动续约的退出通知期 ≥ 60 天',
        playbookRef: 'Legal Playbook v3.1 · 第 3.4 节',
        precedents: [],
        confidence: '高（明确 playbook 偏离）',
        model: 'Claude Sonnet 4.6',
      },
    ],
    chain: [
      { who: 'AI 法务审查员', note: '已完成条款级审查（3 条偏离）', at: '今天 09:42', done: true },
      { who: '李法务', note: '复核中', at: '今天 10:15', done: false },
      { who: '法务总监 张律师', note: '待审批', at: null, done: false },
      { who: 'BD 总监 王经理', note: '待签字', at: null, done: false },
    ],
  },
  {
    id: 'nda-vendora',
    title: 'Vendor A NDA',
    counterparty: 'Vendor A Inc. (供应商)',
    version: 'v1.0 · 2026-04',
    value: '保密期：永久',
    line: '业务线：采购',
    risk: 'high',
    status: 'pending_legal',
    clauses: [
      {
        id: 'c1',
        type: '非常规竞业',
        risk: 'high',
        location: '第 5.1 条 · 第 4 页',
        quote: '签署方未来 5 年内不得与对方业务领域有重叠的任何公司发生雇佣或顾问关系',
        deviation: '我司 Playbook 不允许 NDA 包含竞业条款，更不允许 5 年这种长周期',
        suggestion: '建议彻底删除第 5.1 条，竞业义务应另行签订独立竞业协议',
        playbookQuote: 'NDA 不得包含竞业条款；竞业义务需独立协议',
        playbookRef: 'Legal Playbook v3.1 · 第 6.2 节',
        precedents: [],
        confidence: '高',
        model: 'Claude Sonnet 4.6',
      },
    ],
    chain: [
      { who: 'AI 法务审查员', note: '已完成审查（1 条高风险）', at: '今天 08:30', done: true },
      { who: '王法务', note: '已发起改写沟通', at: '今天 09:15', done: false },
      { who: '法务总监 张律师', note: '待审批', at: null, done: false },
    ],
  },
  {
    id: 'q2-vendor-cmp',
    title: 'Q2 供应商条款横向对比',
    counterparty: '5 家供应商汇总',
    version: '对比报告 v1',
    value: '总合同价值 ¥45M',
    line: '业务线：采购',
    risk: 'medium',
    status: 'ai_reviewed',
    clauses: [],
    chain: [
      { who: 'AI 合同对比员', note: '已生成横向对比报告', at: '昨天 16:00', done: true },
      { who: '采购总监', note: '复核中', at: null, done: false },
    ],
  },
]

const filteredCases = computed(() => {
  if (listFilter.value === 'high') return cases.filter(c => c.risk === 'high')
  if (listFilter.value === 'pending') return cases.filter(c => c.status !== 'approved')
  return cases
})

const selectedId = ref<string>(cases[0].id)
watch(() => props.focus, (id) => {
  if (id && cases.find(c => c.id === id)) selectedId.value = id
}, { immediate: true })

const selectedCase = computed(() => cases.find(c => c.id === selectedId.value) ?? cases[0])
const activeClauseId = ref<string>(selectedCase.value.clauses[0]?.id ?? '')
watch(selectedId, () => { activeClauseId.value = selectedCase.value.clauses[0]?.id ?? '' })

const activeClause = computed<Clause>(() => {
  const c = selectedCase.value.clauses.find(x => x.id === activeClauseId.value)
  return c ?? selectedCase.value.clauses[0] ?? emptyClause
})

const firstActiveChain = computed(() => selectedCase.value.chain.findIndex(s => !s.done))

const emptyClause: Clause = {
  id: 'empty', type: '—', risk: 'low', location: '—', quote: '—', deviation: '—', suggestion: '—',
  playbookQuote: '—', playbookRef: '—', precedents: [], confidence: '—', model: '—',
}

function riskLabel(r: 'high' | 'medium' | 'low'): string {
  return r === 'high' ? t('enterprise.risk.high')
       : r === 'medium' ? t('enterprise.risk.medium')
       : t('enterprise.risk.low')
}
function statusLabel(s: Case['status']): string {
  return s === 'ai_reviewed' ? t('enterprise.status.aiReviewed')
       : s === 'pending_legal' ? t('enterprise.status.pendingLegal')
       : t('enterprise.status.approved')
}
</script>

<style scoped>
.review-shell {
  display: grid;
  grid-template-columns: 280px 1fr 340px;
  /* Force the single row to the container height so each pane has a definite
     height ceiling; minmax(0, 1fr) lets the pane shrink below its content
     intrinsic size so its inner scrollable region kicks in. */
  grid-template-rows: minmax(0, 1fr);
  gap: 14px;
  flex: 1;
  min-height: 0;
}

/* === panes === */
.review-list, .review-center, .review-evidence {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  overflow: hidden;
}
.review-list { padding: 16px 0; }

/* === list pane === */
.list-head { padding: 0 18px; display: flex; flex-direction: column; gap: 8px; }
.list-title { font-size: 14px; font-weight: 700; color: var(--mc-text-primary); margin: 0; text-transform: uppercase; letter-spacing: 0.05em; }
.list-filters { display: flex; gap: 6px; flex-wrap: wrap; }
.chip {
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font-size: 11px;
  padding: 4px 9px;
  border-radius: 999px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.chip.active { background: var(--mc-primary-bg); color: var(--mc-primary-hover); border-color: var(--mc-primary); }
.chip-count { opacity: 0.65; }

.case-list { list-style: none; margin: 0; padding: 0; flex: 1; overflow-y: auto; }
.case-item {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 10px;
  padding: 12px 18px;
  border-left: 3px solid transparent;
  cursor: pointer;
  transition: all 0.12s;
}
.case-item:hover { background: var(--mc-bg-muted); }
.case-item.active { background: var(--mc-primary-bg); border-left-color: var(--mc-primary); }
.case-main { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.case-title { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.case-meta { font-size: 11px; color: var(--mc-text-tertiary); display: flex; gap: 6px; align-items: center; }

/* === risk pill (shared) === */
.risk-pill {
  font-size: 10px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 999px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  text-align: center;
  min-width: 38px;
  align-self: flex-start;
  height: fit-content;
}
.risk-high { background: #fee2e2; color: #b91c1c; }
.risk-medium { background: #fef3c7; color: #b45309; }
.risk-low { background: #dcfce7; color: #15803d; }
.dot { width: 3px; height: 3px; background: currentColor; border-radius: 50%; opacity: 0.5; }

/* === center pane === */
.review-center { overflow: hidden; }
.center-head {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--mc-border-light);
}
.center-headline { display: flex; flex-direction: column; gap: 4px; }
.center-title { font-size: 18px; font-weight: 700; color: var(--mc-text-primary); margin: 0; }
.center-sub { display: flex; gap: 8px; align-items: center; font-size: 12px; color: var(--mc-text-secondary); }
.center-status-row { display: flex; gap: 10px; align-items: center; }
.status-chip { font-size: 11px; font-weight: 600; padding: 4px 10px; border-radius: 6px; }
.status-ai_reviewed { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.status-pending_legal { background: #fef3c7; color: #b45309; }
.status-approved { background: #dcfce7; color: #15803d; }
.status-counter { font-size: 12px; color: var(--mc-text-tertiary); }

.clause-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
  overflow-y: auto;
  flex: 1;
  padding-right: 6px;
  /* Stop the last card from butting against the approval-chain footer. */
  padding-bottom: 8px;
}
.clause-card {
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
  background: var(--mc-bg-elevated);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.18s, transform 0.15s;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(58, 32, 19, 0.04);
}
.clause-card:hover { box-shadow: 0 4px 14px rgba(58, 32, 19, 0.08); }
.clause-card.risk-high { border-left: 4px solid #ef4444; }
.clause-card.risk-medium { border-left: 4px solid #f59e0b; }
.clause-card.risk-low { border-left: 4px solid #84cc16; }
.clause-card[data-active="true"] {
  box-shadow: 0 6px 22px rgba(217, 109, 70, 0.18);
  border-color: var(--mc-primary);
}

/* === head row — gets its own contrast band so each card has an obvious "start" === */
.clause-head {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  padding: 12px 16px;
  background: var(--mc-bg-muted);
  border-bottom: 1px solid var(--mc-border-light);
}
.clause-type { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); }
.clause-loc {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  font-family: var(--mc-font-mono);
  margin-left: auto;
  background: var(--mc-bg-elevated);
  padding: 3px 8px;
  border-radius: 4px;
  border: 1px solid var(--mc-border-light);
}

/* === body — quote / deviation / suggestion stack with consistent padding === */
.clause-card > .clause-quote,
.clause-card > .clause-deviation,
.clause-card > .clause-suggest { margin: 0 16px; }
.clause-card > .clause-quote { margin-top: 14px; }
.clause-card > .clause-deviation { margin-top: 10px; }
.clause-card > .clause-suggest { margin: 12px 16px 16px; }

.clause-quote {
  font-size: 13px;
  color: var(--mc-text-primary);
  line-height: 1.6;
  padding: 10px 14px;
  background: var(--mc-bg);
  border-radius: 8px;
  font-style: italic;
  border: 1px dashed var(--mc-border-light);
}
.clause-deviation {
  font-size: 12px;
  color: var(--mc-text-secondary);
  display: flex;
  gap: 10px;
  align-items: baseline;
  line-height: 1.55;
}
.dev-label, .suggest-label {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--mc-text-tertiary);
  white-space: nowrap;
  padding: 2px 7px;
  border-radius: 4px;
}
.dev-label { color: #b45309; background: #fef3c7; }
.suggest-label { color: var(--mc-primary-hover); background: var(--mc-primary-bg); }

.clause-suggest {
  background: linear-gradient(180deg, var(--mc-primary-bg), rgba(246, 226, 215, 0.4));
  border: 1px solid rgba(217, 109, 70, 0.18);
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.suggest-text { font-size: 13px; color: var(--mc-text-primary); line-height: 1.6; }

/* === approval chain === */
.approval-chain {
  border-top: 1px solid var(--mc-border-light);
  padding-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.chain-title { font-size: 11px; font-weight: 700; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.06em; }

/* Vertical stepper layout — bullet on top, body underneath, connector
   between bullets at bullet-vertical-center. Mirrors the overview pipeline
   so the two surfaces share a single stepper grammar. */
.chain-list { list-style: none; margin: 0; padding: 0; display: flex; align-items: stretch; }
.chain-step {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 6px;
  padding: 0 8px;
  position: relative;
  min-width: 0;
}
.chain-step::after {
  content: '';
  position: absolute;
  top: 13px;
  left: 50%;
  right: -50%;
  height: 2px;
  background: var(--mc-border-light);
  z-index: 0;
}
.chain-step.done::after { background: var(--mc-primary); }
.chain-step:last-child::after { display: none; }

.chain-bullet {
  width: 28px; height: 28px;
  border-radius: 50%;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-tertiary);
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid var(--mc-border-light);
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}
.chain-step.done .chain-bullet { background: var(--mc-primary); color: white; border-color: var(--mc-primary); }
.chain-step.active .chain-bullet { background: var(--mc-primary-bg); color: var(--mc-primary-hover); border-color: var(--mc-primary); }

.chain-body { min-width: 0; display: flex; flex-direction: column; align-items: center; gap: 2px; max-width: 140px; }
.chain-who { font-size: 12px; font-weight: 600; color: var(--mc-text-primary); line-height: 1.3; }
.chain-step.active .chain-who { color: var(--mc-primary-hover); }
.chain-note { font-size: 11px; color: var(--mc-text-secondary); line-height: 1.35; }
.chain-at { font-size: 10px; color: var(--mc-text-tertiary); font-family: var(--mc-font-mono); margin-top: 1px; }

.chain-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 4px; }
.chain-btn { white-space: nowrap; }
.btn-primary, .btn-secondary {
  border-radius: 8px;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s;
}
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-secondary { background: var(--mc-bg-elevated); color: var(--mc-text-primary); border-color: var(--mc-border); }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

/* === evidence pane === */
.evidence-head { display: flex; flex-direction: column; gap: 4px; }
.evidence-sub { font-size: 11px; color: var(--mc-text-tertiary); margin: 0; }
.evidence-stack { display: flex; flex-direction: column; gap: 14px; overflow-y: auto; flex: 1; }
.evidence-block {
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  padding: 12px 14px;
  background: var(--mc-bg-muted);
}
.block-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; color: var(--mc-text-tertiary); margin-bottom: 8px; }
.block-quote { font-size: 12px; color: var(--mc-text-primary); line-height: 1.55; font-style: italic; }
.block-loc { font-size: 11px; color: var(--mc-text-tertiary); margin-top: 6px; font-family: var(--mc-font-mono); }

.precedent-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.precedent-headline { font-size: 12px; font-weight: 600; color: var(--mc-text-primary); }
.precedent-outcome { font-size: 11px; color: var(--mc-text-secondary); margin-top: 2px; line-height: 1.4; }

.evidence-foot {
  border-top: 1px solid var(--mc-border-light);
  padding-top: 10px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.foot-meta { display: flex; flex-direction: column; gap: 2px; }
.foot-label { font-size: 10px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--mc-text-tertiary); }
.foot-value { font-size: 12px; color: var(--mc-text-primary); font-weight: 500; }

@media (max-width: 1200px) {
  .review-shell { grid-template-columns: 240px 1fr 280px; }
}
@media (max-width: 1000px) {
  .review-shell { grid-template-columns: 1fr; }
  .review-list, .review-evidence { max-height: 240px; }
}
</style>
