<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner templates-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('skillTemplates.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('skillTemplates.title') }}</h1>
            <p class="mc-page-desc">{{ t('skillTemplates.desc') }}</p>
          </div>
          <div class="header-actions">
            <button class="btn-secondary" @click="$router.push('/skills')">
              {{ t('skillTemplates.backToSkills') }}
            </button>
          </div>
        </div>

        <!-- Gallery -->
        <div v-if="!selectedTemplate" class="template-grid">
          <div
            v-for="tpl in templates"
            :key="tpl.id"
            class="template-card mc-surface-card"
            @click="onPickTemplate(tpl)"
          >
            <div class="template-card-head">
              <span class="template-icon">{{ tpl.icon || '🧩' }}</span>
              <div class="template-meta">
                <h3 class="template-name">{{ tpl.nameZh || tpl.name }}</h3>
                <p class="template-name-en">{{ tpl.nameEn || tpl.name }}</p>
              </div>
              <span class="template-type-badge" :class="`type-${tpl.type}`">{{ tpl.type }}</span>
            </div>
            <p class="template-desc">{{ tpl.descriptionZh || tpl.description }}</p>
            <div class="template-footer">
              <span class="template-fields-count">{{ tpl.fields?.length || 0 }} {{ t('skillTemplates.fields') }}</span>
              <button class="btn-primary btn-sm">{{ t('skillTemplates.useTemplate') }} →</button>
            </div>
          </div>
          <p v-if="templates.length === 0" class="empty-state">
            {{ t('skillTemplates.empty') }}
          </p>
        </div>

        <!-- Wizard -->
        <div v-else class="wizard mc-surface-card">
          <div class="wizard-head">
            <button class="btn-link" @click="selectedTemplate = null">← {{ t('skillTemplates.backToGallery') }}</button>
            <h2>{{ selectedTemplate.nameZh || selectedTemplate.name }}</h2>
            <p>{{ selectedTemplate.descriptionZh || selectedTemplate.description }}</p>
          </div>

          <div class="wizard-steps">
            <div class="wizard-step" :class="{ active: step === 1, done: step > 1 }">
              <span class="step-num">1</span>
              <span>{{ t('skillTemplates.step1') }}</span>
            </div>
            <div class="wizard-step" :class="{ active: step === 2, done: step > 2 }">
              <span class="step-num">2</span>
              <span>{{ t('skillTemplates.step2') }}</span>
            </div>
            <div class="wizard-step" :class="{ active: step === 3 }">
              <span class="step-num">3</span>
              <span>{{ t('skillTemplates.step3') }}</span>
            </div>
          </div>

          <!-- Step 1: form -->
          <div v-if="step === 1" class="wizard-step-body">
            <div v-for="field in selectedTemplate.fields" :key="field.key" class="form-group">
              <label class="form-label">
                {{ field.label || field.key }}
                <span v-if="field.required" class="required-mark">*</span>
              </label>
              <input
                v-if="field.type === 'text'"
                v-model="form[field.key]"
                class="form-input"
                :placeholder="field.placeholder || ''"
              />
              <input
                v-else-if="field.type === 'secret'"
                v-model="form[field.key]"
                type="password"
                autocomplete="new-password"
                class="form-input"
                :placeholder="field.placeholder || ''"
              />
              <textarea
                v-else-if="field.type === 'textarea'"
                v-model="form[field.key]"
                class="form-input form-textarea"
                rows="3"
                :placeholder="field.placeholder || ''"
              />
              <select
                v-else-if="field.type === 'select'"
                v-model="form[field.key]"
                class="form-input"
              >
                <option v-for="opt in field.options || []" :key="opt.value" :value="opt.value">
                  {{ opt.label || opt.value }}
                </option>
              </select>
              <label v-else-if="field.type === 'toggle'" class="toggle-row">
                <input type="checkbox" v-model="form[field.key]" />
                <span>{{ field.placeholder || '' }}</span>
              </label>
              <select
                v-else-if="field.type === 'kb-picker'"
                v-model="form[field.key]"
                class="form-input"
              >
                <option value="">{{ t('skillTemplates.selectKb') }}</option>
                <option v-for="kb in availableKbs" :key="kb.slug" :value="kb.slug">
                  {{ kb.name }} ({{ kb.slug }})
                </option>
              </select>
              <input v-else v-model="form[field.key]" class="form-input" :placeholder="field.placeholder || ''" />
              <p v-if="field.hint" class="form-hint">{{ field.hint }}</p>
            </div>
            <div class="wizard-actions">
              <button class="btn-secondary" @click="selectedTemplate = null">{{ t('common.cancel') }}</button>
              <button class="btn-primary" :disabled="!step1Valid" @click="step = 2">
                {{ t('skillTemplates.next') }} →
              </button>
            </div>
          </div>

          <!-- Step 2: preview -->
          <div v-if="step === 2" class="wizard-step-body">
            <p class="form-hint">{{ t('skillTemplates.previewHint') }}</p>
            <pre class="preview-pre">{{ renderedSkillMd }}</pre>
            <div class="wizard-actions">
              <button class="btn-secondary" @click="step = 1">← {{ t('skillTemplates.back') }}</button>
              <button class="btn-primary" :disabled="installing" @click="installSkill">
                {{ installing ? t('skillTemplates.installing') : t('skillTemplates.installSkill') }}
              </button>
            </div>
          </div>

          <!-- Step 3: done -->
          <div v-if="step === 3" class="wizard-step-body">
            <div class="success-state">
              <span class="success-icon">✅</span>
              <h3>{{ t('skillTemplates.installed') }}</h3>
              <p>{{ t('skillTemplates.installedDesc', { name: form.skill_name }) }}</p>
              <div class="wizard-actions">
                <button class="btn-secondary" @click="resetWizard">{{ t('skillTemplates.installAnother') }}</button>
                <button class="btn-primary" @click="$router.push('/skills')">{{ t('skillTemplates.viewInSkills') }} →</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { skillTemplateApi, wikiApi } from '@/api/index'

interface TemplateField {
  key: string
  label?: string
  type: string
  required?: boolean
  placeholder?: string
  hint?: string
  default?: any
  options?: Array<{ value: string; label?: string }>
}

interface SkillTemplate {
  id: string
  name: string
  nameZh?: string
  nameEn?: string
  description?: string
  descriptionZh?: string
  type: string
  icon?: string
  category?: string
  fields?: TemplateField[]
  skillMd?: string
  /** Backend metadata (RFC-091 multi-file bridge); unused by the wizard UI. */
  bundlePath?: string
}

const { t } = useI18n()
const templates = ref<SkillTemplate[]>([])
const selectedTemplate = ref<SkillTemplate | null>(null)
const step = ref<1 | 2 | 3>(1)
const form = reactive<Record<string, any>>({})
const installing = ref(false)
const availableKbs = ref<Array<{ slug: string; name: string }>>([])

onMounted(async () => {
  try {
    const res: any = await skillTemplateApi.list()
    templates.value = res?.data || []
  } catch {
    templates.value = []
  }
  // Load KB list lazily for kb-picker fields. wikiApi.listKnowledgeBases is
  // the existing endpoint used elsewhere. Failure is non-fatal.
  try {
    const res: any = await wikiApi.listKBs()
    availableKbs.value = (res?.data || []).map((kb: any) => ({
      // KBs don't have slugs in MateClaw — fall back to id-as-slug for the
      // wizard. The backend can still bind via id; manifest just stores
      // whichever value the user picked here.
      slug: kb.slug || (kb.id != null ? String(kb.id) : ''),
      name: kb.name || kb.title || '',
    })).filter((kb: any) => kb.slug)
  } catch {
    availableKbs.value = []
  }
})

function onPickTemplate(template: SkillTemplate) {
  selectedTemplate.value = template
  step.value = 1
  // Pre-populate defaults so the form starts in a usable state.
  Object.keys(form).forEach(k => delete form[k])
  for (const field of template.fields || []) {
    form[field.key] = field.default !== undefined ? field.default : ''
  }
}

const step1Valid = computed(() => {
  if (!selectedTemplate.value) return false
  for (const field of selectedTemplate.value.fields || []) {
    if (field.required) {
      const v = form[field.key]
      if (v === undefined || v === null || (typeof v === 'string' && v.trim() === '')) return false
    }
  }
  return true
})

/** Mask for preview substitutions of secret fields. The actual value is
 *  still sent to the backend by installSkill(); only the visible preview
 *  hides it so screenshots / "share my screen" don't leak credentials. */
const SECRET_MASK = '••••••••'

const renderedSkillMd = computed(() => {
  if (!selectedTemplate.value || !selectedTemplate.value.skillMd) return ''
  let out = selectedTemplate.value.skillMd
  const secretKeys = new Set(
    (selectedTemplate.value.fields || [])
      .filter(f => f.type === 'secret')
      .map(f => f.key),
  )
  // Mirror the backend's auxiliary placeholders so the preview matches reality.
  const ctx: Record<string, string> = {}
  for (const k of Object.keys(form)) {
    if (secretKeys.has(k)) {
      const v = form[k]
      ctx[k] = v == null || v === '' ? '' : SECRET_MASK
    } else {
      ctx[k] = form[k]?.toString() ?? ''
    }
  }
  if ('citation_required' in form) {
    const req = !!form.citation_required
    ctx.citation_string = req ? 'required' : 'optional'
    ctx.citation_instruction = req
      ? '**每条建议必须标明引用的 KB 出处** ({{citation}} 自动注入)。'
      : '如有 KB 引用，按 {{citation}} 标注；否则可省略。'
  }
  if ('output_language' in form) {
    ctx.output_language_label = form.output_language === 'zh' ? '中文' : 'English'
  }
  out = out.replace(/\{\{([a-zA-Z0-9_]+)\}\}/g, (_match, k) => ctx[k] ?? '')
  return out
})

async function installSkill() {
  if (!selectedTemplate.value) return
  installing.value = true
  try {
    await skillTemplateApi.instantiate(selectedTemplate.value.id, form)
    step.value = 3
  } catch (e: any) {
    mcToast.error(typeof e === 'string' ? e : e?.message || t('skillTemplates.installFailed'))
  } finally {
    installing.value = false
  }
}

function resetWizard() {
  selectedTemplate.value = null
  step.value = 1
  Object.keys(form).forEach(k => delete form[k])
}
</script>

<style scoped>
.templates-page { gap: 18px; }
.template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 18px; }
.template-card { padding: 18px; cursor: pointer; transition: transform 0.15s, box-shadow 0.15s; display: flex; flex-direction: column; gap: 12px; }
.template-card:hover { transform: translateY(-2px); box-shadow: var(--mc-shadow-medium); border-color: var(--mc-primary-light); }
.template-card-head { display: flex; align-items: flex-start; gap: 12px; }
.template-icon { font-size: 28px; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; background: var(--mc-bg-muted); border-radius: 12px; flex-shrink: 0; }
.template-meta { flex: 1; min-width: 0; }
.template-name { font-size: 16px; font-weight: 700; color: var(--mc-text-primary); margin: 0; }
.template-name-en { font-size: 12px; color: var(--mc-text-tertiary); margin: 2px 0 0; }
.template-type-badge { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; text-transform: uppercase; flex-shrink: 0; }
.type-knowledge { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.type-prompt { background: var(--mc-primary-bg); color: var(--mc-primary); }
.template-desc { font-size: 13px; color: var(--mc-text-secondary); line-height: 1.5; margin: 0; flex: 1; }
.template-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding-top: 8px; border-top: 1px solid var(--mc-border-light); }
.template-fields-count { font-size: 12px; color: var(--mc-text-tertiary); }
.empty-state { color: var(--mc-text-tertiary); font-size: 14px; padding: 40px; text-align: center; }

