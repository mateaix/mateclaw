<template>
  <div class="adv-panel">
    <!-- Sub-navigation across the five advanced management surfaces -->
    <div class="adv-subtabs">
      <button
        v-for="s in sections" :key="s.key"
        class="adv-subtab" :class="{ active: section === s.key }"
        @click="switchSection(s.key)"
      >{{ s.label }}</button>
    </div>

    <!-- ===================== REQ-1: PageType Profile ===================== -->
    <section v-if="section === 'profile'" class="adv-section">
      <header class="adv-head">
        <div>
          <h3>{{ t('wiki.adv.profile.title') }}</h3>
          <p class="adv-desc">{{ t('wiki.adv.profile.desc') }}</p>
        </div>
        <span v-if="profile.builtinDefault" class="badge badge-muted">{{ t('wiki.adv.profile.builtin') }}</span>
        <span v-else class="badge badge-ok">v{{ profile.version }}</span>
      </header>
      <textarea
        v-model="profile.config"
        class="code-editor" spellcheck="false"
        :placeholder="t('wiki.adv.profile.placeholder')"
      ></textarea>
      <div v-if="profile.issues.length" class="issue-box">
        <div v-for="(iss, i) in profile.issues" :key="i" class="issue-line">⚠ {{ iss }}</div>
      </div>
      <div class="adv-actions">
        <button class="btn-ghost" @click="validateProfile" :disabled="profile.busy">{{ t('wiki.adv.validate') }}</button>
        <button class="btn-ghost danger" @click="resetProfile" :disabled="profile.busy">{{ t('wiki.adv.profile.reset') }}</button>
        <button class="btn-primary" @click="saveProfile" :disabled="profile.busy">{{ t('common.save') }}</button>
      </div>
    </section>

    <!-- ===================== REQ-2: Layers & Stale ===================== -->
    <section v-else-if="section === 'layers'" class="adv-section">
      <header class="adv-head">
        <div>
          <h3>{{ t('wiki.adv.layers.title') }}</h3>
          <p class="adv-desc">{{ t('wiki.adv.layers.desc') }}</p>
        </div>
        <button class="btn-ghost" @click="loadLayers" :disabled="layers.busy">{{ t('common.refresh') }}</button>
      </header>
      <div class="layer-stats">
        <div class="stat-chip"><b>{{ layerGroups.fact.length }}</b> {{ t('wiki.adv.layers.fact') }}</div>
        <div class="stat-chip"><b>{{ layerGroups.experience.length }}</b> {{ t('wiki.adv.layers.experience') }}</div>
        <div class="stat-chip"><b>{{ layerGroups.other.length }}</b> {{ t('wiki.adv.layers.other') }}</div>
        <div class="stat-chip stale"><b>{{ staleCount }}</b> {{ t('wiki.adv.layers.stale') }}</div>
      </div>
      <div v-if="layers.pages.length === 0 && !layers.busy" class="empty-hint">{{ t('wiki.adv.layers.empty') }}</div>
      <table v-else class="adv-table">
        <thead><tr>
          <th>{{ t('wiki.adv.layers.page') }}</th>
          <th>{{ t('wiki.adv.layers.layer') }}</th>
          <th>{{ t('wiki.adv.layers.status') }}</th>
        </tr></thead>
        <tbody>
          <tr v-for="p in layers.pages" :key="p.id" :class="{ 'row-stale': isStale(p) }">
            <td class="cell-title">{{ p.title || p.slug }}</td>
            <td><span class="layer-tag" :class="layerClass(p.knowledgeLayer)">{{ p.knowledgeLayer || '—' }}</span></td>
            <td>
              <span v-if="isStale(p)" class="badge badge-warn" :title="staleReason(p)">{{ t('wiki.adv.layers.staleTag') }}</span>
              <span v-else class="badge badge-ok">{{ t('wiki.adv.layers.fresh') }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- ===================== REQ-3: Permissions ===================== -->
    <section v-else-if="section === 'permissions'" class="adv-section">
      <header class="adv-head">
        <div>
          <h3>{{ t('wiki.adv.perm.title') }}</h3>
          <p class="adv-desc">{{ t('wiki.adv.perm.desc') }}</p>
        </div>
      </header>
      <div class="perm-agent-row">
        <label>{{ t('wiki.adv.perm.agent') }}</label>
        <AgentPickerDialog
          v-model="perm.agentId"
          :agents="agents"
          :placeholder="t('wiki.adv.perm.selectAgent')"
          clearable
          @change="loadPermissions"
        />
      </div>
      <template v-if="perm.agentId">
        <table class="adv-table">
          <thead><tr>
            <th>{{ t('wiki.adv.perm.pageType') }}</th>
            <th>R</th><th>C</th><th>U</th><th>D</th>
            <th>{{ t('wiki.adv.perm.policy') }}</th>
            <th></th>
          </tr></thead>
          <tbody>
            <tr v-for="row in perm.rows" :key="row.id">
              <td class="cell-title">{{ row.pageType }}</td>
              <td>{{ flag(row.canRead) }}</td>
              <td>{{ flag(row.canCreate) }}</td>
              <td>{{ flag(row.canUpdate) }}</td>
              <td>{{ flag(row.canDelete) }}</td>
              <td><span class="badge" :class="policyClass(row.writePolicy)">{{ row.writePolicy }}</span></td>
              <td><button class="link-danger" @click="deletePermission(row)">{{ t('common.delete') }}</button></td>
            </tr>
            <tr v-if="perm.rows.length === 0"><td colspan="7" class="empty-hint">{{ t('wiki.adv.perm.noRows') }}</td></tr>
          </tbody>
        </table>
        <div class="perm-add">
          <input v-model.trim="perm.draft.pageType" class="form-input compact" :placeholder="t('wiki.adv.perm.pageTypeHint')" />
          <label class="ck"><input type="checkbox" v-model="perm.draft.canRead" /> R</label>
          <label class="ck"><input type="checkbox" v-model="perm.draft.canCreate" /> C</label>
          <label class="ck"><input type="checkbox" v-model="perm.draft.canUpdate" /> U</label>
          <label class="ck"><input type="checkbox" v-model="perm.draft.canDelete" /> D</label>
          <select v-model="perm.draft.writePolicy" class="form-input compact">
            <option value="allow">allow</option>
            <option value="approval_required">approval_required</option>
            <option value="deny">deny</option>
          </select>
          <button class="btn-primary" @click="savePermission" :disabled="!perm.draft.pageType">{{ t('wiki.adv.perm.addRule') }}</button>
        </div>
      </template>
    </section>

    <!-- ===================== REQ-4: Source Watcher ===================== -->
    <section v-else-if="section === 'watcher'" class="adv-section">
      <header class="adv-head">
        <div>
          <h3>{{ t('wiki.adv.watcher.title') }}</h3>
          <p class="adv-desc">{{ t('wiki.adv.watcher.desc') }}</p>
        </div>
        <button class="btn-ghost" @click="loadWatcher" :disabled="watcher.busy">{{ t('common.refresh') }}</button>
      </header>
      <div class="kv-grid">
        <div class="kv"><span>{{ t('wiki.adv.watcher.enabled') }}</span><b>{{ watcher.data.watcherEnabled ? t('common.yes') : t('common.no') }}</b></div>
        <div class="kv"><span>{{ t('wiki.adv.watcher.active') }}</span><b>{{ watcher.data.active ? t('common.yes') : t('common.no') }}</b></div>
        <div class="kv"><span>{{ t('wiki.adv.watcher.interval') }}</span><b>{{ watcher.data.intervalMs ? (watcher.data.intervalMs / 1000) + 's' : '—' }}</b></div>
        <div class="kv"><span>{{ t('wiki.adv.watcher.sourceType') }}</span><b>{{ watcher.data.sourceType || '—' }}</b></div>
      </div>
      <label class="field-label">{{ t('wiki.adv.watcher.directory') }}</label>
      <textarea
        v-model="watcher.dir"
        class="code-editor dir-editor"
        spellcheck="false"
        :placeholder="t('wiki.adv.watcher.dirHint')"
      ></textarea>
      <div class="adv-actions">
        <button class="btn-ghost" @click="saveDirectory" :disabled="watcher.busy">{{ t('common.save') }}</button>
      </div>
      <p v-if="watcher.data.availableSourceTypes?.length" class="adv-desc">
        {{ t('wiki.adv.watcher.availableTypes') }}: {{ watcher.data.availableSourceTypes.join(', ') }}
      </p>
      <div class="adv-actions">
        <button class="btn-primary" @click="triggerScan" :disabled="watcher.busy || !watcher.data.active">{{ t('wiki.adv.watcher.scanNow') }}</button>
      </div>
      <div v-if="watcher.lastScan" class="issue-box ok">
        {{ t('wiki.adv.watcher.scanResult', { scanned: watcher.lastScan.scanned, added: watcher.lastScan.added, skipped: watcher.lastScan.skipped, errors: watcher.lastScan.errors }) }}
      </div>
    </section>

    <!-- ===================== REQ-5: Pipeline ===================== -->
    <section v-else-if="section === 'pipeline'" class="adv-section">
      <header class="adv-head">
        <div>
          <h3>{{ t('wiki.adv.pipeline.title') }}</h3>
          <p class="adv-desc">{{ t('wiki.adv.pipeline.desc') }}</p>
        </div>
        <button class="btn-ghost" @click="loadPipelines" :disabled="pipeline.busy">{{ t('common.refresh') }}</button>
      </header>
      <table class="adv-table">
        <thead><tr>
          <th>{{ t('wiki.adv.pipeline.name') }}</th>
          <th>{{ t('wiki.adv.pipeline.trigger') }}</th>
          <th>{{ t('wiki.adv.pipeline.enabled') }}</th>
          <th></th>
        </tr></thead>
        <tbody>
          <tr v-for="d in pipeline.defs" :key="d.id">
            <td class="cell-title">{{ d.name }}</td>
            <td>{{ d.triggerType }}</td>
            <td>{{ d.enabled ? '✓' : '—' }}</td>
            <td class="cell-actions">
              <button class="link" @click="viewRuns(d)">{{ t('wiki.adv.pipeline.runs') }}</button>
              <button class="link-danger" @click="deletePipeline(d)">{{ t('common.delete') }}</button>
            </td>
          </tr>
          <tr v-if="pipeline.defs.length === 0"><td colspan="4" class="empty-hint">{{ t('wiki.adv.pipeline.empty') }}</td></tr>
        </tbody>
      </table>

      <div v-if="pipeline.runsFor" class="runs-box">
        <div class="runs-head">
          <b>{{ t('wiki.adv.pipeline.runsFor', { name: pipeline.runsFor.name }) }}</b>
          <button class="link" @click="pipeline.runsFor = null">{{ t('common.close') }}</button>
        </div>
        <table class="adv-table compact">
          <thead><tr><th>#</th><th>{{ t('wiki.adv.pipeline.status') }}</th><th>{{ t('wiki.adv.pipeline.startedAt') }}</th></tr></thead>
          <tbody>
            <tr v-for="r in pipeline.runs" :key="r.id">
              <td>{{ r.id }}</td>
              <td><span class="badge" :class="runClass(r.status)">{{ r.status }}</span></td>
              <td>{{ r.createTime }}</td>
            </tr>
            <tr v-if="pipeline.runs.length === 0"><td colspan="3" class="empty-hint">{{ t('wiki.adv.pipeline.noRuns') }}</td></tr>
          </tbody>
        </table>
      </div>

      <label class="field-label">{{ t('wiki.adv.pipeline.editor') }}</label>
      <textarea
        v-model="pipeline.config"
        class="code-editor" spellcheck="false"
        :placeholder="t('wiki.adv.pipeline.editorHint')"
      ></textarea>
      <div v-if="pipeline.issues.length" class="issue-box">
        <div v-for="(iss, i) in pipeline.issues" :key="i" class="issue-line">⚠ {{ iss }}</div>
      </div>
      <div class="adv-actions">
        <button class="btn-ghost" @click="validatePipeline" :disabled="pipeline.busy">{{ t('wiki.adv.validate') }}</button>
        <button class="btn-primary" @click="savePipeline" :disabled="pipeline.busy">{{ t('wiki.adv.pipeline.saveDef') }}</button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore } from '@/stores/useWikiStore'
