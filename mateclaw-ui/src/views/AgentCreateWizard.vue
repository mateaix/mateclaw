<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner wizard-page">

        <!-- Step rail. Hidden on the first step so the input box is the sole
             focus; orientation only matters once the user has committed. -->
        <div v-if="step !== 'describe'" class="wiz-rail">
          <div class="wiz-step" :class="{ done: stepIndex > 0 }">
            <span class="wiz-dot">
              <svg v-if="stepIndex > 0" class="wiz-dot-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
              <template v-else>1</template>
            </span>
            <span class="wiz-step-label">{{ t('agents.wizard.steps.describe') }}</span>
          </div>
          <div class="wiz-line" :class="{ on: stepIndex > 0 }"></div>
          <div class="wiz-step" :class="{ done: stepIndex > 1, active: step === 'confirm' }">
            <span class="wiz-dot">
              <svg v-if="stepIndex > 1" class="wiz-dot-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
              <template v-else>2</template>
            </span>
            <span class="wiz-step-label">{{ t('agents.wizard.steps.confirm') }}</span>
          </div>
          <div class="wiz-line" :class="{ on: stepIndex > 1 }"></div>
          <div class="wiz-step" :class="{ active: step === 'success' }">
            <span class="wiz-dot">3</span>
            <span class="wiz-step-label">{{ t('agents.wizard.steps.onboard') }}</span>
          </div>
        </div>

        <!-- Short steps (describe / success) center vertically; the long
             confirm step flows from the top and scrolls. -->
        <div class="wiz-body" :class="{ 'wiz-body--center': step !== 'confirm' }">

        <!-- Step 1: describe -->
        <template v-if="step === 'describe'">
          <div class="wiz-hero">
            <div class="wiz-hero-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"><path d="M12 3l1.8 5.4 5.4 1.8-5.4 1.8L12 17.4l-1.8-5.4L4.8 10.2l5.4-1.8z"/></svg>
            </div>
            <h1 class="wiz-title">{{ t('agents.wizard.title') }}</h1>
            <p class="wiz-subtitle">{{ t('agents.wizard.subtitle') }}</p>
          </div>

          <div class="wiz-input-card">
            <textarea
              v-model="requirement"
              class="wiz-textarea"
              rows="2"
              :placeholder="t('agents.wizard.placeholder')"
              @keydown.enter.exact.prevent="generate"
            ></textarea>
            <div class="wiz-input-foot">
              <span class="wiz-hint">{{ t('agents.wizard.inputHint') }}</span>
              <button class="wiz-btn-primary" :disabled="generating || !requirement.trim()" @click="generate">
                <svg class="wiz-btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><path d="M12 3l1.8 5.4 5.4 1.8-5.4 1.8L12 17.4l-1.8-5.4L4.8 10.2l5.4-1.8z"/></svg>
                {{ generating ? t('agents.wizard.generating') : t('agents.wizard.generate') }}
              </button>
            </div>
          </div>

          <div class="wiz-examples">
            <span class="wiz-try">{{ t('agents.wizard.tryLabel') }}</span>
            <button
              v-for="ex in exampleList"
              :key="ex"
              class="wiz-chip"
              :disabled="generating"
              @click="requirement = ex"
            >{{ ex }}</button>
          </div>
        </template>

        <!-- Step 2: confirm config -->
        <template v-else-if="step === 'confirm' && draft">
          <div class="wiz-notice">
            <svg class="wiz-notice-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><path d="M12 3l1.8 5.4 5.4 1.8-5.4 1.8L12 17.4l-1.8-5.4L4.8 10.2l5.4-1.8z"/></svg>
            {{ t('agents.wizard.aiNotice') }}
          </div>

          <div class="wiz-card">
            <div class="wiz-grid">
              <div class="wiz-field">
                <label>{{ t('agents.wizard.fields.name') }} <span class="req">*</span></label>
                <input v-model="draft.name" class="wiz-control" />
              </div>
              <div class="wiz-field">
                <label>{{ t('agents.wizard.fields.icon') }}</label>
                <button type="button" class="wiz-icon-trigger" @click="iconPickerVisible = true">
                  <SkillIcon :value="draft.icon" :size="22" fallback="🤖" />
                  <span class="wiz-icon-trigger__label">{{ draft.icon || t('common.iconPicker.none') }}</span>
                  <span class="wiz-icon-trigger__action">{{ t('common.iconPicker.pickerOpen') }}</span>
                </button>
              </div>
              <div class="wiz-field">
                <label>{{ t('agents.wizard.fields.type') }}</label>
                <select v-model="draft.agentType" class="wiz-control">
                  <option value="react">{{ t('agents.types.react') }}</option>
                  <option value="plan_execute">{{ t('agents.types.planExecute') }}</option>
                </select>
              </div>
              <div class="wiz-field wiz-full">
                <label>{{ t('agents.wizard.fields.description') }}</label>
                <input v-model="draft.description" class="wiz-control" />
              </div>
              <div class="wiz-field wiz-full">
                <label>{{ t('agents.wizard.fields.persona') }}</label>
                <textarea v-model="draft.systemPrompt" class="wiz-control" rows="4"></textarea>
              </div>
              <div class="wiz-field wiz-full">
                <label>{{ t('agents.wizard.fields.tags') }}</label>
                <input v-model="tagsText" class="wiz-control" />
              </div>
            </div>
          </div>

          <!-- Skills — chosen-first, full catalog on demand. -->
          <WizardCapabilityPicker
            kind="skills"
            v-model="selectedSkillIds"
            :items="skillItems"
            :title="t('agents.wizard.sections.skills')"
            :badge="selectedSkillIds.length ? t('agents.wizard.selected', { count: selectedSkillIds.length }) : ''"
            :empty-text="t('agents.wizard.noAiSkills')"
            :add-label="t('agents.wizard.addSkill')"
            :collapse-label="t('agents.wizard.collapse')"
            :search-placeholder="t('agents.binding.searchSkills')"
            :remove-label="t('agents.wizard.remove')"
          />

          <!-- Tools & MCP -->
          <WizardCapabilityPicker
            kind="tools"
            v-model="selectedToolNames"
            :items="toolItems"
            :title="t('agents.wizard.sections.tools')"
            :badge="selectedToolNames.length ? t('agents.wizard.selected', { count: selectedToolNames.length }) : ''"
            :empty-text="t('agents.wizard.noAiTools')"
            :add-label="t('agents.wizard.addTool')"
            :collapse-label="t('agents.wizard.collapse')"
            :search-placeholder="t('agents.binding.searchTools')"
            :remove-label="t('agents.wizard.remove')"
          />

          <!-- Knowledge base -->
          <div class="wiz-card">
            <div class="wiz-sec-head">
              <svg class="wiz-sec-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
              <span class="wiz-sec-title">{{ t('agents.wizard.sections.kb') }}</span>
            </div>
            <select v-model="selectedKbId" class="wiz-control">
              <option :value="null">{{ t('agents.wizard.kbNone') }}</option>
              <option v-for="kb in bindableKBs" :key="kb.id" :value="String(kb.id)">{{ kb.name }}</option>
            </select>
          </div>

          <div class="wiz-actions">
            <button class="wiz-btn-ghost" :disabled="creating" @click="backToDescribe">{{ t('agents.wizard.back') }}</button>
            <button class="wiz-btn-primary" :disabled="creating || !draft.name.trim()" @click="confirmCreate">
              {{ creating ? t('agents.wizard.creating') : t('agents.wizard.confirmCreate') }}
            </button>
          </div>

          <!-- Shared icon picker, same component the agent edit page uses. -->
          <SkillIconPicker
            v-model:visible="iconPickerVisible"
            :model-value="draft.icon"
            @apply="(v: string) => { if (draft) draft.icon = v }"
          />
        </template>

        <!-- Step 3: success -->
        <template v-else-if="step === 'success' && createdAgent">
          <div class="wiz-hero">
            <div class="wiz-hero-icon success">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
            </div>
            <h1 class="wiz-title">{{ t('agents.wizard.successTitle', { name: createdAgent.name }) }}</h1>
            <p class="wiz-subtitle">{{ t('agents.wizard.successSubtitle') }}</p>
          </div>

          <div class="wiz-card">
            <div class="wiz-success-head">
              <SkillIcon :value="createdAgent.icon" :size="44" fallback="🤖" />
              <div>
                <p class="wiz-success-name">{{ createdAgent.name }}</p>
                <p class="wiz-success-desc">{{ createdAgent.description }}</p>
              </div>
            </div>
            <div class="wiz-stats">
              <div class="wiz-stat"><div class="wiz-stat-num">{{ selectedSkillIds.length }}</div><div class="wiz-stat-label">{{ t('agents.wizard.stat.skills') }}</div></div>
              <div class="wiz-stat"><div class="wiz-stat-num">{{ selectedToolNames.length }}</div><div class="wiz-stat-label">{{ t('agents.wizard.stat.tools') }}</div></div>
              <div class="wiz-stat"><div class="wiz-stat-num">{{ selectedKbId ? 1 : 0 }}</div><div class="wiz-stat-label">{{ t('agents.wizard.stat.kb') }}</div></div>
            </div>
          </div>

          <div class="wiz-actions wiz-actions--success">
            <button class="wiz-btn-primary wiz-grow" @click="goChat">{{ t('agents.wizard.startChat') }}</button>
            <button class="wiz-btn-ghost" @click="buildAnother">{{ t('agents.wizard.buildAnother') }}</button>
            <button class="wiz-btn-ghost" @click="goRoster">{{ t('agents.wizard.roster') }}</button>
          </div>
        </template>

        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { agentApi, agentBindingApi, toolApi, skillApi, wikiApi } from '@/api'