.wizard { padding: 24px; }
.wizard-head { padding-bottom: 12px; border-bottom: 1px solid var(--mc-border-light); margin-bottom: 16px; }
.wizard-head h2 { font-size: 18px; font-weight: 700; margin: 8px 0 4px; }
.wizard-head p { font-size: 13px; color: var(--mc-text-secondary); margin: 0; }
.btn-link { background: none; border: none; color: var(--mc-primary); cursor: pointer; padding: 0; font-size: 13px; }
.wizard-steps { display: flex; gap: 12px; margin-bottom: 20px; }
.wizard-step { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 999px; background: var(--mc-bg-muted); color: var(--mc-text-tertiary); font-size: 13px; font-weight: 500; }
.wizard-step.active { background: var(--mc-primary-bg); color: var(--mc-primary); font-weight: 600; }
.wizard-step.done { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.step-num { width: 22px; height: 22px; border-radius: 50%; background: var(--mc-bg-elevated); color: inherit; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700; }
.wizard-step-body { display: flex; flex-direction: column; gap: 14px; }
.form-group { display: flex; flex-direction: column; gap: 4px; }
.form-label { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }
.required-mark { color: var(--mc-danger); margin-left: 2px; }
.form-input { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-sunken); width: 100%; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1); }
.form-textarea { resize: vertical; font-family: inherit; }
.form-hint { font-size: 11px; color: var(--mc-text-tertiary); line-height: 1.5; margin: 2px 0 0; }
.toggle-row { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 13px; }
.preview-pre { background: var(--mc-bg-sunken); padding: 14px; border-radius: 10px; font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; font-size: 12px; line-height: 1.5; color: var(--mc-text-primary); max-height: 480px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
.wizard-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; padding-top: 14px; border-top: 1px solid var(--mc-border-light); }
.btn-primary { padding: 8px 16px; background: var(--mc-primary); color: white; border: none; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-primary.btn-sm { padding: 6px 12px; font-size: 12px; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.success-state { text-align: center; padding: 32px 20px; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.success-icon { font-size: 48px; }
.success-state h3 { font-size: 20px; font-weight: 700; margin: 0; }
.success-state p { color: var(--mc-text-secondary); margin: 0; }
.success-state .wizard-actions { width: 100%; justify-content: center; border-top: none; padding-top: 0; }

.header-actions { display: flex; gap: 8px; }
</style>
