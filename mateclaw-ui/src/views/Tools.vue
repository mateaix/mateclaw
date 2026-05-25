<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner tools-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('tools.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('tools.title') }}</h1>
            <p class="mc-page-desc">{{ t('tools.desc') }}</p>
          </div>
          <button class="btn-primary" @click="openCreateModal">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('tools.registerButton') }}
          </button>
        </div>

        <!-- 工具列表：核心 / 扩展 双段式 -->
        <div v-for="section in sections" :key="section.key" class="tools-section">
          <div class="tools-section-head">
            <span class="tools-section-title">{{ section.label }}</span>
            <span class="tools-section-count">{{ t('tools.sections.countItems', { n: section.rows.length }) }}</span>
            <span class="tools-section-hint">{{ section.hint }}</span>
          </div>
          <div class="tools-table-wrap mc-surface-card">
            <table class="tools-table">
              <thead>
                <tr>
                  <th>{{ t('tools.columns.tool') }}</th>
                  <th>{{ t('tools.columns.type') }}</th>
                  <th>{{ t('tools.columns.status') }}</th>
                  <th>{{ t('tools.columns.actions') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="tool in section.rows" :key="tool.id" class="tool-row">
                  <td>
                    <div class="tool-info">
                      <div class="tool-icon-wrap">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>
                        </svg>
                      </div>
                      <div>
                        <div class="tool-name">{{ primaryToolName(tool) }}</div>
                        <div class="tool-desc">{{ tool.description }}</div>
                        <div class="tool-meta-line">
                          <code v-if="tool.name && tool.name !== primaryToolName(tool)" class="tool-bean">{{ tool.name }}</code>
                          <code v-if="tool.beanName" class="tool-bean">{{ tool.beanName }}</code>
                        </div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <span class="type-badge" :class="'type-' + tool.toolType">{{ tool.toolType }}</span>
                  </td>
                  <td>
                    <label class="toggle-switch">
                      <input type="checkbox" :checked="tool.enabled" @change="toggleTool(tool)" />
                      <span class="toggle-slider"></span>
                    </label>
                  </td>
                  <td>
                    <div class="row-actions">
                      <button
                        v-if="canEditTier(tool)"
                        class="row-btn tier-btn"
                        :title="section.key === 'core' ? t('tools.tier.toExtensionHint') : t('tools.tier.toCoreHint')"
                        @click="moveTier(tool, section.key === 'core' ? 'extension' : 'core')"
                      >
                        {{ section.key === 'core' ? t('tools.tier.toExtension') : t('tools.tier.toCore') }}
                      </button>
                      <span v-else class="tier-locked" :title="t('tools.tier.lockedHint')">{{ t('tools.tier.locked') }}</span>
                      <button class="row-btn" @click="openEditModal(tool)">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                          <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                        </svg>
                      </button>
                      <button class="row-btn danger" @click="deleteTool(tool.id)">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <polyline points="3 6 5 6 21 6"/>
                          <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                        </svg>
                      </button>
                    </div>
                  </td>
                </tr>
                <tr v-if="section.rows.length === 0">
                  <td colspan="4" class="empty-row">
                    <div class="empty-state">
                      <span class="empty-icon">🔧</span>
                      <p>{{ t('tools.empty') }}</p>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- Modal -->
    <div v-if="showModal" class="modal-overlay">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ editingTool ? t('tools.modal.editTitle') : t('tools.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('tools.fields.name') }} *</label>
              <input v-model="form.name" class="form-input" :placeholder="t('tools.placeholders.name')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('tools.fields.beanName') }} *</label>
              <input v-model="form.beanName" class="form-input" :placeholder="t('tools.placeholders.beanName')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('tools.fields.type') }}</label>
              <select v-model="form.toolType" class="form-input">
                <option value="builtin">{{ t('tools.types.builtin') }}</option>
                <option value="mcp">{{ t('tools.types.mcp') }}</option>
                <option value="custom">{{ t('tools.types.custom') }}</option>
              </select>
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('tools.fields.description') }}</label>
              <input v-model="form.description" class="form-input" :placeholder="t('tools.placeholders.description')" />
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="closeModal">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="saveTool" :disabled="!form.name || !form.beanName">{{ t('common.save') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { toolApi } from '@/api/index'
import type { Tool } from '@/types/index'

const { t } = useI18n()
const tools = ref<Tool[]>([])

type Tier = 'core' | 'extension'

function effectiveTier(tool: Tool): Tier {
  return tool.disclosureTier === 'extension' ? 'extension' : 'core'
}

function primaryToolName(tool: Tool): string {
  const runtimeName = Array.isArray(tool.runtimeNames) ? tool.runtimeNames[0] : ''
  return runtimeName || tool.name
}

// Only builtin / channel atomic tools are tiered on the row itself; MCP / ACP /
// skill tools are tiered at their owning source, so their control is locked.
function canEditTier(tool: Tool): boolean {
  return tool.toolType === 'builtin' || tool.toolType === 'channel'
}

const sections = computed(() => [
  {
    key: 'core' as Tier,
    label: t('tools.sections.core'),
    hint: t('tools.tier.core.desc'),
    rows: tools.value.filter((tool) => effectiveTier(tool) === 'core'),
  },
  {
    key: 'extension' as Tier,
    label: t('tools.sections.extension'),
    hint: t('tools.tier.extension.desc'),
    rows: tools.value.filter((tool) => effectiveTier(tool) === 'extension'),
  },
])

async function moveTier(tool: Tool, tier: Tier) {
  try {
    await toolApi.setDisclosureTier(tool.id, tier)
    await loadTools()
  } catch (e: any) {
    mcToast.error(e?.message || t('tools.messages.tierFailed'))
  }
}
const showModal = ref(false)
const editingTool = ref<Tool | null>(null)

const defaultForm = () => ({ name: '', beanName: '', description: '', toolType: 'builtin' as string, enabled: true })
const form = ref<any>(defaultForm())

onMounted(loadTools)

async function loadTools() {
  try {
    const res: any = await toolApi.list()
    tools.value = res.data || []
  } catch (e) {
    tools.value = [
      { id: 1, name: 'Web Search', beanName: 'webSearchTool', description: 'Search the web for information', toolType: 'builtin', enabled: true, createTime: '' },
      { id: 2, name: 'Date & Time', beanName: 'dateTimeTool', description: 'Get current date and time', toolType: 'builtin', enabled: true, createTime: '' },
    ]
  }
}

function openCreateModal() {
  editingTool.value = null
  form.value = defaultForm()
  showModal.value = true
}

function openEditModal(tool: Tool) {
  editingTool.value = tool
  form.value = { ...tool }
  showModal.value = true
}

function closeModal() {
  showModal.value = false
  editingTool.value = null
}

async function saveTool() {
  try {
    if (editingTool.value) {
      await toolApi.update(editingTool.value.id, form.value)
    } else {
      await toolApi.create(form.value)
    }
    closeModal()
    await loadTools()
  } catch (e: any) { mcToast.error(e?.message || t('tools.messages.saveFailed')) }
}

async function deleteTool(id: string | number) {
  const ok = await mcConfirm({
    title: t('tools.messages.deleteTitle'),
    message: t('tools.messages.deleteConfirm'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await toolApi.delete(id)
    await loadTools()
  } catch (e: any) { mcToast.error(e?.message || t('tools.messages.deleteFailed')) }
}

async function toggleTool(tool: Tool) {
  try {
    await toolApi.toggle(tool.id, !tool.enabled)
    await loadTools()
  } catch (e: any) { mcToast.error(e?.message || t('tools.messages.toggleFailed')) }
}
</script>

<style scoped>
.tools-page { gap: 18px; }
.tools-section { display: flex; flex-direction: column; gap: 10px; margin-bottom: 22px; }
.tools-section-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.tools-section-title { font-size: 15px; font-weight: 700; color: var(--mc-text-primary); }
.tools-section-count { font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); background: var(--mc-bg-muted); padding: 2px 8px; border-radius: 10px; }
.tools-section-hint { font-size: 12px; color: var(--mc-text-tertiary); }
.tier-btn { width: auto; padding: 0 10px; font-size: 12px; font-weight: 600; white-space: nowrap; color: var(--mc-text-secondary); }
.tier-btn:hover { color: var(--mc-primary); border-color: var(--mc-primary); }
.tier-locked { display: inline-flex; align-items: center; padding: 0 8px; font-size: 11px; color: var(--mc-text-tertiary); cursor: help; }
.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.tools-table-wrap { overflow: hidden; }
.tools-table { width: 100%; border-collapse: collapse; }
.tools-table th { padding: 14px 16px; text-align: left; font-size: 12px; font-weight: 700; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.08em; background: var(--mc-bg-muted); border-bottom: 1px solid var(--mc-border); }
.tool-row { border-bottom: 1px solid var(--mc-border-light); transition: background 0.1s; }
.tool-row:hover { background: var(--mc-bg-muted); }
.tool-row:last-child { border-bottom: none; }
.tools-table td { padding: 14px 16px; font-size: 14px; color: var(--mc-text-primary); }
.tool-info { display: flex; align-items: center; gap: 10px; }
.tool-icon-wrap { width: 36px; height: 36px; background: linear-gradient(135deg, rgba(217,109,87,0.12), rgba(24,74,69,0.08)); border-radius: 12px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: var(--mc-text-secondary); }
.tool-name { font-weight: 500; color: var(--mc-text-primary); }
.tool-desc { font-size: 12px; color: var(--mc-text-tertiary); margin-top: 1px; }
.tool-meta-line { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.bean-name { background: var(--mc-bg-sunken); padding: 2px 8px; border-radius: 4px; font-size: 12px; color: var(--mc-text-primary); }
.tool-bean { display: inline-block; padding: 1px 6px; background: var(--mc-bg-sunken); border-radius: 4px; font-size: 11px; color: var(--mc-text-tertiary); font-family: var(--mc-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace); }
.type-badge { padding: 3px 10px; border-radius: 10px; font-size: 12px; font-weight: 500; }
.type-builtin { background: var(--mc-primary-bg); color: var(--mc-primary); }
.type-mcp { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.type-custom { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.toggle-switch { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(16px); }
.row-actions { display: flex; gap: 4px; }
.row-btn { width: 30px; height: 30px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 10px; cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--mc-text-secondary); transition: all 0.15s; }
.row-btn:hover { background: var(--mc-bg-sunken); }
.row-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }
.empty-row { padding: 40px !important; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; color: var(--mc-text-tertiary); }
.empty-icon { font-size: 32px; }
.empty-state p { font-size: 14px; margin: 0; }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 520px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); }
.form-input { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-sunken); width: 100%; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; border-top: 1px solid var(--mc-border-light); }
</style>
