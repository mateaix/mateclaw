<template>
  <div class="settings-section">
    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('security.toolGuard.title') }}</h2>
        <p class="section-desc">{{ t('security.toolGuard.desc') }}</p>
      </div>
      <button class="btn-primary" @click="openCreateRuleModal">
        {{ t('security.toolGuard.addRule') }}
      </button>
    </div>

    <!-- Global Config -->
    <div class="config-card">
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('security.toolGuard.enabled') }}</div>
          <div class="setting-hint">{{ t('security.toolGuard.enabledHint') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input type="checkbox" v-model="guardConfig.enabled" @change="saveGuardConfig" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>
      <div class="config-row">
        <label>{{ t('security.toolGuard.guardScope') }}</label>
        <select v-model="guardConfig.guardScope" @change="saveGuardConfig" class="config-select">
          <option value="all">{{ t('security.toolGuard.scopeAll') }}</option>
          <option value="selected">{{ t('security.toolGuard.scopeSelected') }}</option>
        </select>
      </div>
      <div v-if="guardConfig.guardScope === 'selected'" class="config-row">
        <label>{{ t('security.toolGuard.guardedTools') }}</label>
        <div class="tag-input">
          <span v-for="tool in guardedTools" :key="tool" class="tag">
            {{ tool }}
            <button class="tag-remove" @click="removeGuardedTool(tool)">&times;</button>
          </span>
          <input
            v-model="newGuardedTool"
            :placeholder="t('security.toolGuard.guardedToolsPlaceholder')"
            @keydown.enter.prevent="addGuardedTool"
            class="tag-input-field"
          />
        </div>
      </div>
      <div class="config-row">
        <label>{{ t('security.toolGuard.deniedTools') }}</label>
        <div class="tag-input">
          <span v-for="tool in deniedTools" :key="tool" class="tag tag-danger">
            {{ tool }}
            <button class="tag-remove" @click="removeDeniedTool(tool)">&times;</button>
          </span>
          <input
            v-model="newDeniedTool"
            :placeholder="t('security.toolGuard.deniedToolsPlaceholder')"
            @keydown.enter.prevent="addDeniedTool"
            class="tag-input-field"
          />
        </div>
      </div>
    </div>

    <!-- Rules Table -->
    <div class="rules-section">
      <h3 class="subsection-title">{{ t('security.toolGuard.rules') }}</h3>
      <div class="rules-table-wrapper">
        <table class="rules-table">
          <thead>
            <tr>
              <th>{{ t('security.toolGuard.columns.name') }}</th>
              <th>{{ t('security.toolGuard.columns.severity') }}</th>
              <th>{{ t('security.toolGuard.columns.category') }}</th>
              <th>{{ t('security.toolGuard.columns.decision') }}</th>
              <th>{{ t('security.toolGuard.columns.builtin') }}</th>
              <th>{{ t('security.toolGuard.columns.enabled') }}</th>
              <th>{{ t('security.toolGuard.columns.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="rule in rules" :key="rule.ruleId">
              <td>
                <div class="rule-name">{{ rule.name }}</div>
                <div class="rule-pattern" :title="rule.pattern">{{ rule.pattern }}</div>
              </td>
              <td>
                <span class="severity-badge" :class="'severity-' + rule.severity?.toLowerCase()">
                  {{ t('security.severity.' + rule.severity) || rule.severity }}
                </span>
              </td>
              <td><span class="category-tag">{{ rule.category }}</span></td>
              <td>
                <span class="decision-badge" :class="'decision-' + rule.decision?.toLowerCase()">
                  {{ t('security.decision.' + rule.decision) || rule.decision }}
                </span>
              </td>
              <td>
                <span class="type-badge" :class="rule.builtin ? 'builtin' : 'custom'">
                  {{ rule.builtin ? t('security.toolGuard.builtinBadge') : t('security.toolGuard.customBadge') }}
                </span>
              </td>
              <td>
                <label class="toggle-switch toggle-sm">
                  <input type="checkbox" :checked="rule.enabled" @change="toggleRule(rule)" />
                  <span class="toggle-slider"></span>
                </label>
              </td>
              <td>
                <div class="action-btns">
                  <button class="action-btn" @click="openEditRuleModal(rule)" :title="t('common.edit')">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                    </svg>
                  </button>
                  <button
                    v-if="!rule.builtin"
                    class="action-btn danger"
                    @click="deleteRule(rule)"
                    :title="t('common.delete')"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <polyline points="3 6 5 6 21 6"/>
                      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
                    </svg>
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!rules.length" class="empty-state">{{ t('security.audit.noLogs') }}</div>
      </div>
    </div>

    <!-- Rule Modal -->
    <Teleport to="body">
      <div v-if="showRuleModal" class="modal-overlay">
        <div class="modal">
          <div class="modal-header">
            <h3>{{ editingRule ? t('security.toolGuard.editRule') : t('security.toolGuard.addRule') }}</h3>
            <button class="modal-close" @click="showRuleModal = false">&times;</button>
          </div>
          <div class="modal-body">
            <div class="form-grid">
              <div class="form-group" v-if="!editingRule">
                <label>{{ t('security.toolGuard.fields.ruleId') }} <span class="required">*</span></label>
                <input
                  v-model.trim="ruleForm.ruleId"
                  class="form-input"
                  placeholder="CUSTOM_RULE_001"
                  required
                />
              </div>
              <div class="form-group">
                <label>{{ t('security.toolGuard.fields.name') }} <span class="required">*</span></label>
                <input v-model="ruleForm.name" class="form-input" required />
              </div>
              <div class="form-group">
                <label>{{ t('security.toolGuard.fields.pattern') }} <span class="required">*</span></label>
                <input v-model="ruleForm.pattern" class="form-input mono" placeholder="regex pattern" required />
              </div>
              <div class="form-group">
                <label>{{ t('security.toolGuard.fields.severity') }}</label>
                <select v-model="ruleForm.severity" class="form-input">
                  <option value="CRITICAL">CRITICAL</option>
                  <option value="HIGH">HIGH</option>
                  <option value="MEDIUM">MEDIUM</option>
                  <option value="LOW">LOW</option>
                  <option value="INFO">INFO</option>
                </select>
              </div>
              <div class="form-group">
                <label>{{ t('security.toolGuard.fields.category') }}</label>
                <select v-model="ruleForm.category" class="form-input">
                  <option value="COMMAND_INJECTION">COMMAND_INJECTION</option>
                  <option value="DATA_EXFILTRATION">DATA_EXFILTRATION</option>
                  <option value="PATH_TRAVERSAL">PATH_TRAVERSAL</option>
                  <option value="SENSITIVE_FILE_ACCESS">SENSITIVE_FILE_ACCESS</option>
                  <option value="NETWORK_ABUSE">NETWORK_ABUSE</option>
                  <option value="CREDENTIAL_EXPOSURE">CREDENTIAL_EXPOSURE</option>
                  <option value="RESOURCE_ABUSE">RESOURCE_ABUSE</option>
                  <option value="CODE_EXECUTION">CODE_EXECUTION</option>
                  <option value="PRIVILEGE_ESCALATION">PRIVILEGE_ESCALATION</option>
                </select>
              </div>
              <div class="form-group">
                <label>{{ t('security.toolGuard.fields.decision') }}</label>
                <select v-model="ruleForm.decision" class="form-input">
                  <option value="BLOCK">BLOCK</option>
                  <option value="NEEDS_APPROVAL">NEEDS_APPROVAL</option>
                </select>
              </div>
              <div class="form-group">
                <label>{{ t('security.toolGuard.fields.toolName') }}</label>
                <input v-model="ruleForm.toolName" class="form-input" placeholder="execute_shell_command" />
              </div>
              <div class="form-group">
                <label>{{ t('security.toolGuard.fields.remediation') }}</label>
                <input v-model="ruleForm.remediation" class="form-input" />
              </div>
              <div class="form-group">
                <label>{{ t('security.toolGuard.fields.priority') }}</label>
                <input v-model.number="ruleForm.priority" type="number" class="form-input" />
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showRuleModal = false">{{ t('common.cancel') }}</button>
            <button class="btn-primary" @click="saveRule">{{ t('common.save') }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { securityApi } from '@/api'
import { parseJsonArray } from '../composables/helpers'
import type { GuardRule } from '@/types'

const { t } = useI18n()

// ==================== Guard Config ====================

const guardConfig = reactive({
  enabled: true,
  guardScope: 'all',
  guardedToolsJson: '',
  deniedToolsJson: '',
})

const guardedTools = ref<string[]>([])
const deniedTools = ref<string[]>([])
const newGuardedTool = ref('')
const newDeniedTool = ref('')

async function loadGuardConfig() {
  try {
    const res: any = await securityApi.getGuardConfig()
    const data = res.data
    guardConfig.enabled = data.enabled
    guardConfig.guardScope = data.guardScope || 'all'
    guardConfig.guardedToolsJson = data.guardedToolsJson || ''
    guardConfig.deniedToolsJson = data.deniedToolsJson || ''
    guardedTools.value = parseJsonArray(data.guardedToolsJson)
    deniedTools.value = parseJsonArray(data.deniedToolsJson)
  } catch {
    // ignore
  }
}

async function saveGuardConfig() {
  try {
    await securityApi.updateGuardConfig({
      enabled: guardConfig.enabled,
      guardScope: guardConfig.guardScope,
      guardedToolsJson: JSON.stringify(guardedTools.value),
      deniedToolsJson: JSON.stringify(deniedTools.value),
    })
  } catch {
    // ignore
  }
}

function addGuardedTool() {
  const val = newGuardedTool.value.trim()
  if (val && !guardedTools.value.includes(val)) {
    guardedTools.value.push(val)
    saveGuardConfig()
  }
  newGuardedTool.value = ''
}

function removeGuardedTool(tool: string) {
  guardedTools.value = guardedTools.value.filter(t => t !== tool)
  saveGuardConfig()
}

function addDeniedTool() {
  const val = newDeniedTool.value.trim()
  if (val && !deniedTools.value.includes(val)) {
    deniedTools.value.push(val)
    saveGuardConfig()
  }
  newDeniedTool.value = ''
}

function removeDeniedTool(tool: string) {
  deniedTools.value = deniedTools.value.filter(t => t !== tool)
  saveGuardConfig()
}

// ==================== Rules ====================

const rules = ref<GuardRule[]>([])
const showRuleModal = ref(false)
const editingRule = ref<GuardRule | null>(null)
const ruleForm = reactive({
  ruleId: '',
  name: '',
  pattern: '',
  severity: 'HIGH',
  category: 'COMMAND_INJECTION',
  decision: 'NEEDS_APPROVAL',
  toolName: '',
  remediation: '',
  priority: 100,
})

async function loadRules() {
  try {
    const res: any = await securityApi.listRules({ page: 1, size: 200 })
    rules.value = res.data?.records || []
  } catch {
    // ignore
  }
}

function openCreateRuleModal() {
  editingRule.value = null
  Object.assign(ruleForm, {
    ruleId: '',
    name: '',
    pattern: '',
    severity: 'HIGH',
    category: 'COMMAND_INJECTION',
    decision: 'NEEDS_APPROVAL',
    toolName: '',
    remediation: '',
    priority: 100,
  })
  showRuleModal.value = true
}

function openEditRuleModal(rule: GuardRule) {
  editingRule.value = rule
  Object.assign(ruleForm, {
    ruleId: rule.ruleId,
    name: rule.name,
    pattern: rule.pattern,
    severity: rule.severity,
    category: rule.category,
    decision: rule.decision,
    toolName: rule.toolName || '',
    remediation: rule.remediation || '',
    priority: rule.priority,
  })
  showRuleModal.value = true
}

async function saveRule() {
  if (!editingRule.value && !ruleForm.ruleId.trim()) {
    mcToast.error(t('security.toolGuard.messages.ruleIdRequired'))
    return
  }
  if (!ruleForm.name.trim()) {
    mcToast.error(t('security.toolGuard.messages.nameRequired'))
    return
  }
  if (!ruleForm.pattern.trim()) {
    mcToast.error(t('security.toolGuard.messages.patternRequired'))
    return
  }

  try {
    if (editingRule.value) {
      await securityApi.updateRule(editingRule.value.ruleId, ruleForm)
    } else {
      await securityApi.createRule(ruleForm)
    }
    showRuleModal.value = false
    loadRules()
  } catch (e: any) {
    const raw = e?.msg || e?.message || ''
    if (typeof raw === 'string' && raw.toLowerCase().includes('already exists')) {
      mcToast.error(t('security.toolGuard.messages.ruleIdDuplicate'))
    } else {
      mcToast.error(raw || t('security.toolGuard.messages.saveFailed'))
    }
  }
}

async function toggleRule(rule: GuardRule) {
  try {
    await securityApi.toggleRule(rule.ruleId, !rule.enabled)
    loadRules()
  } catch {
    // ignore
  }
}

async function deleteRule(rule: GuardRule) {
  if (!confirm(t('security.toolGuard.deleteConfirm', { name: rule.name }))) return
  try {
    await securityApi.deleteRule(rule.ruleId)
    loadRules()
  } catch {
    // ignore
  }
}

// ==================== Init ====================

onMounted(async () => {
  await Promise.all([loadGuardConfig(), loadRules()])
})
</script>

<style>
@import '../shared.css';
</style>

<style scoped>
.rules-section { margin-top: 8px; }
.subsection-title { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 12px; }

.rule-name { font-weight: 500; }
.required { color: var(--mc-danger, #ef4444); }
.rule-pattern {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