import { useAgentStore } from '@/stores/useAgentStore'
import { wikiApi } from '@/api/index'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import AgentPickerDialog from '@/components/common/AgentPickerDialog.vue'

const { t } = useI18n()
const store = useWikiStore()
const agentStore = useAgentStore()

// kbId stays a string for its whole lifecycle (Snowflake precision rule).
const kbId = computed(() => (store.currentKB ? String(store.currentKB.id) : ''))
const agents = computed(() => agentStore.agents)

const section = ref<'profile' | 'layers' | 'permissions' | 'watcher' | 'pipeline'>('profile')
const sections = computed(() => [
  { key: 'profile' as const, label: t('wiki.adv.profile.tab') },
  { key: 'layers' as const, label: t('wiki.adv.layers.tab') },
  { key: 'permissions' as const, label: t('wiki.adv.perm.tab') },
  { key: 'watcher' as const, label: t('wiki.adv.watcher.tab') },
  { key: 'pipeline' as const, label: t('wiki.adv.pipeline.tab') },
])

const loaded = reactive<Record<string, boolean>>({})
function switchSection(key: typeof section.value) {
  section.value = key
  if (loaded[key]) return
  loaded[key] = true
  if (key === 'layers') loadLayers()
  else if (key === 'permissions') { if (agents.value.length === 0) agentStore.fetchAgents() }
  else if (key === 'watcher') loadWatcher()
  else if (key === 'pipeline') loadPipelines()
}

