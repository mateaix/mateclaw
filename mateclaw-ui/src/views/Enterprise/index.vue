<template>
  <div class="enterprise-shell">
    <header class="enterprise-head">
      <div class="enterprise-eyebrow">{{ t('enterprise.eyebrow') }}</div>
      <h1 class="enterprise-title">{{ t('enterprise.title') }}</h1>
      <p class="enterprise-subtitle">{{ t('enterprise.subtitle') }}</p>
    </header>

    <nav class="enterprise-tabs">
      <button
        v-for="tab in tabs" :key="tab.key"
        class="enterprise-tab" :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        <span class="tab-label">{{ tab.label }}</span>
        <span v-if="tab.count != null" class="tab-count">{{ tab.count }}</span>
      </button>
    </nav>

    <section class="enterprise-body">
      <Overview v-if="activeTab === 'overview'" @open-case="onOpenCase" />
      <ContractReview v-else-if="activeTab === 'contract'" :focus="caseFocus" />
      <AccountIntel v-else-if="activeTab === 'account'" />
      <Approvals v-else-if="activeTab === 'approvals'" />
      <Audit v-else-if="activeTab === 'audit'" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Overview from './Overview.vue'
import ContractReview from './ContractReview.vue'
import AccountIntel from './AccountIntel.vue'
import Approvals from './Approvals.vue'
import Audit from './Audit.vue'

const { t } = useI18n()
type TabKey = 'overview' | 'contract' | 'account' | 'approvals' | 'audit'
const activeTab = ref<TabKey>('overview')
const caseFocus = ref<string | null>(null)

const tabs = computed<{ key: TabKey; label: string; count: number | null }[]>(() => [
  { key: 'overview', label: t('enterprise.tabs.overview'), count: null },
  { key: 'contract', label: t('enterprise.tabs.contract'), count: 23 },
  { key: 'account',  label: t('enterprise.tabs.account'),  count: 14 },
  { key: 'approvals', label: t('enterprise.tabs.approvals'), count: 5 },
  { key: 'audit', label: t('enterprise.tabs.audit'), count: null },
])

function onOpenCase(id: string) {
  caseFocus.value = id
  activeTab.value = 'contract'
}
</script>

<style scoped>
.enterprise-shell {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  padding: 28px 32px 24px;
  gap: 18px;
  background: var(--mc-bg);
  /* Constrain the inner body so each tab can decide how to overflow:
     vertical-stack pages scroll the body itself, three-pane grid pages
     keep each pane scrolling independently. Without this, content that
     exceeds the viewport falls outside the layout box and is unreachable. */
  overflow: hidden;
}

.enterprise-head { display: flex; flex-direction: column; gap: 4px; }
.enterprise-eyebrow {
  font-size: var(--mc-text-xs);
  color: var(--mc-primary);
  text-transform: uppercase;
  letter-spacing: 0.12em;
  font-weight: 600;
}
.enterprise-title {
  margin: 0;
  font-size: 26px;
  line-height: 1.2;
  font-weight: 700;
  color: var(--mc-text-primary);
}
.enterprise-subtitle {
  margin: 0;
  font-size: 14px;
  color: var(--mc-text-secondary);
  max-width: 720px;
  line-height: 1.55;
}

.enterprise-tabs {
  display: inline-flex;
  align-self: flex-start;
  padding: 4px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
  gap: 2px;
}
.enterprise-tab {
  border: none;
  background: none;
  padding: 8px 14px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-secondary);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transition: all 0.15s;
}
.enterprise-tab:hover { color: var(--mc-text-primary); }
.enterprise-tab.active {
  background: var(--mc-bg-elevated);
  color: var(--mc-primary);
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}
.tab-count {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--mc-primary-bg);
  color: var(--mc-primary-hover);
}
.enterprise-tab.active .tab-count { background: var(--mc-primary); color: white; }

/* The body fills the remaining height and gives its single child a strict
   height contract via flex. Page roots must declare `flex: 1; min-height: 0;`
   so they consume that height and can choose their own overflow strategy. */
.enterprise-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
