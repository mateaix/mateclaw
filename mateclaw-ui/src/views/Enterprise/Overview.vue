<template>
  <div class="overview">
    <div class="metric-strip">
      <div class="metric" v-for="m in metrics" :key="m.key" :class="m.tone">
        <div class="metric-label">{{ m.label }}</div>
        <div class="metric-value">{{ m.value }}</div>
        <div class="metric-delta" :class="m.deltaTone">{{ m.delta }}</div>
      </div>
    </div>

    <div class="overview-grid">
      <article class="panel work-queue mc-surface-card">
        <header class="panel-head">
          <div>
            <h3 class="panel-title">{{ t('enterprise.overview.queueTitle') }}</h3>
            <p class="panel-desc">{{ t('enterprise.overview.queueDesc') }}</p>
          </div>
          <div class="filter-row">
            <button v-for="f in queueFilters" :key="f.key"
                    class="chip" :class="{ active: activeFilter === f.key }"
                    @click="activeFilter = f.key">
              {{ f.label }}
              <span class="chip-count">{{ f.count }}</span>
            </button>
          </div>
        </header>

        <ul class="queue-list">
          <li v-for="item in filteredQueue" :key="item.id" class="queue-item" @click="openCase(item.id)">
            <span class="risk-pill" :class="`risk-${item.risk}`">{{ riskLabel(item.risk) }}</span>
            <div class="queue-main">
              <div class="queue-title">{{ item.title }}</div>
              <div class="queue-meta">
                <span>{{ item.type }}</span>
                <span class="dot"></span>
                <span>{{ item.owner }}</span>
                <span class="dot"></span>
                <span>{{ item.eta }}</span>
              </div>
            </div>
            <div class="queue-status" :class="`status-${item.status}`">
              {{ statusLabel(item.status) }}
            </div>
          </li>
        </ul>
      </article>

      <article class="panel evidence-panel mc-surface-card">
        <header class="panel-head">
          <div>
            <h3 class="panel-title">{{ t('enterprise.overview.evidenceTitle') }}</h3>
            <p class="panel-desc">{{ t('enterprise.overview.evidenceDesc') }}</p>
          </div>
        </header>

        <div class="coverage">
          <div class="coverage-bar">
            <div class="coverage-fill" :style="{ width: '98%' }"></div>
          </div>
          <div class="coverage-text">
            <strong>98%</strong>
            <span>{{ t('enterprise.overview.coverageNote') }}</span>
          </div>
        </div>

        <ul class="evidence-list">
          <li v-for="e in recentEvidence" :key="e.id" class="evidence-item">
            <div class="evidence-marker" :class="`evidence-${e.kind}`"></div>
            <div class="evidence-body">
              <div class="evidence-quote">「{{ e.quote }}」</div>
              <div class="evidence-meta">
                <span>{{ e.source }}</span>
                <span class="dot"></span>
                <span>{{ e.age }}</span>
                <span v-if="e.kind === 'playbook'" class="evidence-tag tag-playbook">{{ t('enterprise.overview.tagPlaybook') }}</span>
                <span v-else-if="e.kind === 'precedent'" class="evidence-tag tag-precedent">{{ t('enterprise.overview.tagPrecedent') }}</span>
                <span v-else class="evidence-tag tag-source">{{ t('enterprise.overview.tagSource') }}</span>
              </div>
            </div>
          </li>
        </ul>
      </article>
    </div>

    <article class="panel pipeline-panel mc-surface-card">
      <header class="panel-head">
        <div>
          <h3 class="panel-title">{{ t('enterprise.overview.pipelineTitle') }}</h3>
          <p class="panel-desc">{{ t('enterprise.overview.pipelineDesc') }}</p>
        </div>
      </header>
      <div class="pipeline">
        <div class="pipeline-step"
             v-for="(s, i) in pipeline" :key="s.label"
             :class="{ done: s.done, active: !s.done && i === firstActive }">
          <div class="step-bullet">{{ s.done ? '✓' : i + 1 }}</div>
          <div class="step-label">{{ s.label }}</div>
          <div class="step-desc">{{ s.desc }}</div>
        </div>
      </div>
    </article>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const emit = defineEmits<{ (e: 'open-case', id: string): void }>()

