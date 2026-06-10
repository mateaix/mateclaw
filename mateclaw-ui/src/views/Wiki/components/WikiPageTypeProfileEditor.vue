<template>
  <div class="pt-editor">
    <header class="adv-head">
      <div>
        <h3>{{ t('wiki.adv.profile.title') }}</h3>
        <p class="adv-desc">{{ t('wiki.adv.profile.desc') }}</p>
      </div>
      <div class="head-right">
        <span v-if="meta.builtinDefault" class="badge badge-muted">{{ t('wiki.adv.profile.builtin') }}</span>
        <span v-else class="badge badge-ok">v{{ meta.version }}</span>
        <div class="mode-toggle" role="tablist">
          <button
            type="button" class="mode-btn" :class="{ active: mode === 'form' }"
            role="tab" :aria-selected="mode === 'form'"
            @click="switchMode('form')"
          >{{ t('wiki.adv.profile.formMode') }}</button>
          <button
            type="button" class="mode-btn" :class="{ active: mode === 'json' }"
            role="tab" :aria-selected="mode === 'json'"
            @click="switchMode('json')"
          >{{ t('wiki.adv.profile.jsonMode') }}</button>
        </div>
      </div>
    </header>

    <!-- ===================== FORM MODE ===================== -->
    <div v-if="mode === 'form'" class="form-mode">
      <!-- Profile-level settings -->
      <div class="profile-settings">
        <label class="ps-field">
          <span>{{ t('wiki.adv.profile.fallbackType') }}</span>
          <select v-model="form.fallbackType" class="form-input compact">
            <option v-for="ty in form.types" :key="ty.key" :value="ty.key">{{ ty.label || ty.key }} ({{ ty.key }})</option>
          </select>
        </label>
        <label class="ps-check">
          <input type="checkbox" v-model="form.allowAdditionalFields" />
          <span>{{ t('wiki.adv.profile.allowAdditionalFields') }}</span>
        </label>
        <span class="ps-version">{{ t('wiki.adv.profile.versionLabel') }}: v{{ form.version }}</span>
      </div>

      <div v-if="clientIssues.length" class="issue-box warn">
        <div v-for="(iss, i) in clientIssues" :key="i" class="issue-line">⚠ {{ iss }}</div>
      </div>

      <div class="two-col">
        <!-- Type list -->
        <aside class="type-list">
          <button
            v-for="(ty, i) in form.types" :key="ty.key + ':' + i"
            class="type-item" :class="{ active: ty.key === selectedKey }"
            @click="selectedKey = ty.key"
          >
            <span class="ti-main">
              <span class="ti-label">{{ ty.label || ty.key }}</span>
              <span class="ti-key">{{ ty.key }}</span>
            </span>
            <span class="ti-ops">
              <span class="ti-op" :title="t('wiki.adv.profile.moveUp')" @click.stop="moveType(i, -1)">↑</span>
              <span class="ti-op" :title="t('wiki.adv.profile.moveDown')" @click.stop="moveType(i, 1)">↓</span>
              <span class="ti-op danger" :title="t('wiki.adv.profile.removeType')" @click.stop="removeType(i)">✕</span>
            </span>
          </button>
          <button v-if="form.types.length === 0" class="type-empty" disabled>{{ t('wiki.adv.profile.noTypes') }}</button>
          <button class="add-type-btn" @click="openAddType">+ {{ t('wiki.adv.profile.addType') }}</button>
        </aside>

        <!-- Selected type editor -->
        <section v-if="selectedType" class="type-form">
          <div class="tf-grid">
            <label class="tf-field">
              <span>{{ t('wiki.adv.profile.label') }}</span>
              <input v-model="selectedType.label" class="form-input compact" />
            </label>
            <label class="tf-field">
              <span>{{ t('wiki.adv.profile.typeKey') }}</span>
              <input :value="selectedType.key" class="form-input compact" disabled />
            </label>
            <label class="tf-field">
              <span>{{ t('wiki.adv.profile.layer') }}</span>
              <select v-model="selectedType.layer" class="form-input compact">
                <option value="">{{ t('wiki.adv.profile.layerUnset') }}</option>
                <option value="fact">{{ t('wiki.adv.profile.layerFact') }}</option>
                <option value="experience">{{ t('wiki.adv.profile.layerExperience') }}</option>
              </select>
            </label>
          </div>
          <label class="tf-field full">
            <span>{{ t('wiki.adv.profile.description') }}</span>
            <textarea v-model="selectedType.description" class="form-input" rows="2"></textarea>
          </label>

          <!-- Field schema -->
          <div class="fields-head">
            <span class="field-label">{{ t('wiki.adv.profile.fields') }}</span>
            <button class="btn-ghost xs" @click="addField(selectedType)">+ {{ t('wiki.adv.profile.addField') }}</button>
          </div>
          <table v-if="selectedType.fields.length" class="adv-table fields-table">
            <thead><tr>
              <th>{{ t('wiki.adv.profile.fieldName') }}</th>
              <th>{{ t('wiki.adv.profile.fieldType') }}</th>
              <th>{{ t('wiki.adv.profile.fieldRequired') }}</th>
              <th>{{ t('wiki.adv.profile.enumValues') }}</th>
              <th></th>
            </tr></thead>
            <tbody>
              <tr v-for="(f, fi) in selectedType.fields" :key="fi">
                <td><input v-model.trim="f.name" class="form-input compact" /></td>
                <td>
                  <select v-model="f.type" class="form-input compact">
                    <option v-for="ft in FIELD_TYPES" :key="ft" :value="ft">{{ ft }}</option>
                  </select>
                </td>
                <td class="td-center"><input type="checkbox" v-model="f.required" /></td>
                <td>
                  <input
                    v-if="f.type === 'enum'" v-model="f.valuesText"
                    class="form-input compact" :placeholder="t('wiki.adv.profile.enumHint')"
                  />
                  <span v-else class="muted-dash">—</span>
                </td>
                <td class="td-ops">
                  <span class="ti-op" :title="t('wiki.adv.profile.moveUp')" @click="moveField(selectedType, fi, -1)">↑</span>
                  <span class="ti-op" :title="t('wiki.adv.profile.moveDown')" @click="moveField(selectedType, fi, 1)">↓</span>
                  <span class="ti-op danger" @click="selectedType.fields.splice(fi, 1)">✕</span>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty-hint sm">{{ t('wiki.adv.profile.noFields') }}</p>

          <!-- Advanced (stage prompts + template) -->
          <details class="advanced">
            <summary>{{ t('wiki.adv.profile.advanced') }}</summary>
            <div class="adv-body">
              <p class="adv-intro">{{ t('wiki.adv.profile.advancedIntro') }}</p>
              <label class="tf-field full">
                <span>{{ t('wiki.adv.profile.stageRoute') }}</span>
                <small class="field-hint">{{ t('wiki.adv.profile.routeHint') }}</small>
                <textarea
                  v-model="selectedType.route.instructions" class="form-input" rows="2"
                  :placeholder="t('wiki.adv.profile.routeExample')"
                ></textarea>
              </label>
              <label class="tf-field full">
                <span>{{ t('wiki.adv.profile.stageCreate') }}</span>
                <small class="field-hint">{{ t('wiki.adv.profile.createHint') }}</small>
                <textarea
                  v-model="selectedType.create.instructions" class="form-input" rows="2"
                  :placeholder="t('wiki.adv.profile.createExample')"
                ></textarea>
              </label>
              <label class="tf-field">
                <span>{{ t('wiki.adv.profile.stageCreateTemplate') }}</span>
                <small class="field-hint">{{ t('wiki.adv.profile.createTemplateHint') }}</small>
                <input
                  v-model.trim="selectedType.create.template" class="form-input compact"
                  :placeholder="t('wiki.adv.profile.createTemplateExample')"
                />
              </label>
              <label class="tf-field full">
                <span>{{ t('wiki.adv.profile.stageMerge') }}</span>
                <small class="field-hint">{{ t('wiki.adv.profile.mergeHint') }}</small>
                <textarea
                  v-model="selectedType.merge.instructions" class="form-input" rows="2"
                  :placeholder="t('wiki.adv.profile.mergeExample')"
                ></textarea>
              </label>
              <label class="tf-field">
                <span>{{ t('wiki.adv.profile.templateKey') }}</span>
                <small class="field-hint">{{ t('wiki.adv.profile.templateKeyHint') }}</small>
                <input
                  v-model.trim="selectedType.templateKey" class="form-input compact"
                  :placeholder="t('wiki.adv.profile.createTemplateExample')"
                />
              </label>
              <label class="tf-field full">
                <span>{{ t('wiki.adv.profile.templateMarkdown') }}</span>
                <small class="field-hint">{{ t('wiki.adv.profile.templateMarkdownHint') }}</small>
                <textarea
                  v-model="selectedType.templateMarkdown" class="form-input mono" rows="5"
                  :placeholder="t('wiki.adv.profile.templateMarkdownExample')"
                ></textarea>
              </label>
            </div>
          </details>
        </section>
        <section v-else class="type-form empty">
          <p class="empty-hint">{{ t('wiki.adv.profile.selectType') }}</p>
        </section>
      </div>
    </div>

    <!-- ===================== JSON MODE ===================== -->
    <div v-else class="json-mode">
      <textarea
        v-model="rawConfig" class="code-editor" spellcheck="false"
        :placeholder="t('wiki.adv.profile.placeholder')"
      ></textarea>
    </div>

    <!-- Server validation issues -->
    <div v-if="issues.length" class="issue-box">
      <div v-for="(iss, i) in issues" :key="i" class="issue-line">⚠ {{ iss }}</div>
    </div>

    <!-- Actions -->
    <div class="adv-actions">
      <button class="btn-ghost" @click="validate" :disabled="busy">{{ t('wiki.adv.validate') }}</button>
      <button class="btn-ghost danger" @click="reset" :disabled="busy">{{ t('wiki.adv.profile.reset') }}</button>
      <button class="btn-primary" @click="save" :disabled="busy">{{ t('common.save') }}</button>
    </div>

    <div class="reclassify-box">
      <p class="adv-desc">{{ t('wiki.adv.reclassify.desc') }}</p>
      <button class="btn-ghost" @click="reclassify" :disabled="busy">{{ t('wiki.adv.reclassify.button') }}</button>
    </div>

    <!-- Add-type mini dialog -->
    <div v-if="addDlg.open" class="modal-overlay" @click.self="addDlg.open = false">
      <div class="modal-content">
        <h3 class="modal-title">{{ t('wiki.adv.profile.addType') }}</h3>
        <div class="form-group">
          <label>{{ t('wiki.adv.profile.typeKey') }}</label>
          <input v-model.trim="addDlg.key" class="form-input" :placeholder="t('wiki.adv.profile.typeKeyHint')" autofocus />
        </div>
        <div class="form-group">
          <label>{{ t('wiki.adv.profile.label') }}</label>
          <input v-model.trim="addDlg.label" class="form-input" />
        </div>
        <div class="form-group">
          <label>{{ t('wiki.adv.profile.layer') }}</label>
          <select v-model="addDlg.layer" class="form-input">
            <option value="">{{ t('wiki.adv.profile.layerUnset') }}</option>
            <option value="fact">{{ t('wiki.adv.profile.layerFact') }}</option>
            <option value="experience">{{ t('wiki.adv.profile.layerExperience') }}</option>
          </select>
        </div>
        <div class="form-group">
          <label>{{ t('wiki.adv.profile.copyFrom') }}</label>
          <select v-model="addDlg.copyFrom" class="form-input">
            <option value="">{{ t('wiki.adv.profile.copyFromNone') }}</option>
            <option v-for="ty in form.types" :key="ty.key" :value="ty.key">{{ ty.label || ty.key }} ({{ ty.key }})</option>
          </select>
        </div>
        <p v-if="addDlg.error" class="dlg-error">{{ addDlg.error }}</p>
        <div class="modal-actions">
          <button class="btn-ghost" @click="addDlg.open = false">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="confirmAddType" :disabled="!addDlg.key">{{ t('common.create') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore } from '@/stores/useWikiStore'
import { wikiApi } from '@/api/index'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'

const FIELD_TYPES = ['string', 'number', 'boolean', 'date', 'enum', 'string_array'] as const
type FieldType = typeof FIELD_TYPES[number]

interface FieldForm { name: string; type: FieldType; required: boolean; valuesText: string }
interface StageForm { instructions: string; template: string }
interface TypeForm {
  key: string
  label: string
  description: string
  layer: '' | 'fact' | 'experience'
  fields: FieldForm[]
  route: StageForm
  create: StageForm
  merge: StageForm
  templateKey: string
  templateMarkdown: string
  _raw: any
}

const { t } = useI18n()
const store = useWikiStore()
const kbId = computed(() => (store.currentKB ? String(store.currentKB.id) : ''))

const mode = ref<'form' | 'json'>('form')
const rawConfig = ref('')
const meta = reactive({ version: 0, builtinDefault: true })
const issues = ref<string[]>([])
const busy = ref(false)

const form = reactive<{ version: number; fallbackType: string; allowAdditionalFields: boolean; types: TypeForm[]; _raw: any }>({
  version: 1,
  fallbackType: 'concept',
  allowAdditionalFields: false,
  types: [],
  _raw: {},
})
const selectedKey = ref('')
const selectedType = computed(() => form.types.find((ty) => ty.key === selectedKey.value) || null)

function clone<T>(v: T): T { return v == null ? v : JSON.parse(JSON.stringify(v)) }
function unwrap(res: any) { return res?.data ?? res }
function errMsg(e: any, fallback: string) { return e?.response?.data?.message || fallback }

// ---------- parse JSON -> form ----------
function parseToForm(raw: string) {
  const obj = JSON.parse(raw || '{}')
  form._raw = obj
  form.version = typeof obj.version === 'number' ? obj.version : 1
  form.fallbackType = typeof obj.fallbackType === 'string' ? obj.fallbackType : 'concept'
  form.allowAdditionalFields = !!obj.allowAdditionalFields
  const pts = obj.pageTypes && typeof obj.pageTypes === 'object' ? obj.pageTypes : {}
  form.types = Object.keys(pts).map((key) => defToForm(key, pts[key]))
  if (!selectedType.value) selectedKey.value = form.types[0]?.key ?? ''
}

function defToForm(key: string, def: any): TypeForm {
  def = def || {}
  const schema = def.schema && typeof def.schema === 'object' ? def.schema : {}
  return {
    key,
    label: def.label ?? '',
    description: def.description ?? '',
    layer: def.layer === 'fact' || def.layer === 'experience' ? def.layer : '',
    fields: Object.keys(schema).map((name) => {
      const fs = schema[name] || {}
      return {
        name,
        type: (FIELD_TYPES as readonly string[]).includes(fs.type) ? fs.type : 'string',
        required: !!fs.required,
        valuesText: Array.isArray(fs.values) ? fs.values.join(', ') : '',
      } as FieldForm
    }),
    route: { instructions: def.route?.instructions ?? '', template: def.route?.template ?? '' },
    create: { instructions: def.create?.instructions ?? '', template: def.create?.template ?? '' },
    merge: { instructions: def.merge?.instructions ?? '', template: def.merge?.template ?? '' },
    templateKey: def.template?.key ?? '',
    templateMarkdown: def.template?.markdown ?? '',
    _raw: def,
  }
}

// ---------- form -> JSON ----------
function parseEnumValues(text: string): string[] {
  return text.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
}
function setStage(def: any, name: 'route' | 'create' | 'merge', stage: StageForm) {
  const instr = stage.instructions.trim()
  const tpl = stage.template.trim()
  if (!instr && !tpl) { delete def[name]; return }
  const out: any = clone(def[name]) || {}
  if (instr) out.instructions = stage.instructions; else delete out.instructions
  if (tpl) out.template = tpl; else delete out.template
  def[name] = out
}
function serializeFromForm(): string {
  const root: any = clone(form._raw) || {}
  root.version = form.version
  root.fallbackType = form.fallbackType
  root.allowAdditionalFields = form.allowAdditionalFields
  const pageTypes: any = {}
  for (const tf of form.types) {
    const def: any = clone(tf._raw) || {}
    def.label = tf.label
    if (tf.description.trim()) def.description = tf.description; else delete def.description
    if (tf.layer) def.layer = tf.layer; else delete def.layer
    const schema: any = {}
    for (const f of tf.fields) {
      const name = f.name.trim()
      if (!name) continue
      const fs: any = clone(tf._raw?.schema?.[name]) || {}
      fs.type = f.type
      fs.required = f.required
      if (f.type === 'enum') fs.values = parseEnumValues(f.valuesText); else delete fs.values
      schema[name] = fs
    }
    def.schema = schema
    setStage(def, 'route', tf.route)
    setStage(def, 'create', tf.create)
    setStage(def, 'merge', tf.merge)
    const tk = tf.templateKey.trim()
    const tm = tf.templateMarkdown
    if (tk || tm.trim()) {
      const tpl: any = clone(def.template) || {}
      if (tk) tpl.key = tk; else delete tpl.key
      if (tm.trim()) tpl.markdown = tm; else delete tpl.markdown
      def.template = tpl
    } else { delete def.template }
    pageTypes[tf.key] = def
  }
  root.pageTypes = pageTypes
  return JSON.stringify(root, null, 2)
}

// ---------- client-side light validation ----------
const KEY_RE = /^[a-z0-9_-]+$/
const clientIssues = computed(() => {
  const out: string[] = []
  const seen = new Set<string>()
  for (const ty of form.types) {
    if (!ty.key || !KEY_RE.test(ty.key)) out.push(t('wiki.adv.profile.errKeyFormat', { key: ty.key || '∅' }))
    if (seen.has(ty.key)) out.push(t('wiki.adv.profile.errKeyDup', { key: ty.key }))
    seen.add(ty.key)
    const fseen = new Set<string>()
    for (const f of ty.fields) {
      const n = f.name.trim()
      if (n && fseen.has(n)) out.push(t('wiki.adv.profile.errFieldDup', { type: ty.key, field: n }))
      if (n) fseen.add(n)
    }
  }
  if (form.fallbackType && !form.types.some((ty) => ty.key === form.fallbackType)) {
    out.push(t('wiki.adv.profile.warnFallbackMissing', { type: form.fallbackType }))
  }
  return out
})

// ---------- mode switching ----------
function switchMode(target: 'form' | 'json') {
  if (target === mode.value) return
  if (target === 'json') {
    rawConfig.value = serializeFromForm()
    mode.value = 'json'
  } else {
    try {
      parseToForm(rawConfig.value)
      mode.value = 'form'
    } catch (e: any) {
      mcToast.error(t('wiki.adv.profile.errJsonParse'))
    }
  }
}

// ---------- type list ops ----------
function moveType(i: number, dir: -1 | 1) {
  const j = i + dir
  if (j < 0 || j >= form.types.length) return
  const [it] = form.types.splice(i, 1)
  form.types.splice(j, 0, it)
}
async function removeType(i: number) {
  const ty = form.types[i]
  if (!(await mcConfirm({ title: t('wiki.adv.profile.removeType'), message: t('wiki.adv.profile.removeTypeConfirm', { type: ty.label || ty.key }), tone: 'danger' }))) return
  form.types.splice(i, 1)
  if (selectedKey.value === ty.key) selectedKey.value = form.types[0]?.key ?? ''
}
function addField(ty: TypeForm) {
  ty.fields.push({ name: '', type: 'string', required: false, valuesText: '' })
}
function moveField(ty: TypeForm, i: number, dir: -1 | 1) {
  const j = i + dir
  if (j < 0 || j >= ty.fields.length) return
  const [it] = ty.fields.splice(i, 1)
  ty.fields.splice(j, 0, it)
}

// ---------- add-type dialog ----------
const addDlg = reactive({ open: false, key: '', label: '', layer: '' as '' | 'fact' | 'experience', copyFrom: '', error: '' })
function openAddType() {
  addDlg.open = true
  addDlg.key = ''
  addDlg.label = ''
  addDlg.layer = ''
  addDlg.copyFrom = ''
  addDlg.error = ''
}
function confirmAddType() {
  const key = addDlg.key.trim().toLowerCase()
  if (!KEY_RE.test(key)) { addDlg.error = t('wiki.adv.profile.errKeyFormat', { key: key || '∅' }); return }
  if (form.types.some((ty) => ty.key === key)) { addDlg.error = t('wiki.adv.profile.errKeyDup', { key }); return }
  let ty: TypeForm
  if (addDlg.copyFrom) {
    const src = form.types.find((x) => x.key === addDlg.copyFrom)
    ty = src ? { ...clone(src), key, label: addDlg.label || key, _raw: clone(src._raw) } : blankType(key)
    ty.label = addDlg.label || ty.label || key
    if (addDlg.layer) ty.layer = addDlg.layer
  } else {
    ty = blankType(key)
    ty.label = addDlg.label || key
    ty.layer = addDlg.layer
  }
  form.types.push(ty)
  selectedKey.value = key
  if (!form.fallbackType) form.fallbackType = key
  addDlg.open = false
}
function blankType(key: string): TypeForm {
  return {
    key, label: '', description: '', layer: '',
    fields: [],
    route: { instructions: '', template: '' },
    create: { instructions: '', template: '' },
    merge: { instructions: '', template: '' },
    templateKey: '', templateMarkdown: '', _raw: {},
  }
}

// ---------- load / validate / save / reset / reclassify ----------
async function load() {
  if (!kbId.value) return
  try {
    const d = unwrap(await wikiApi.getPageTypeProfile(kbId.value))
    rawConfig.value = typeof d.config === 'string' ? d.config : JSON.stringify(d.config, null, 2)
    meta.version = d.version
    meta.builtinDefault = d.builtinDefault
    parseToForm(rawConfig.value)
  } catch (e: any) { mcToast.error(errMsg(e, 'Load profile failed')) }
}
function syncRaw() { if (mode.value === 'form') rawConfig.value = serializeFromForm() }
async function validate() {
  syncRaw()
  busy.value = true
  try {
    const d = unwrap(await wikiApi.validatePageTypeProfile(kbId.value, rawConfig.value))
    issues.value = d.issues || []
    if (d.valid) mcToast.success(t('wiki.adv.validOk'))
  } catch (e: any) { mcToast.error(errMsg(e, 'Validate failed')) } finally { busy.value = false }
}
async function save() {
  syncRaw()
  busy.value = true
  try {
    await wikiApi.savePageTypeProfile(kbId.value, rawConfig.value)
    mcToast.success(t('common.saved'))
    issues.value = []
    await load()
  } catch (e: any) { mcToast.error(errMsg(e, 'Save failed')) } finally { busy.value = false }
}
async function reset() {
  if (!(await mcConfirm({ title: t('wiki.adv.profile.reset'), message: t('wiki.adv.profile.resetConfirm'), tone: 'danger' }))) return
  busy.value = true
  try {
    await wikiApi.resetPageTypeProfile(kbId.value)
    mcToast.success(t('common.saved'))
    await load()
  } catch (e: any) { mcToast.error(errMsg(e, 'Reset failed')) } finally { busy.value = false }
}
async function reclassify() {
  if (!kbId.value) return
  if (!(await mcConfirm({ title: t('wiki.adv.reclassify.confirmTitle'), message: t('wiki.adv.reclassify.confirmMsg'), tone: 'danger' }))) return
  busy.value = true
  try {
    await wikiApi.reclassifyKB(kbId.value)
    mcToast.success(t('wiki.adv.reclassify.started'))
  } catch (e: any) { mcToast.error(errMsg(e, 'Reclassify failed')) } finally { busy.value = false }
}

onMounted(load)
</script>

<style scoped>
.pt-editor { display: flex; flex-direction: column; gap: 12px; }
.adv-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.adv-head h3 { margin: 0; font-size: 15px; font-weight: 700; color: var(--mc-text-primary); }
.adv-desc { margin: 4px 0 0; font-size: 12px; color: var(--mc-text-tertiary); line-height: 1.5; }
.head-right { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }

.mode-toggle { display: inline-flex; gap: 4px; padding: 3px; background: var(--mc-bg-muted); border: 1px solid var(--mc-border-light); border-radius: 10px; }
.mode-btn { padding: 5px 12px; border: none; background: none; cursor: pointer; font-size: 12.5px; font-weight: 500; color: var(--mc-text-secondary); border-radius: 7px; transition: all 0.15s; }
.mode-btn:hover { color: var(--mc-text-primary); }
.mode-btn.active { color: var(--mc-primary); background: var(--mc-bg-elevated); font-weight: 600; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }

.profile-settings { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; padding: 10px 12px; background: var(--mc-bg-muted); border-radius: 10px; }
.ps-field { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); }
.ps-check { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--mc-text-secondary); cursor: pointer; }
.ps-version { font-size: 12px; color: var(--mc-text-tertiary); margin-left: auto; }