function unwrap(res: any) { return res?.data ?? res }
function errMsg(e: any, fallback: string) { return e?.response?.data?.message || fallback }

// ---- REQ-1 Profile ----
const profile = reactive({ config: '', version: 0, builtinDefault: true, issues: [] as string[], busy: false })
async function loadProfile() {
  if (!kbId.value) return
  try {
    const d = unwrap(await wikiApi.getPageTypeProfile(kbId.value))
    profile.config = typeof d.config === 'string' ? d.config : JSON.stringify(d.config, null, 2)
    profile.version = d.version
    profile.builtinDefault = d.builtinDefault
  } catch (e: any) { mcToast.error(errMsg(e, 'Load profile failed')) }
}
async function validateProfile() {
  profile.busy = true
  try {
    const d = unwrap(await wikiApi.validatePageTypeProfile(kbId.value, profile.config))
    profile.issues = d.issues || []
    if (d.valid) mcToast.success(t('wiki.adv.validOk'))
  } catch (e: any) { mcToast.error(errMsg(e, 'Validate failed')) } finally { profile.busy = false }
}
async function saveProfile() {
  profile.busy = true
  try {
    await wikiApi.savePageTypeProfile(kbId.value, profile.config)
    mcToast.success(t('common.saved'))
    profile.issues = []
    await loadProfile()
  } catch (e: any) { mcToast.error(errMsg(e, 'Save failed')) } finally { profile.busy = false }
}
async function resetProfile() {
  if (!(await mcConfirm({ title: t('wiki.adv.profile.reset'), message: t('wiki.adv.profile.resetConfirm'), tone: 'danger' }))) return
  profile.busy = true
  try {
    await wikiApi.resetPageTypeProfile(kbId.value)
    mcToast.success(t('common.saved'))
    await loadProfile()
  } catch (e: any) { mcToast.error(errMsg(e, 'Reset failed')) } finally { profile.busy = false }
}