type QueueFilterKey = 'all' | 'high' | 'pending' | 'today'
const activeFilter = ref<QueueFilterKey>('all')

const metrics = computed(() => [
  { key: 'pending', label: t('enterprise.overview.metricPending'), value: '23', delta: '+4', deltaTone: 'up', tone: '' },
  { key: 'risk',    label: t('enterprise.overview.metricHighRisk'), value: '7', delta: '+2', deltaTone: 'up bad', tone: 'tone-warn' },
  { key: 'account', label: t('enterprise.overview.metricAccounts'), value: '14', delta: '+1', deltaTone: 'up', tone: '' },
  { key: 'cited',   label: t('enterprise.overview.metricCited'), value: '98%', delta: '+3pp', deltaTone: 'up', tone: 'tone-good' },
])

const queueFilters = computed<{ key: QueueFilterKey; label: string; count: number }[]>(() => [
  { key: 'all', label: t('enterprise.overview.filterAll'), count: 23 },
  { key: 'high', label: t('enterprise.overview.filterHighRisk'), count: 7 },
  { key: 'pending', label: t('enterprise.overview.filterPending'), count: 12 },
  { key: 'today', label: t('enterprise.overview.filterToday'), count: 5 },
])

interface QueueItem {
  id: string
  title: string
  type: string
  owner: string
  eta: string
  risk: 'high' | 'medium' | 'low'
  status: 'ai_reviewed' | 'pending_legal' | 'approved' | 'signal'
}

const queue: QueueItem[] = [
  { id: 'msa-acme-2026q2', title: 'Acme Corp - MSA 续约（赔偿条款异常）', type: '合同审查', owner: '李法务', eta: '今天', risk: 'high', status: 'ai_reviewed' },
  { id: 'nda-vendora',     title: 'Vendor A NDA - 非常规竞业条款',     type: '合同审查', owner: '王法务', eta: '今天', risk: 'high', status: 'pending_legal' },
  { id: 'signal-acme-jd',  title: 'Acme 招了新 CTO，10 月 14 日',     type: '情报信号', owner: '张销售', eta: '6 小时前', risk: 'low', status: 'signal' },
  { id: 'q2-vendor-cmp',   title: 'Q2 供应商条款横向对比',             type: '合同对比', owner: '李法务', eta: '昨天', risk: 'medium', status: 'ai_reviewed' },
  { id: 'msa-bayer-renew', title: 'Bayer MSA - 自动续约条款偏离 playbook', type: '合同审查', owner: '王法务', eta: '昨天', risk: 'medium', status: 'ai_reviewed' },
  { id: 'signal-zerto-pr', title: 'Zerto 发布与我们竞品的新版本',       type: '情报信号', owner: '张销售', eta: '昨天', risk: 'low', status: 'signal' },
  { id: 'nda-finchen',     title: 'FinChen NDA - 仲裁地条款',           type: '合同审查', owner: '李法务', eta: '前天', risk: 'low', status: 'approved' },
]

const filteredQueue = computed(() => {
  switch (activeFilter.value) {
    case 'high': return queue.filter(q => q.risk === 'high')
    case 'pending': return queue.filter(q => q.status === 'pending_legal' || q.status === 'ai_reviewed')
    case 'today': return queue.filter(q => q.eta === '今天' || q.eta.includes('小时'))
    default: return queue
  }
})

function openCase(id: string) {
  emit('open-case', id)
}

function riskLabel(r: 'high' | 'medium' | 'low'): string {
  return r === 'high' ? t('enterprise.risk.high')
       : r === 'medium' ? t('enterprise.risk.medium')
       : t('enterprise.risk.low')
}

function statusLabel(s: QueueItem['status']): string {
  switch (s) {
    case 'ai_reviewed': return t('enterprise.status.aiReviewed')
    case 'pending_legal': return t('enterprise.status.pendingLegal')
    case 'approved': return t('enterprise.status.approved')
    case 'signal': return t('enterprise.status.signal')
  }
}