import { mcToast } from '@/composables/useMcToast'
import SkillIcon from '@/components/common/SkillIcon.vue'
import SkillIconPicker from '@/components/common/SkillIconPicker.vue'
import WizardCapabilityPicker from '@/components/agent/WizardCapabilityPicker.vue'

const { t, tm, locale } = useI18n()
const router = useRouter()

type Step = 'describe' | 'confirm' | 'success'
const step = ref<Step>('describe')
const stepIndex = computed(() => (step.value === 'describe' ? 0 : step.value === 'confirm' ? 1 : 2))

const requirement = ref('')
const generating = ref(false)
const creating = ref(false)
const iconPickerVisible = ref(false)

interface Draft {
  name: string
  icon: string
  description: string
  agentType: string
  systemPrompt: string
  role?: string
  goal?: string
  tags: string[]
  recommendedQuestions: string[]
  tools: string[]
  skillIds: string[]
  primaryKbId: string | number | null
}
const draft = ref<Draft | null>(null)
const createdAgent = ref<any>(null)

// Editable capability selections (IDs kept as strings per Snowflake contract).
const tagsText = ref('')
const availableTools = ref<any[]>([])
const selectedToolNames = ref<string[]>([])
const availableSkills = ref<any[]>([])
const selectedSkillIds = ref<string[]>([])
const bindableKBs = ref<any[]>([])
const selectedKbId = ref<string | null>(null)