// ---- REQ-2 Layers & Stale ----
const layers = reactive({ pages: [] as any[], busy: false })
async function loadLayers() {
  if (!kbId.value) return
  layers.busy = true
  try {
    layers.pages = unwrap(await wikiApi.listPages(kbId.value as any)) || []
  } catch (e: any) { mcToast.error(errMsg(e, 'Load pages failed')) } finally { layers.busy = false }
}
function isStale(p: any) { return p.stale === 1 || p.stale === true }
const staleCount = computed(() => layers.pages.filter(isStale).length)
const layerGroups = computed(() => {
  const g = { fact: [] as any[], experience: [] as any[], other: [] as any[] }
  for (const p of layers.pages) {
    if (p.knowledgeLayer === 'fact') g.fact.push(p)
    else if (p.knowledgeLayer === 'experience') g.experience.push(p)
    else g.other.push(p)
  }
  return g
})
function layerClass(l?: string) { return l === 'fact' ? 'tag-fact' : l === 'experience' ? 'tag-exp' : 'tag-other' }
function staleReason(p: any) {
  try { return p.staleReasonJson ? JSON.stringify(JSON.parse(p.staleReasonJson)) : '' } catch { return p.staleReasonJson || '' }
}

// ---- REQ-3 Permissions ----
const perm = reactive({
  agentId: '',
  rows: [] as any[],
  draft: { pageType: '', canRead: true, canCreate: false, canUpdate: false, canDelete: false, writePolicy: 'approval_required' as 'allow' | 'deny' | 'approval_required' },
})
async function loadPermissions() {
  if (!perm.agentId || !kbId.value) { perm.rows = []; return }
  try {
    perm.rows = unwrap(await wikiApi.listPageTypePermissions(kbId.value, perm.agentId)) || []
  } catch (e: any) { mcToast.error(errMsg(e, 'Load permissions failed')) }
}
async function savePermission() {
  if (!perm.draft.pageType) return
  try {
    await wikiApi.savePageTypePermission(kbId.value, perm.agentId, {
      pageType: perm.draft.pageType,
      canRead: perm.draft.canRead ? 1 : 0,
      canCreate: perm.draft.canCreate ? 1 : 0,
      canUpdate: perm.draft.canUpdate ? 1 : 0,
      canDelete: perm.draft.canDelete ? 1 : 0,
      writePolicy: perm.draft.writePolicy,
    })
    mcToast.success(t('common.saved'))
    perm.draft.pageType = ''
    await loadPermissions()
  } catch (e: any) { mcToast.error(errMsg(e, 'Save failed')) }
}
async function deletePermission(row: any) {
  if (!(await mcConfirm({ title: t('common.delete'), message: t('wiki.adv.perm.deleteConfirm', { type: row.pageType }), tone: 'danger' }))) return
  try {
    await wikiApi.deletePageTypePermission(kbId.value, perm.agentId, String(row.id))
    await loadPermissions()
  } catch (e: any) { mcToast.error(errMsg(e, 'Delete failed')) }
}
function flag(v: any) { return v ? '✓' : '·' }
function policyClass(p?: string) { return p === 'allow' ? 'badge-ok' : p === 'deny' ? 'badge-warn' : 'badge-muted' }

