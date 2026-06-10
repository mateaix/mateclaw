<template>
  <div class="fact-list">
    <!-- Search -->
    <div class="fact-search">
      <input
        v-model="keyword"
        class="fact-search-input"
        :placeholder="t('memory.facts.searchPlaceholder')"
        @keydown.enter="search"
      />
    </div>

    <!-- Contradiction badge -->
    <div v-if="contradictions.length > 0" class="contradiction-bar" @click="showContradictions = !showContradictions">
      <span class="contradiction-dot" />
      <span>{{ t('memory.facts.contradictions', { count: contradictions.length }) }}</span>
      <MemoryIcon :name="showContradictions ? 'chevron-up' : 'chevron-down'" :size="12" />
    </div>

    <!-- Contradiction inbox -->
    <Transition name="slide-down">
      <div v-if="showContradictions && contradictions.length > 0" class="contradiction-inbox">
        <div v-for="c in contradictions" :key="c.id" class="contradiction-item">
          <p class="contradiction-desc">{{ c.description }}</p>
          <div class="contradiction-actions">
            <button v-for="r in ['KEEP_A', 'KEEP_B', 'MERGE', 'IGNORE']" :key="r"
              class="resolve-btn" @click="resolve(c.id, r)">{{ t('memory.facts.resolve.' + r) }}</button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- Loading -->
    <MemorySkeleton v-if="loading" />

    <!-- Empty -->
    <MemoryEmptyState v-else-if="facts.length === 0" icon="archive" :text="t('memory.facts.empty')" />

    <!-- Fact cards -->
    <div v-else class="fact-cards">
      <div v-for="fact in facts" :key="fact.id" class="fact-card">
        <div class="fact-top">
          <span class="fact-subject">{{ fact.subject }}</span>
          <span class="fact-category">{{ fact.category }}</span>
        </div>
        <div class="fact-triple">
          <span class="fact-predicate">{{ fact.predicate }}</span>
          <span class="fact-object">{{ fact.objectValue }}</span>
        </div>
        <div class="fact-bottom">
          <FactTrustBar :trust="fact.trust" />
          <span class="fact-use-count" v-if="fact.useCount > 0">{{ t('memory.facts.used', { n: fact.useCount }) }}</span>
          <div class="fact-actions">
            <button class="action-btn helpful" @click="feedback(fact.id, 'HELPFUL')" :title="t('memory.facts.helpful')">
              <MemoryIcon name="thumbs-up" :size="14" />
            </button>
            <button class="action-btn unhelpful" @click="feedback(fact.id, 'UNHELPFUL')" :title="t('memory.facts.unhelpful')">
              <MemoryIcon name="thumbs-down" :size="14" />
            </button>
            <button class="action-btn forget" @click="forget(fact.id)" :title="t('memory.facts.forget')">
              <MemoryIcon name="trash" :size="14" />
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { http } from '@/api'
import FactTrustBar from './FactTrustBar.vue'
import MemoryIcon from './MemoryIcon.vue'
import MemorySkeleton from './MemorySkeleton.vue'
import MemoryEmptyState from './MemoryEmptyState.vue'

const props = defineProps<{ agentId: string | number }>()
const { t } = useI18n()

const facts = ref<any[]>([])
const contradictions = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const showContradictions = ref(false)

watch(() => props.agentId, () => { keyword.value = ''; loadFacts(); loadContradictions() }, { immediate: true })

async function loadFacts() {
  loading.value = true
  try {
    const params: any = {}
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const res = await http.get(`/memory/${props.agentId}/facts`, { params })
    facts.value = res.data || []
  } catch { facts.value = [] }
  finally { loading.value = false }
}

async function loadContradictions() {
  try {
    const res = await http.get(`/memory/${props.agentId}/facts/contradictions`)
    contradictions.value = res.data || []
  } catch { contradictions.value = [] }
}

function search() { loadFacts() }

async function feedback(factId: number, kind: string) {
  try {
    await http.post(`/memory/${props.agentId}/facts/${factId}/feedback`, { kind })
    mcToast.success(t('memory.facts.' + (kind === 'HELPFUL' ? 'helpful' : 'unhelpful')))
    loadFacts()
  } catch (e: any) { mcToast.error(e.message || 'Failed') }
}