interface Evidence { id: string; quote: string; source: string; age: string; kind: 'playbook' | 'precedent' | 'source' }
const recentEvidence: Evidence[] = [
  { id: 'e1', quote: '本协议双方因履行本协议发生的争议，应当先行协商解决',
    source: 'Acme MSA · 第 8.2 条 · 第 14 页', age: '12 分钟前', kind: 'source' },
  { id: 'e2', quote: '我司标准合同要求对方承担因数据泄露产生的全部直接和间接损失',
    source: 'Legal Playbook · 数据保护条款 v3.1', age: '今天 09:42', kind: 'playbook' },
  { id: 'e3', quote: '类似案件 2025-06 与 Zerto 谈判，最终采用阶梯式赔偿上限',
    source: '历史合同 · Zerto MSA 2025', age: '今天 09:12', kind: 'precedent' },
  { id: 'e4', quote: 'Acme 在 LinkedIn 上招聘 Privacy Counsel，1 个职位',
    source: 'LinkedIn 信号 · 10 月 14 日', age: '6 小时前', kind: 'source' },
]

const pipeline = [
  { label: t('enterprise.overview.step1'),  desc: t('enterprise.overview.step1Desc'),  done: true },
  { label: t('enterprise.overview.step2'),  desc: t('enterprise.overview.step2Desc'),  done: true },
  { label: t('enterprise.overview.step3'),  desc: t('enterprise.overview.step3Desc'),  done: true },
  { label: t('enterprise.overview.step4'),  desc: t('enterprise.overview.step4Desc'),  done: false },
  { label: t('enterprise.overview.step5'),  desc: t('enterprise.overview.step5Desc'),  done: false },
]
const firstActive = computed(() => pipeline.findIndex(s => !s.done))
</script>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 18px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* Inset the scrollbar so it doesn't hug the cards, and give the last
     card breathing room from the bottom edge of the body. */
  padding-right: 4px;
  padding-bottom: 16px;
}
/* Each panel inside an overflow-y flex column must opt out of the default
   flex-shrink behaviour, otherwise the browser will compress them to fit
   inside the visible body instead of letting them overflow into the
   scrollable area. Without this the pipeline panel renders at ~38px tall
   (= just its padding) and its content overflows below the visible card. */
.overview > .panel,
.overview > .metric-strip,
.overview > .overview-grid {
  flex-shrink: 0;
}