// ---- REQ-4 Watcher ----
const watcher = reactive({ data: {} as any, dir: '', lastScan: null as any, busy: false })
async function loadWatcher() {
  if (!kbId.value) return
  watcher.busy = true
  try {
    watcher.data = unwrap(await wikiApi.getSourceWatcher(kbId.value)) || {}
    watcher.dir = watcher.data.sourceDirectory || ''
  } catch (e: any) { mcToast.error(errMsg(e, 'Load watcher failed')) } finally { watcher.busy = false }
}
async function saveDirectory() {
  watcher.busy = true
  try {
    await wikiApi.setSourceDirectory(kbId.value, watcher.dir)
    mcToast.success(t('common.saved'))
    await loadWatcher()
  } catch (e: any) { mcToast.error(errMsg(e, 'Save failed')) } finally { watcher.busy = false }
}
async function triggerScan() {
  watcher.busy = true
  try {
    watcher.lastScan = unwrap(await wikiApi.triggerSourceWatcher(kbId.value))
    mcToast.success(t('wiki.adv.watcher.scanDone'))
  } catch (e: any) { mcToast.error(errMsg(e, 'Scan failed')) } finally { watcher.busy = false }
}

// ---- REQ-5 Pipeline ----
const pipeline = reactive({ defs: [] as any[], config: '', issues: [] as string[], runsFor: null as any, runs: [] as any[], busy: false })
async function loadPipelines() {
  if (!kbId.value) return
  pipeline.busy = true
  try {
    pipeline.defs = unwrap(await wikiApi.listPipelines(kbId.value)) || []
  } catch (e: any) { mcToast.error(errMsg(e, 'Load pipelines failed')) } finally { pipeline.busy = false }
}
async function validatePipeline() {
  pipeline.busy = true
  try {
    const d = unwrap(await wikiApi.validatePipeline(kbId.value, pipeline.config))
    pipeline.issues = d.issues || []
    if (d.valid) mcToast.success(t('wiki.adv.validOk'))
  } catch (e: any) { mcToast.error(errMsg(e, 'Validate failed')) } finally { pipeline.busy = false }
}
async function savePipeline() {
  pipeline.busy = true
  try {
    await wikiApi.savePipeline(kbId.value, pipeline.config)
    mcToast.success(t('common.saved'))
    pipeline.issues = []
    await loadPipelines()
  } catch (e: any) { mcToast.error(errMsg(e, 'Save failed')) } finally { pipeline.busy = false }
}
async function deletePipeline(d: any) {
  if (!(await mcConfirm({ title: t('common.delete'), message: t('wiki.adv.pipeline.deleteConfirm', { name: d.name }), tone: 'danger' }))) return
  try {
    await wikiApi.deletePipeline(kbId.value, String(d.id))
    if (pipeline.runsFor && String(pipeline.runsFor.id) === String(d.id)) pipeline.runsFor = null
    await loadPipelines()
  } catch (e: any) { mcToast.error(errMsg(e, 'Delete failed')) }
}
async function viewRuns(d: any) {
  pipeline.runsFor = d
  try {
    pipeline.runs = unwrap(await wikiApi.listPipelineRuns(kbId.value, String(d.id))) || []
  } catch (e: any) { mcToast.error(errMsg(e, 'Load runs failed')) }
}
function runClass(s?: string) {
  if (s === 'succeeded' || s === 'success') return 'badge-ok'
  if (s === 'failed' || s === 'error') return 'badge-warn'
  return 'badge-muted'
}