const exampleList = computed<string[]>(() => {
  const ex = tm('agents.wizard.examples') as unknown
  return Array.isArray(ex) ? (ex as string[]) : []
})

function skillName(s: any): string {
  if (locale.value === 'zh-CN' && s.nameZh) return s.nameZh
  if (locale.value !== 'zh-CN' && s.nameEn) return s.nameEn
  return s.name
}

// Catalogs shaped for WizardCapabilityPicker — keys are strings throughout.
const skillItems = computed(() =>
  availableSkills.value.map((s: any) => ({ key: String(s.id), name: skillName(s), desc: s.description })))
const toolItems = computed(() =>
  availableTools.value.map((t: any) => ({ key: t.name, name: t.name, desc: t.description, mcp: t.source === 'mcp' })))

async function loadCatalogs() {
  const [toolsRes, skillsRes, kbRes] = await Promise.allSettled([
    toolApi.listAvailable(),
    skillApi.page({ enabled: true, size: 200 }),
    wikiApi.listBindableKBs(),
  ])
  if (toolsRes.status === 'fulfilled') {
    const all = ((toolsRes.value as any).data || []) as any[]
    availableTools.value = all.filter((tl) => tl.available && !tl.stale)
  }
  if (skillsRes.status === 'fulfilled') {
    const data = (skillsRes.value as any).data
    availableSkills.value = (data?.records || data || []) as any[]
  }
  if (kbRes.status === 'fulfilled') {
    bindableKBs.value = ((kbRes.value as any).data || []) as any[]
  }
}