async function forget(factId: number) {
  try {
    await http.post(`/memory/${props.agentId}/facts/${factId}/forget`)
    mcToast.success(t('memory.facts.forgotten'))
    loadFacts()
  } catch (e: any) { mcToast.error(e.message || 'Failed') }
}

async function resolve(contradictionId: number, resolution: string) {
  try {
    await http.post(`/memory/${props.agentId}/facts/contradictions/${contradictionId}/resolve`, { resolution })
    mcToast.success(t('memory.facts.resolved'))
    loadContradictions()
  } catch (e: any) { mcToast.error(e.message || 'Failed') }
}
</script>

<style scoped>
.fact-search { padding: 0 0 12px; }
.fact-search-input {
  width: 100%; padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 10px;
  background: var(--mc-bg-sunken); font-size: 13px; color: var(--mc-text-primary);
  outline: none; transition: border-color 0.15s;
}
.fact-search-input:focus { border-color: var(--mc-primary); }
.fact-search-input::placeholder { color: var(--mc-text-tertiary); }

/* Contradiction bar */
.contradiction-bar {
  display: flex; align-items: center; gap: 6px; padding: 8px 12px; margin-bottom: 12px;
  border-radius: 10px; background: rgba(255,59,48,0.08); cursor: pointer;
  font-size: 12px; font-weight: 500; color: #ff3b30; transition: background 0.15s;
}
.contradiction-bar:hover { background: rgba(255,59,48,0.12); }
.contradiction-dot { width: 8px; height: 8px; border-radius: 50%; background: #ff3b30; animation: pulse 2s infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }

.contradiction-inbox { margin-bottom: 12px; }
.contradiction-item {
  padding: 10px 12px; margin-bottom: 6px; border-radius: 10px;
  background: var(--mc-bg-sunken); border: 1px solid var(--mc-border-light);
}
.contradiction-desc { margin: 0 0 8px; font-size: 12px; color: var(--mc-text-secondary); line-height: 1.4; }
.contradiction-actions { display: flex; gap: 4px; }
.resolve-btn {
  padding: 3px 8px; border: 1px solid var(--mc-border); border-radius: 6px;
  background: transparent; font-size: 11px; color: var(--mc-text-secondary);
  cursor: pointer; transition: all 0.12s;
}
.resolve-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); }

/* Fact cards */
.fact-cards { display: flex; flex-direction: column; gap: 8px; }
.fact-card {
  padding: 12px 14px; border-radius: 12px; background: var(--mc-bg-sunken);
  border: 1px solid transparent; transition: border-color 0.12s;
}
.fact-card:hover { border-color: var(--mc-border); }
.fact-top { display: flex; align-items: center; justify-content: space-between; }
.fact-subject { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }
.fact-category {
  font-size: 10px; font-weight: 500; text-transform: uppercase; letter-spacing: 0.5px;
  color: var(--mc-text-tertiary); padding: 2px 6px; border-radius: 4px; background: var(--mc-bg-elevated);
}
.fact-triple { margin-top: 4px; font-size: 12px; }
.fact-predicate { color: var(--mc-text-tertiary); margin-right: 4px; }
.fact-object { color: var(--mc-text-secondary); }
.fact-bottom { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.fact-use-count { font-size: 10px; color: var(--mc-text-tertiary); }
.fact-actions { margin-left: auto; display: flex; gap: 2px; opacity: 0; transition: opacity 0.15s; }
.fact-card:hover .fact-actions { opacity: 1; }
.action-btn {
  width: 26px; height: 26px; display: flex; align-items: center; justify-content: center;
  border: none; border-radius: 6px; background: transparent;
  color: var(--mc-text-tertiary); cursor: pointer; transition: all 0.12s;
}
.action-btn:hover { background: var(--mc-bg-elevated); color: var(--mc-text-secondary); }
.action-btn.helpful:hover { color: #34c759; }
.action-btn.unhelpful:hover { color: #ff9f0a; }
.action-btn.forget:hover { background: rgba(255,59,48,0.1); color: #ff3b30; }

.slide-down-enter-active, .slide-down-leave-active { transition: all 0.2s ease; }
.slide-down-enter-from, .slide-down-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