onMounted(() => { loaded.profile = true; loadProfile() })
</script>

<style scoped>
.adv-panel { display: flex; flex-direction: column; gap: 14px; height: 100%; }
.adv-subtabs { display: inline-flex; gap: 4px; padding: 4px; background: var(--mc-bg-muted); border-radius: 12px; border: 1px solid var(--mc-border-light); align-self: flex-start; flex-wrap: wrap; }
.adv-subtab { padding: 6px 13px; border: none; background: none; cursor: pointer; font-size: 13px; color: var(--mc-text-secondary); border-radius: 9px; font-weight: 500; transition: all 0.15s; }
.adv-subtab:hover { color: var(--mc-text-primary); }
.adv-subtab.active { color: var(--mc-primary); background: var(--mc-bg-elevated); font-weight: 600; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }

.adv-section { display: flex; flex-direction: column; gap: 12px; overflow-y: auto; padding-right: 2px; }
.adv-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.adv-head h3 { margin: 0; font-size: 15px; font-weight: 700; color: var(--mc-text-primary); }
.adv-desc { margin: 4px 0 0; font-size: 12px; color: var(--mc-text-tertiary); line-height: 1.5; }

.code-editor { width: 100%; min-height: 240px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; line-height: 1.6; padding: 12px; border: 1px solid var(--mc-border); border-radius: 12px; background: var(--mc-bg-muted); color: var(--mc-text-primary); resize: vertical; outline: none; box-sizing: border-box; }
.code-editor:focus { border-color: var(--mc-primary); }