async function generate() {
  if (!requirement.value.trim()) {
    mcToast.error(t('agents.wizard.emptyRequirement'))
    return
  }
  generating.value = true
  try {
    const [res] = await Promise.all([
      agentApi.generate(requirement.value.trim()) as any,
      loadCatalogs(),
    ])
    const d = res.data as Draft
    draft.value = {
      name: d.name || '',
      icon: d.icon || '🤖',
      description: d.description || '',
      agentType: d.agentType || 'react',
      systemPrompt: d.systemPrompt || '',
      role: d.role,
      goal: d.goal,
      tags: Array.isArray(d.tags) ? d.tags : [],
      recommendedQuestions: Array.isArray(d.recommendedQuestions) ? d.recommendedQuestions : [],
      tools: Array.isArray(d.tools) ? d.tools : [],
      skillIds: Array.isArray(d.skillIds) ? d.skillIds.map(String) : [],
      primaryKbId: d.primaryKbId != null ? String(d.primaryKbId) : null,
    }
    tagsText.value = draft.value.tags.join(', ')
    selectedToolNames.value = [...draft.value.tools]
    selectedSkillIds.value = [...draft.value.skillIds]
    selectedKbId.value = draft.value.primaryKbId != null ? String(draft.value.primaryKbId) : null
    step.value = 'confirm'
  } catch (e: any) {
    mcToast.error(e?.message || t('agents.wizard.generateFailed'))
  } finally {
    generating.value = false
  }
}

async function confirmCreate() {
  if (!draft.value || !draft.value.name.trim()) return
  creating.value = true
  try {
    const tags = tagsText.value
      .split(/[,，]/)
      .map((s) => s.trim())
      .filter(Boolean)
      .join(',')
    const payload: any = {
      name: draft.value.name.trim(),
      icon: draft.value.icon,
      description: draft.value.description,
      agentType: draft.value.agentType,
      systemPrompt: draft.value.systemPrompt,
      tags,
      enabled: true,
      maxIterations: 10,
      // primaryKbId kept as string to preserve Snowflake precision.
      primaryKbId: selectedKbId.value,
    }
    const res: any = await agentApi.create(payload)
    const agentId = res.data?.id
    if (!agentId) throw new Error(t('agents.wizard.createFailed'))

    // Apply capability bindings sequentially so a partial failure is
    // attributable. IDs flow through as strings (Snowflake contract).
    await agentBindingApi.setSkills(agentId, selectedSkillIds.value as any)
    await agentBindingApi.setTools(agentId, selectedToolNames.value)
    await agentBindingApi.setKbs(agentId, selectedKbId.value ? [selectedKbId.value] : [])

    createdAgent.value = res.data
    step.value = 'success'
  } catch (e: any) {
    mcToast.error(e?.message || t('agents.wizard.createFailed'))
  } finally {
    creating.value = false
  }
}

function backToDescribe() {
  step.value = 'describe'
}

function buildAnother() {
  requirement.value = ''
  draft.value = null
  createdAgent.value = null
  tagsText.value = ''
  selectedToolNames.value = []
  selectedSkillIds.value = []
  selectedKbId.value = null
  step.value = 'describe'
}

function goChat() {
  if (createdAgent.value) {
    router.push({ path: '/chat', query: { agentId: String(createdAgent.value.id) } })
  }
}

function goRoster() {
  router.push({ path: '/agents' })
}
</script>