.two-col { display: grid; grid-template-columns: 230px 1fr; gap: 14px; align-items: start; }
.type-list { display: flex; flex-direction: column; gap: 4px; }
.type-item { display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 8px 10px; border: 1px solid var(--mc-border-light); border-radius: 10px; background: var(--mc-bg-elevated); cursor: pointer; text-align: left; transition: all 0.15s; }
.type-item:hover { border-color: var(--mc-border); }
.type-item.active { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.ti-main { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.ti-label { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ti-key { font-size: 11px; color: var(--mc-text-tertiary); font-family: ui-monospace, monospace; }
.ti-ops { display: inline-flex; gap: 4px; opacity: 0; transition: opacity 0.15s; }
.type-item:hover .ti-ops, .type-item.active .ti-ops { opacity: 1; }
.ti-op { width: 18px; height: 18px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; color: var(--mc-text-tertiary); border-radius: 5px; cursor: pointer; }
.ti-op:hover { background: var(--mc-bg-muted); color: var(--mc-text-primary); }
.ti-op.danger:hover { color: var(--mc-danger); }
.type-empty { padding: 10px; font-size: 12px; color: var(--mc-text-tertiary); border: 1px dashed var(--mc-border-light); border-radius: 10px; background: none; }
.add-type-btn { margin-top: 4px; padding: 8px 10px; border: 1px dashed var(--mc-border); border-radius: 10px; background: none; color: var(--mc-primary); font-size: 12.5px; font-weight: 600; cursor: pointer; }
.add-type-btn:hover { background: var(--mc-primary-bg); }

.type-form { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.type-form.empty { align-items: center; justify-content: center; min-height: 200px; }
.tf-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; }
.tf-field { display: flex; flex-direction: column; gap: 5px; font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); }
.tf-field.full { width: 100%; }

.fields-head { display: flex; align-items: center; justify-content: space-between; }
.field-label { font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); }
.fields-table { width: 100%; }
.fields-table input.form-input, .fields-table select.form-input { width: 100%; }
.td-center { text-align: center; }
.td-ops { white-space: nowrap; }
.muted-dash { color: var(--mc-text-tertiary); }

.advanced { border: 1px solid var(--mc-border-light); border-radius: 10px; padding: 4px 12px; }
.advanced summary { cursor: pointer; font-size: 12.5px; font-weight: 600; color: var(--mc-text-secondary); padding: 6px 0; }
.adv-body { display: flex; flex-direction: column; gap: 12px; padding: 6px 0 10px; }
.adv-intro { margin: 0 0 2px; font-size: 12px; color: var(--mc-text-tertiary); line-height: 1.6; }
.field-hint { font-weight: 400; font-size: 11.5px; color: var(--mc-text-tertiary); line-height: 1.5; }

.code-editor { width: 100%; min-height: 320px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; line-height: 1.6; padding: 12px; border: 1px solid var(--mc-border); border-radius: 12px; background: var(--mc-bg-muted); color: var(--mc-text-primary); resize: vertical; outline: none; box-sizing: border-box; }
.code-editor:focus { border-color: var(--mc-primary); }

.adv-actions { display: flex; gap: 10px; justify-content: flex-end; }
.btn-primary { padding: 8px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: #fff; border: none; border-radius: 11px; font-size: 13px; font-weight: 600; cursor: pointer; }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-ghost { padding: 8px 14px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 11px; font-size: 13px; cursor: pointer; }
.btn-ghost.xs { padding: 4px 10px; font-size: 12px; }
.btn-ghost.danger { color: #c0392b; border-color: rgba(192,57,43,0.3); }
.btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }

.badge { display: inline-block; padding: 2px 9px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.badge-ok { background: rgba(24,74,69,0.12); color: #184a45; }
.badge-muted { background: var(--mc-bg-muted); color: var(--mc-text-secondary); }

.issue-box { border: 1px solid rgba(192,57,43,0.3); background: rgba(192,57,43,0.06); border-radius: 10px; padding: 10px 12px; font-size: 12px; color: #c0392b; }
.issue-box.warn { border-color: rgba(217,119,6,0.35); background: rgba(217,119,6,0.07); color: #b45309; }
.issue-line { line-height: 1.6; }

.adv-table { border-collapse: collapse; font-size: 13px; }
.adv-table th { text-align: left; padding: 6px 8px; color: var(--mc-text-tertiary); font-weight: 600; font-size: 11px; text-transform: uppercase; letter-spacing: 0.03em; border-bottom: 1px solid var(--mc-border-light); }
.adv-table td { padding: 6px 8px; border-bottom: 1px solid var(--mc-border-light); color: var(--mc-text-primary); vertical-align: middle; }

.form-input { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 10px; font-size: 13px; background: var(--mc-bg-muted); color: var(--mc-text-primary); outline: none; box-sizing: border-box; font-family: inherit; }
.form-input:focus { border-color: var(--mc-primary); }
.form-input.compact { padding: 6px 10px; }
.form-input.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; }
textarea.form-input { resize: vertical; }

.empty-hint { color: var(--mc-text-tertiary); font-size: 13px; text-align: center; padding: 18px; }
.empty-hint.sm { padding: 8px; font-size: 12px; text-align: left; }

.reclassify-box { border-top: 1px solid var(--mc-border-light); padding-top: 12px; display: flex; align-items: center; justify-content: space-between; gap: 12px; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); backdrop-filter: blur(4px); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal-content { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 18px; width: 100%; max-width: 460px; padding: 22px; box-shadow: 0 24px 64px rgba(0,0,0,0.18); }
.modal-title { font-size: 16px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 16px; }
.form-group { margin-bottom: 12px; }
.form-group label { display: block; font-size: 12px; font-weight: 600; margin-bottom: 5px; color: var(--mc-text-secondary); }
.form-group .form-input { width: 100%; }
.dlg-error { color: #c0392b; font-size: 12px; margin: 0 0 10px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 6px; }

@media (max-width: 860px) {
  .two-col { grid-template-columns: 1fr; }
}
</style>