.adv-actions { display: flex; gap: 10px; justify-content: flex-end; }
.btn-primary { padding: 8px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: #fff; border: none; border-radius: 11px; font-size: 13px; font-weight: 600; cursor: pointer; }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-ghost { padding: 8px 14px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 11px; font-size: 13px; cursor: pointer; }
.btn-ghost.danger { color: #c0392b; border-color: rgba(192,57,43,0.3); }
.btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }

.badge { display: inline-block; padding: 2px 9px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.badge-ok { background: rgba(24,74,69,0.12); color: #184a45; }
.badge-warn { background: rgba(192,57,43,0.12); color: #c0392b; }
.badge-muted { background: var(--mc-bg-muted); color: var(--mc-text-secondary); }

.issue-box { border: 1px solid rgba(192,57,43,0.3); background: rgba(192,57,43,0.06); border-radius: 10px; padding: 10px 12px; font-size: 12px; color: #c0392b; }
.issue-box.ok { border-color: rgba(24,74,69,0.3); background: rgba(24,74,69,0.06); color: #184a45; }
.issue-line { line-height: 1.6; }

.adv-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.adv-table th { text-align: left; padding: 8px 10px; color: var(--mc-text-tertiary); font-weight: 600; font-size: 11px; text-transform: uppercase; letter-spacing: 0.03em; border-bottom: 1px solid var(--mc-border-light); }
.adv-table td { padding: 8px 10px; border-bottom: 1px solid var(--mc-border-light); color: var(--mc-text-primary); }
.adv-table.compact td, .adv-table.compact th { padding: 6px 10px; }
.cell-title { font-weight: 600; }
.cell-actions { display: flex; gap: 12px; }
.row-stale { background: rgba(192,57,43,0.04); }

.layer-tag { padding: 2px 8px; border-radius: 6px; font-size: 11px; font-weight: 600; }
.tag-fact { background: rgba(24,74,69,0.12); color: #184a45; }
.tag-exp { background: rgba(217,109,70,0.14); color: #b8552f; }
.tag-other { background: var(--mc-bg-muted); color: var(--mc-text-secondary); }

.layer-stats { display: flex; gap: 10px; flex-wrap: wrap; }
.stat-chip { padding: 8px 14px; background: var(--mc-bg-muted); border-radius: 10px; font-size: 12px; color: var(--mc-text-secondary); }
.stat-chip b { font-size: 16px; color: var(--mc-text-primary); margin-right: 4px; }
.stat-chip.stale b { color: #c0392b; }

.perm-agent-row { display: flex; align-items: center; gap: 10px; }
.perm-agent-row label, .field-label { font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); }
.perm-add { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding-top: 8px; }
.ck { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--mc-text-secondary); }

.form-input { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 10px; font-size: 13px; background: var(--mc-bg-muted); color: var(--mc-text-primary); outline: none; box-sizing: border-box; }
.form-input:focus { border-color: var(--mc-primary); }
.form-input.compact { padding: 6px 10px; }

.link { background: none; border: none; color: var(--mc-primary); cursor: pointer; font-size: 12px; padding: 0; }
.link-danger { background: none; border: none; color: #c0392b; cursor: pointer; font-size: 12px; padding: 0; }
.empty-hint { color: var(--mc-text-tertiary); font-size: 13px; text-align: center; padding: 18px; }

.kv-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; }
.kv { display: flex; flex-direction: column; gap: 3px; padding: 10px 12px; background: var(--mc-bg-muted); border-radius: 10px; }
.kv span { font-size: 11px; color: var(--mc-text-tertiary); }
.kv b { font-size: 14px; color: var(--mc-text-primary); }
.dir-row { display: flex; gap: 8px; }
.dir-row .form-input { flex: 1; }
.dir-editor { min-height: 100px; font-size: 12.5px; }

.runs-box { border: 1px solid var(--mc-border-light); border-radius: 12px; padding: 12px; background: var(--mc-bg-muted); }
.runs-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
</style>