<style scoped>
.wizard-page { max-width: 720px; margin: 0 auto; padding: 24px 20px 48px; box-sizing: border-box;
  min-height: calc(100vh - 132px); display: flex; flex-direction: column; }

/* Short steps fill the remaining height and center; the tall confirm step
   keeps natural top-aligned flow so it scrolls instead of clipping. */
.wiz-body { flex: 1 1 auto; min-width: 0; }
.wiz-body--center { display: flex; flex-direction: column; justify-content: center; }

/* Step rail. Explicit width (not max-width) because the page is a flex
   column — margin:auto there suppresses the stretch, collapsing the rail to
   content width and zeroing the flex:1 connector lines. */
.wiz-rail { display: flex; align-items: center; justify-content: center; width: min(460px, 100%); margin: 0 auto 28px; }
.wiz-step { display: flex; align-items: center; gap: 8px; }
.wiz-dot { width: 26px; height: 26px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 700; background: var(--mc-bg-muted); border: 1px solid var(--mc-border); color: var(--mc-text-tertiary); }
.wiz-step.active .wiz-dot { background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: #fff; border-color: transparent; }
.wiz-step.done .wiz-dot { background: var(--mc-accent-soft); color: var(--mc-accent); border-color: transparent; }
.wiz-dot-check { width: 13px; height: 13px; }
.wiz-step-label { font-size: 13px; color: var(--mc-text-tertiary); }
.wiz-step.active .wiz-step-label { color: var(--mc-text-primary); font-weight: 600; }
.wiz-line { flex: 1; height: 2px; background: var(--mc-border-light); margin: 0 12px; }
.wiz-line.on { background: var(--mc-primary); }

/* Hero */
.wiz-hero { text-align: center; margin-bottom: 22px; }
.wiz-hero-icon { width: 56px; height: 56px; border-radius: 50%; background: var(--mc-primary-bg); display: inline-flex;
  align-items: center; justify-content: center; color: var(--mc-primary); margin-bottom: 12px; }
.wiz-hero-icon svg { width: 26px; height: 26px; }
.wiz-hero-icon.success { background: var(--mc-accent-soft); color: var(--mc-accent); }
.wiz-hero-icon.success svg { width: 30px; height: 30px; }
.wiz-title { font-size: 28px; font-weight: 800; letter-spacing: -0.03em; color: var(--mc-text-primary); margin: 0 0 6px; }
.wiz-subtitle { font-size: 15px; color: var(--mc-text-secondary); line-height: 1.7; margin: 0; }

/* Input card */
.wiz-input-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px;
  padding: 14px 16px; box-shadow: var(--mc-shadow-soft); }
.wiz-textarea { width: 100%; border: none; background: transparent; resize: none; font-size: 16px; line-height: 1.6;
  color: var(--mc-text-primary); font-family: inherit; outline: none; }
.wiz-input-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; }
.wiz-hint { font-size: 12px; color: var(--mc-text-tertiary); }

/* Examples */
.wiz-examples { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 14px; }
.wiz-try { font-size: 12px; color: var(--mc-text-tertiary); }
.wiz-chip { font-size: 13px; color: var(--mc-text-secondary); background: var(--mc-bg-elevated); border: 1px solid var(--mc-border-light);
  border-radius: 999px; padding: 5px 13px; cursor: pointer; }
.wiz-chip:hover { border-color: var(--mc-primary); color: var(--mc-primary); }

/* Notice */
.wiz-notice { display: flex; align-items: center; gap: 8px; padding: 10px 14px; margin-bottom: 16px;
  background: var(--mc-primary-bg); border: 1px solid var(--mc-primary-light); border-radius: 12px;
  font-size: 13px; font-weight: 600; color: var(--mc-primary-hover); }

/* Cards */
.wiz-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px;
  padding: 18px 20px; box-shadow: var(--mc-shadow-soft); margin-bottom: 14px; }