/* === metric strip === */
.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.metric {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  box-shadow: var(--mc-shadow-soft);
  position: relative;
  overflow: hidden;
}
.metric::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--mc-border);
}
.metric.tone-warn::before { background: #d97706; }
.metric.tone-good::before { background: #15803d; }
.metric-label { font-size: 12px; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.05em; }
.metric-value { font-size: 28px; font-weight: 700; color: var(--mc-text-primary); line-height: 1; }
.metric-delta { font-size: 12px; color: var(--mc-text-secondary); }
.metric-delta.up { color: #15803d; }
.metric-delta.up.bad { color: #b91c1c; }

/* === panels === */
.overview-grid {
  display: grid;
  grid-template-columns: 1.2fr 0.85fr;
  gap: 14px;
}
.panel {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
}
.panel-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.panel-title { font-size: 16px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 2px; }
.panel-desc { font-size: 12px; color: var(--mc-text-secondary); margin: 0; max-width: 480px; line-height: 1.5; }

/* === filter chips === */
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
  transition: all 0.15s;
}
.chip:hover { color: var(--mc-text-primary); border-color: var(--mc-border); }
.chip.active { background: var(--mc-primary-bg); color: var(--mc-primary-hover); border-color: var(--mc-primary); }
.chip-count { font-size: 11px; opacity: 0.7; }

/* === queue list === */
.queue-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.queue-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 12px;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--mc-border-light);
  cursor: pointer;
  transition: background 0.15s;
}
.queue-item:hover { background: var(--mc-bg-muted); margin: 0 -8px; padding: 12px 8px; border-radius: 8px; border-bottom-color: transparent; }
.queue-item:last-child { border-bottom: none; }

.risk-pill {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: 999px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  min-width: 44px;
  text-align: center;
}
.risk-high { background: #fee2e2; color: #b91c1c; }
.risk-medium { background: #fef3c7; color: #b45309; }
.risk-low { background: #dcfce7; color: #15803d; }

.queue-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.queue-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.queue-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}
.dot { width: 3px; height: 3px; background: currentColor; border-radius: 50%; opacity: 0.5; }

.queue-status {
  font-size: 11px;
  font-weight: 600;
  padding: 4px 9px;
  border-radius: 6px;
  white-space: nowrap;
}
.status-ai_reviewed { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.status-pending_legal { background: #fef3c7; color: #b45309; }
.status-approved { background: #dcfce7; color: #15803d; }
.status-signal { background: var(--mc-accent-soft); color: var(--mc-accent); }

/* === evidence panel === */
.coverage { background: var(--mc-bg-muted); padding: 12px 14px; border-radius: 10px; display: flex; flex-direction: column; gap: 6px; }
.coverage-bar { height: 8px; background: var(--mc-bg-sunken); border-radius: 999px; overflow: hidden; }
.coverage-fill { height: 100%; background: linear-gradient(90deg, var(--mc-primary), var(--mc-primary-light)); border-radius: 999px; }
.coverage-text { display: flex; align-items: baseline; gap: 8px; font-size: 12px; color: var(--mc-text-secondary); }
.coverage-text strong { font-size: 18px; font-weight: 700; color: var(--mc-primary); }

.evidence-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.evidence-item { display: flex; gap: 10px; }
.evidence-marker {
  width: 4px;
  border-radius: 2px;
  flex-shrink: 0;
  align-self: stretch;
}
.evidence-source { background: var(--mc-border); }
.evidence-playbook { background: var(--mc-primary); }
.evidence-precedent { background: var(--mc-accent); }

.evidence-body { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.evidence-quote {
  font-size: 13px;
  color: var(--mc-text-primary);
  line-height: 1.55;
  font-style: italic;
}
.evidence-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  flex-wrap: wrap;
}
.evidence-tag {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 4px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.tag-source { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.tag-playbook { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.tag-precedent { background: var(--mc-accent-soft); color: var(--mc-accent); }

/* === pipeline ===
   Centered horizontal stepper. The connector is a pseudo-element on each
   step that spans from the bullet's right edge to the next step's bullet
   (i.e. just past its left edge). Pulling it out of the flex flow lets the
   label and desc sit cleanly below the bullet without colliding with the
   horizontal line. */
.pipeline {
  display: flex;
  align-items: stretch;
  padding: 4px 8px;
}
.pipeline-step {
  flex: 1;
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 0 8px;
}
.pipeline-step::after {
  /* Connector drawn at the bullet's vertical centre, behind the bullet via
     z-index. last-child has no connector. */
  content: '';
  position: absolute;
  top: 16px;
  left: 50%;
  right: -50%;
  height: 2px;
  background: var(--mc-border-light);
  z-index: 0;
}
.pipeline-step.done::after { background: var(--mc-primary); }
.pipeline-step:last-child::after { display: none; }

.step-bullet {
  width: 32px; height: 32px;
  border-radius: 50%;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-tertiary);
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid var(--mc-border-light);
  position: relative;
  z-index: 1;
  margin-bottom: 10px;
  flex-shrink: 0;
}
.pipeline-step.done .step-bullet { background: var(--mc-primary); color: white; border-color: var(--mc-primary); }
.pipeline-step.active .step-bullet { background: var(--mc-primary-bg); color: var(--mc-primary-hover); border-color: var(--mc-primary); }

.step-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-text-primary);
  line-height: 1.3;
}
.pipeline-step.active .step-label { color: var(--mc-primary-hover); }
.step-desc {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin-top: 4px;
  line-height: 1.45;
  max-width: 180px;
}

@media (max-width: 1100px) {
  .overview-grid { grid-template-columns: 1fr; }
  .metric-strip { grid-template-columns: repeat(2, 1fr); }
}
</style>