.wiz-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 16px; }
.wiz-field { display: flex; flex-direction: column; }
.wiz-field.wiz-full { grid-column: 1 / -1; }
.wiz-field > label { font-size: 13px; font-weight: 600; color: var(--mc-text-secondary); margin-bottom: 6px; }
.wiz-field .req { color: var(--mc-primary); }
.wiz-control { width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--mc-border);
  border-radius: 10px; background: var(--mc-input-bg); font-size: 14px; color: var(--mc-text-primary);
  font-family: inherit; outline: none; }
.wiz-control:focus { border-color: var(--mc-primary); }
textarea.wiz-control { resize: vertical; line-height: 1.6; }
.wiz-icon-trigger { display: flex; align-items: center; gap: 10px; width: 100%; box-sizing: border-box;
  padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 10px; background: var(--mc-input-bg);
  cursor: pointer; font-family: inherit; text-align: left; }
.wiz-icon-trigger:hover { border-color: var(--mc-border-strong); }
.wiz-icon-trigger__label { font-size: 14px; color: var(--mc-text-primary); }
.wiz-icon-trigger__action { margin-left: auto; font-size: 12px; color: var(--mc-primary); font-weight: 600; }

/* Section header */
.wiz-sec-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.wiz-sec-icon { width: 17px; height: 17px; color: var(--mc-primary); flex-shrink: 0; }
.wiz-notice-icon { width: 16px; height: 16px; flex-shrink: 0; }
.wiz-sec-title { font-size: 14px; font-weight: 700; color: var(--mc-text-primary); }

/* Actions */
.wiz-actions { display: flex; justify-content: space-between; align-items: center; margin-top: 18px; }
.wiz-actions--success { gap: 10px; }
.wiz-grow { flex: 1; }
.wiz-btn-primary { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 10px 18px;
  background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: #fff; border: none;
  border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); }
.wiz-btn-primary:disabled { opacity: 0.55; cursor: not-allowed; }
.wiz-btn-icon { width: 16px; height: 16px; }
.wiz-btn-ghost { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 10px 16px;
  background: transparent; color: var(--mc-text-secondary); border: 1px solid var(--mc-border); border-radius: 14px;
  font-size: 14px; font-weight: 600; cursor: pointer; }
.wiz-btn-ghost:hover { border-color: var(--mc-border-strong); }

/* Success */
.wiz-success-head { display: flex; align-items: center; gap: 14px; margin-bottom: 16px; }
.wiz-success-name { font-size: 17px; font-weight: 700; color: var(--mc-text-primary); margin: 0; }
.wiz-success-desc { font-size: 13px; color: var(--mc-text-secondary); margin: 4px 0 0; }
.wiz-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; border-top: 1px solid var(--mc-border-light); padding-top: 14px; }
.wiz-stat { text-align: center; }
.wiz-stat-num { font-size: 22px; font-weight: 800; color: var(--mc-primary-hover); }
.wiz-stat-label { font-size: 12px; color: var(--mc-text-tertiary); margin-top: 2px; }

@media (max-width: 640px) {
  .wizard-page { min-height: calc(100vh - 96px); padding: 16px 14px 32px; }
  .wiz-grid { grid-template-columns: 1fr; }
  .wiz-rail { margin-bottom: 20px; }
  .wiz-line { margin: 0 6px; }
  .wiz-step { gap: 5px; }
  .wiz-step-label { font-size: 12px; }
  .wiz-title { font-size: 22px; }
  .wiz-subtitle { font-size: 14px; }
  .wiz-hero-icon { width: 48px; height: 48px; }
  .wiz-card { padding: 14px 14px; }
  .wiz-input-foot { flex-direction: column; align-items: stretch; gap: 10px; }
  .wiz-input-foot .wiz-btn-primary { width: 100%; }
  .wiz-actions { flex-wrap: wrap; gap: 10px; }
  .wiz-actions--success { flex-direction: column; align-items: stretch; }
  .wiz-actions--success .wiz-btn-primary,
  .wiz-actions--success .wiz-btn-ghost { width: 100%; }
}

/* Below a phone's narrowest common width, drop the inactive rail labels so
   the three-step indicator never wraps awkwardly. */
@media (max-width: 420px) {
  .wiz-step:not(.active) .wiz-step-label { display: none; }
}
</style>
