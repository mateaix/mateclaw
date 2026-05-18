<template>
  <div class="page-container">
    <div class="page-shell">
      <div class="page-header">
        <div class="page-lead">
          <div class="page-kicker">{{ t('acp.kicker') }}</div>
          <h1 class="page-title">{{ t('acp.title') }}</h1>
          <p class="page-desc">{{ t('acp.desc') }}</p>
        </div>
        <div class="header-actions">
          <button class="btn-primary" @click="openCreateModal">
            + {{ t('acp.addEndpoint') }}
          </button>
        </div>
      </div>

      <!-- Table -->
      <div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ t('acp.columns.name') }}</th>
              <th>{{ t('acp.columns.command') }}</th>
              <th class="th-center">{{ t('acp.columns.status') }}</th>
              <th class="th-center">{{ t('acp.columns.enabled') }}</th>
              <th>{{ t('acp.columns.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ep in endpoints" :key="ep.id" class="data-row">
              <td>
                <div class="endpoint-info">
                  <div class="endpoint-name">
                    {{ ep.displayName || ep.name }}
                    <span v-if="ep.builtin" class="builtin-badge">{{ t('acp.builtin') }}</span>
                  </div>
                  <div class="endpoint-slug">{{ ep.name }}</div>
                  <div v-if="ep.description" class="endpoint-desc">{{ ep.description }}</div>
                </div>
              </td>
              <td class="cell-cmd">
                <code>{{ ep.command }} {{ argsPreview(ep) }}</code>
              </td>
              <td class="th-center">
                <span class="status-badge" :class="`status-${(ep.lastStatus || 'unknown').toLowerCase()}`">
                  {{ ep.lastStatus || t('acp.statusUnknown') }}
                </span>
                <div v-if="ep.lastError" class="status-error" :title="ep.lastError">
                  {{ ep.lastError.slice(0, 80) }}
                </div>
              </td>
              <td class="th-center">
                <label class="toggle-switch">
                  <input type="checkbox" :checked="ep.enabled" @change="toggle(ep)" />
                  <span class="toggle-slider"></span>
                </label>
              </td>
              <td>
                <div class="row-actions">
                  <button class="btn-link" :disabled="testingId === ep.id" @click="testEndpoint(ep)">
                    {{ testingId === ep.id ? t('acp.testing') : t('acp.test') }}
                  </button>
                  <button class="btn-link" @click="openEditModal(ep)">{{ t('common.edit') }}</button>
                  <button v-if="!ep.builtin" class="btn-link danger" @click="removeEndpoint(ep)">{{ t('common.delete') }}</button>
                </div>
              </td>
            </tr>
            <tr v-if="endpoints.length === 0">
              <td colspan="5" class="empty-row">
                <div class="empty-state">
                  <span class="empty-icon">🔌</span>
                  <p>{{ t('acp.empty') }}</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Test result panel -->
      <div v-if="lastTestResult" class="test-result mc-surface-card">
        <div class="test-result-head">
          <span :class="`status-badge status-${(lastTestResult.status || '').toLowerCase()}`">
            {{ lastTestResult.status }}
          </span>
          <span class="test-result-name">{{ lastTestResult.name }}</span>
          <span v-if="lastTestResult.elapsedMs != null" class="test-result-elapsed">
            · {{ lastTestResult.elapsedMs }}ms
          </span>
          <button class="btn-link" @click="lastTestResult = null">×</button>
        </div>
        <pre class="test-result-pre">{{ JSON.stringify(lastTestResult, null, 2) }}</pre>
      </div>
    </div>

    <!-- Modal -->
    <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ editing ? t('acp.modal.editTitle') : t('acp.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">&times;</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('acp.fields.name') }} *</label>
              <input v-model="form.name" class="form-input" placeholder="my-acp-agent" :disabled="!!editing" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('acp.fields.displayName') }}</label>
              <input v-model="form.displayName" class="form-input" placeholder="My ACP Agent" />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('acp.fields.description') }}</label>
              <input v-model="form.description" class="form-input" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('acp.fields.command') }} *</label>
              <input v-model="form.command" class="form-input mono" placeholder="npx" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('acp.fields.toolParseMode') }}</label>
              <select v-model="form.toolParseMode" class="form-input">
                <option value="call_title">call_title</option>
                <option value="call_detail">call_detail</option>
                <option value="update_detail">update_detail</option>
              </select>
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('acp.fields.args') }}</label>
              <input v-model="form.argsJson" class="form-input mono" placeholder='["-y","@zed-industries/codex-acp"]' />
            </div>
            <div class="form-group full-width">
              <div class="env-header">
                <label class="form-label">{{ t('acp.fields.env') }}</label>
                <button type="button" class="btn-link env-mode-toggle" @click="toggleEnvMode">
                  {{ envJsonMode ? t('acp.env.formMode') : t('acp.env.jsonMode') }}
                </button>
              </div>

              <!-- Suggestion chips: clicking adds a pre-named row to the form. -->
              <div v-if="!envJsonMode && envSuggestions.length > 0" class="env-suggestions">
                <span class="env-suggestions-label">{{ t('acp.env.suggested') }}:</span>
                <button
                  v-for="sug in envSuggestions"
                  :key="sug.key"
                  type="button"
                  class="env-suggestion-chip"
                  :class="{ 'is-added': hasEnvKey(sug.key) }"
                  :title="sug.hint"
                  :disabled="hasEnvKey(sug.key)"
                  @click="addSuggestedEnv(sug)"
                >
                  {{ sug.key }}
                  <span class="chip-mark">{{ hasEnvKey(sug.key) ? '✓' : '+' }}</span>
                </button>
              </div>

              <!-- Visual key/value editor (default mode). -->
              <div v-if="!envJsonMode" class="env-table">
                <div v-for="(entry, idx) in envEntries" :key="idx" class="env-row">
                  <input
                    v-model="entry.key"
                    class="form-input mono env-key-input"
                    :placeholder="t('acp.env.keyPlaceholder')"
                    @blur="entry.masked = isSecretKey(entry.key)"
                  />
                  <div class="env-value-wrap">
                    <input
                      v-model="entry.value"
                      :type="entry.masked && !entry.revealed ? 'password' : 'text'"
                      class="form-input mono env-value-input"
                      :placeholder="t('acp.env.valuePlaceholder')"
                      autocomplete="off"
                    />
                    <button
                      v-if="entry.masked"
                      type="button"
                      class="env-eye"
                      @click="entry.revealed = !entry.revealed"
                      :title="entry.revealed ? t('acp.env.hide') : t('acp.env.show')"
                    >
                      {{ entry.revealed ? '🙈' : '👁' }}
                    </button>
                  </div>
                  <button
                    type="button"
                    class="env-remove"
                    @click="removeEnvEntry(idx)"
                    :title="t('acp.env.remove')"
                  >×</button>
                </div>
                <button type="button" class="env-add-btn" @click="addEnvEntry">
                  + {{ t('acp.env.addRow') }}
                </button>
              </div>

              <!-- Raw JSON fallback for advanced users. -->
              <textarea
                v-else
                v-model="form.envJson"
                class="form-input form-textarea mono"
                rows="3"
                placeholder='{"OPENAI_API_KEY":"..."}'
              ></textarea>

              <!-- Endpoint-specific hint (e.g. claude-code OAuth caveat). -->
              <div v-if="envHint" class="env-hint">💡 {{ envHint }}</div>
            </div>
            <div class="form-group full-width">
              <label class="toggle-inline">
                <input type="checkbox" v-model="form.enabled" />
                {{ t('acp.fields.enabled') }}
              </label>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="closeModal">{{ t('common.cancel') }}</button>
          <button class="btn-primary" :disabled="!canSave" @click="saveEndpoint">{{ t('common.save') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { acpApi } from '@/api/index'

interface AcpEndpoint {
  id: number
  name: string
  displayName?: string
  description?: string
  command: string
  argsJson?: string
  envJson?: string
  toolParseMode?: string
  builtin?: boolean
  trusted?: boolean
  enabled?: boolean
  lastStatus?: string
  lastError?: string
  lastTestedAt?: string
  stdioBufferLimitBytes?: number
}

const { t } = useI18n()
const endpoints = ref<AcpEndpoint[]>([])
const showModal = ref(false)
const editing = ref<AcpEndpoint | null>(null)
const testingId = ref<number | null>(null)
const lastTestResult = ref<any>(null)

const defaultForm = (): any => ({
  name: '',
  displayName: '',
  description: '',
  command: '',
  argsJson: '[]',
  envJson: '{}',
  toolParseMode: 'call_title',
  enabled: false,
})
const form = reactive<any>(defaultForm())

const canSave = computed(() => !!form.name && !!form.command)

// ==================== Env editor state ====================
//
// The env field used to be a raw JSON textarea — easy to mistype the
// quotes / commas / brace and impossible for users to discover which
// API key they actually need to paste. The visual editor below replaces
// that with a key/value table + per-endpoint suggestion chips, and
// keeps a "Edit as JSON" toggle so advanced users can still drop into
// a textarea when they want.
//
// Source-of-truth contract:
//   - In form mode: envEntries is the truth; we serialize it into
//     form.envJson at save time (and on toggle).
//   - In JSON mode: form.envJson is the truth; we parse it back to
//     envEntries when toggling off.

interface EnvEntry {
  key: string
  value: string
  masked: boolean
  revealed: boolean
}

interface EnvSuggestion {
  key: string
  hint: string
}

const envEntries = ref<EnvEntry[]>([])
const envJsonMode = ref(false)

/**
 * Treat any key whose name implies "secret" as masked by default. The
 * user can still toggle visibility on the row. The pattern matches the
 * obvious cases (API_KEY / TOKEN / SECRET / PASS*) — false negatives
 * just show the value in plaintext, which is no worse than the old
 * JSON textarea did.
 */
function isSecretKey(key: string): boolean {
  if (!key) return false
  return /(KEY|TOKEN|SECRET|PASS|CREDENTIAL)/i.test(key)
}

function entriesFromJson(json: string): EnvEntry[] {
  if (!json || !json.trim()) return []
  try {
    const obj = JSON.parse(json)
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return []
    return Object.entries(obj).map(([k, v]) => ({
      key: k,
      value: typeof v === 'string' ? v : String(v),
      masked: isSecretKey(k),
      revealed: false,
    }))
  } catch {
    return []
  }
}

function entriesToJson(entries: EnvEntry[]): string {
  const obj: Record<string, string> = {}
  for (const e of entries) {
    const k = e.key.trim()
    if (k) obj[k] = e.value
  }
  return JSON.stringify(obj)
}

function addEnvEntry() {
  envEntries.value.push({ key: '', value: '', masked: false, revealed: false })
}

function removeEnvEntry(idx: number) {
  envEntries.value.splice(idx, 1)
}

function hasEnvKey(key: string): boolean {
  return envEntries.value.some((e) => e.key === key)
}

function addSuggestedEnv(sug: EnvSuggestion) {
  if (hasEnvKey(sug.key)) return
  envEntries.value.push({
    key: sug.key,
    value: '',
    masked: isSecretKey(sug.key),
    revealed: false,
  })
}

/**
 * Suggestion chips per endpoint — mirrors the server-side
 * AcpRuntimeSupport.expectedAuthEnvVar() so the auth-error translator
 * and the form helper agree on what env var each endpoint expects.
 */
const envSuggestions = computed<EnvSuggestion[]>(() => {
  const slug = String(form.name || '').toLowerCase()
  const cmd = String(form.command || '').toLowerCase()
  const args = String(form.argsJson || '').toLowerCase()
  const matches = (kw: string) => slug.includes(kw) || cmd.includes(kw) || args.includes(kw)

  if (matches('claude') || matches('anthropic')) {
    return [{ key: 'ANTHROPIC_API_KEY', hint: t('acp.env.hints.anthropic') }]
  }
  if (matches('codex') || matches('openai')) {
    return [{ key: 'OPENAI_API_KEY', hint: t('acp.env.hints.openai') }]
  }
  if (matches('qwen') || matches('dashscope')) {
    return [{ key: 'DASHSCOPE_API_KEY', hint: t('acp.env.hints.dashscope') }]
  }
  if (matches('gemini') || matches('google')) {
    return [{ key: 'GOOGLE_API_KEY', hint: t('acp.env.hints.google') }]
  }
  // opencode is multi-provider; surface a soft hint instead of a chip
  // because we don't know which provider the user has configured.
  return []
})

/**
 * Caveat banner shown beneath the env editor. Today it only fires for
 * claude-code endpoints, where users are most likely to confuse OAuth
 * login (which doesn't authenticate against the public API) with the
 * env var the third-party Zed wrapper actually needs.
 */
const envHint = computed(() => {
  const slug = String(form.name || '').toLowerCase()
  if (slug.includes('claude')) return t('acp.env.hints.claudeOauth')
  return ''
})

/**
 * Switch between form and JSON. The truth-of-record swaps direction;
 * the watch below performs the bridging conversion so the user never
 * sees stale data on the other side.
 */
function toggleEnvMode() {
  envJsonMode.value = !envJsonMode.value
}

watch(envJsonMode, (isJson, wasJson) => {
  if (isJson && !wasJson) {
    // form → JSON: serialize entries; the textarea now owns the value.
    form.envJson = entriesToJson(envEntries.value)
  } else if (!isJson && wasJson) {
    // JSON → form: parse textarea back into rows; if the JSON is
    // invalid, keep the entries empty rather than throw — the user can
    // fix the JSON and toggle again.
    envEntries.value = entriesFromJson(form.envJson)
  }
})

onMounted(loadEndpoints)

async function loadEndpoints() {
  try {
    const res: any = await acpApi.list()
    endpoints.value = res?.data || []
  } catch (e: any) {
    endpoints.value = []
    mcToast.error(typeof e === 'string' ? e : e?.message || t('acp.loadFailed'))
  }
}

function argsPreview(ep: AcpEndpoint): string {
  if (!ep.argsJson) return ''
  try {
    const parsed = JSON.parse(ep.argsJson)
    if (Array.isArray(parsed)) return parsed.join(' ')
  } catch { /* fall through */ }
  return ep.argsJson
}

function openCreateModal() {
  editing.value = null
  Object.assign(form, defaultForm())
  envEntries.value = []
  envJsonMode.value = false
  showModal.value = true
}

function openEditModal(ep: AcpEndpoint) {
  editing.value = ep
  Object.assign(form, defaultForm(), {
    name: ep.name,
    displayName: ep.displayName || '',
    description: ep.description || '',
    command: ep.command,
    argsJson: ep.argsJson || '[]',
    envJson: ep.envJson || '{}',
    toolParseMode: ep.toolParseMode || 'call_title',
    enabled: !!ep.enabled,
  })
  // Always open in form mode so users see the structured editor first.
  // The "Edit as JSON" toggle is one click away if they need it.
  envEntries.value = entriesFromJson(form.envJson)
  envJsonMode.value = false
  showModal.value = true
}

function closeModal() {
  showModal.value = false
  editing.value = null
}

async function saveEndpoint() {
  // In form mode, the entries array is the truth — serialize it back
  // into envJson before validating. In JSON mode the textarea is the
  // truth and we validate it as-is.
  if (!envJsonMode.value) {
    form.envJson = entriesToJson(envEntries.value)
  }
  // Sanity-check args/env are valid JSON before sending; the server
  // will tolerate empty strings, but we'd rather fail fast in UI.
  try {
    if (form.argsJson) JSON.parse(form.argsJson)
    if (form.envJson) JSON.parse(form.envJson)
  } catch (e: any) {
    mcToast.error(t('acp.invalidJson') + ': ' + (e?.message || 'parse error'))
    return
  }
  try {
    if (editing.value) {
      await acpApi.update(editing.value.id, form)
    } else {
      await acpApi.create(form)
    }
    closeModal()
    await loadEndpoints()
  } catch (e: any) {
    mcToast.error(typeof e === 'string' ? e : e?.message || t('acp.saveFailed'))
  }
}

async function removeEndpoint(ep: AcpEndpoint) {
  const ok = await mcConfirm({
    title: t('acp.deleteTitle'),
    message: t('acp.deleteConfirm', { name: ep.name }),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await acpApi.delete(ep.id)
    await loadEndpoints()
  } catch (e: any) {
    mcToast.error(typeof e === 'string' ? e : e?.message || t('acp.deleteFailed'))
  }
}

async function toggle(ep: AcpEndpoint) {
  try {
    await acpApi.toggle(ep.id, !ep.enabled)
    await loadEndpoints()
  } catch (e: any) {
    mcToast.error(typeof e === 'string' ? e : e?.message || t('acp.toggleFailed'))
  }
}

async function testEndpoint(ep: AcpEndpoint) {
  testingId.value = ep.id
  lastTestResult.value = null
  try {
    const res: any = await acpApi.test(ep.id)
    lastTestResult.value = res?.data || null
    await loadEndpoints()
  } catch (e: any) {
    lastTestResult.value = {
      name: ep.name,
      status: 'ERROR',
      error: typeof e === 'string' ? e : e?.message || 'unknown',
    }
  } finally {
    testingId.value = null
  }
}
</script>

<style scoped>
.page-container { padding: 0; height: 100%; min-height: 0; overflow: auto; }
.page-shell { display: flex; flex-direction: column; gap: 14px; padding: 22px; min-height: 100%; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.page-kicker { font-size: 11px; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.08em; font-weight: 700; }
.page-title { font-size: 22px; font-weight: 700; color: var(--mc-text-primary); margin: 4px 0; }
.page-desc { color: var(--mc-text-secondary); margin: 0; font-size: 13px; }
.header-actions { display: flex; gap: 8px; }
.btn-primary { padding: 8px 14px; background: var(--mc-primary); color: white; border: none; border-radius: 10px; font-size: 13px; font-weight: 600; cursor: pointer; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { padding: 8px 14px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 13px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-link { background: none; border: none; color: var(--mc-primary); cursor: pointer; padding: 4px 8px; font-size: 12px; font-weight: 500; }
.btn-link:hover { color: var(--mc-primary-hover); }
.btn-link.danger { color: var(--mc-danger); }
.btn-link:disabled { color: var(--mc-text-tertiary); cursor: not-allowed; }

.table-wrap { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border-light); border-radius: 14px; overflow: hidden; }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { padding: 12px 14px; text-align: left; font-size: 11px; font-weight: 700; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.06em; background: var(--mc-bg-muted); border-bottom: 1px solid var(--mc-border); }
.data-table td { padding: 12px 14px; font-size: 13px; color: var(--mc-text-primary); border-bottom: 1px solid var(--mc-border-light); vertical-align: top; }
.data-row:hover { background: var(--mc-bg-muted); }
.th-center { text-align: center; }
.endpoint-info { display: flex; flex-direction: column; gap: 2px; }
.endpoint-name { font-weight: 600; display: flex; align-items: center; gap: 6px; }
.builtin-badge { padding: 1px 6px; background: rgba(34, 197, 94, 0.12); color: #16a34a; border-radius: 999px; font-size: 10px; font-weight: 700; text-transform: uppercase; }
.endpoint-slug { font-size: 11px; color: var(--mc-text-tertiary); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.endpoint-desc { font-size: 12px; color: var(--mc-text-secondary); margin-top: 4px; }
.cell-cmd code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; background: var(--mc-bg-sunken); padding: 2px 6px; border-radius: 4px; color: var(--mc-text-primary); display: inline-block; max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }

.status-badge { padding: 2px 10px; border-radius: 999px; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em; }
.status-ok { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.status-error { background: var(--mc-danger-bg); color: var(--mc-danger); }
.status-unknown { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }

td .status-error { background: none; color: var(--mc-text-tertiary); font-size: 11px; padding: 4px 0 0; max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.row-actions { display: flex; gap: 4px; }

.toggle-switch { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(16px); }

.empty-row { padding: 40px !important; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; color: var(--mc-text-tertiary); }
.empty-icon { font-size: 32px; }

.test-result { padding: 14px; }
.test-result-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.test-result-name { font-weight: 600; }
.test-result-elapsed { color: var(--mc-text-tertiary); font-size: 12px; }
.test-result-pre { background: var(--mc-bg-sunken); padding: 12px; border-radius: 8px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; line-height: 1.5; max-height: 360px; overflow: auto; white-space: pre-wrap; word-break: break-word; margin: 0; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 640px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 22px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 17px; font-weight: 600; margin: 0; }
.modal-close { width: 30px; height: 30px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 22px; line-height: 1; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); }
.modal-body { flex: 1; overflow-y: auto; padding: 18px 22px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-group { display: flex; flex-direction: column; gap: 4px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); }
.form-input { padding: 8px 10px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 13px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-sunken); }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1); }
.form-input.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.form-textarea { resize: vertical; }
.toggle-inline { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 22px; border-top: 1px solid var(--mc-border-light); }

/* ---------- Visual env editor ---------- */
.env-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.env-mode-toggle { font-size: 11px; padding: 0; }

.env-suggestions {
  display: flex; flex-wrap: wrap; gap: 6px; align-items: center;
  margin: 4px 0 8px; padding: 7px 10px;
  background: var(--mc-primary-bg); border-radius: 8px; font-size: 12px;
}
.env-suggestions-label { color: var(--mc-text-secondary); font-weight: 600; }
.env-suggestion-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 9px;
  background: var(--mc-bg-elevated); border: 1px solid var(--mc-border);
  border-radius: 999px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px; cursor: pointer; color: var(--mc-text-primary);
  transition: 0.15s;
}
.env-suggestion-chip:hover { border-color: var(--mc-primary); color: var(--mc-primary); }
.env-suggestion-chip:disabled { cursor: default; }
.env-suggestion-chip.is-added {
  background: rgba(34, 197, 94, 0.12); color: #16a34a;
  border-color: transparent;
}
.env-suggestion-chip .chip-mark { font-weight: 700; opacity: 0.7; }

.env-table { display: flex; flex-direction: column; gap: 6px; }
.env-row { display: grid; grid-template-columns: minmax(120px, 1fr) minmax(120px, 1.4fr) 24px; gap: 6px; align-items: center; }
.env-key-input, .env-value-input { font-size: 12px; padding: 7px 9px; }
.env-value-wrap { position: relative; display: flex; }
.env-value-input { padding-right: 32px; flex: 1; }
.env-eye {
  position: absolute; right: 4px; top: 50%; transform: translateY(-50%);
  border: none; background: none; font-size: 13px; cursor: pointer; padding: 2px;
  width: 24px; height: 24px; line-height: 1; border-radius: 4px;
}
.env-eye:hover { background: var(--mc-bg-sunken); }
.env-remove {
  width: 24px; height: 24px; border: none; background: none; cursor: pointer;
  color: var(--mc-text-tertiary); border-radius: 6px; font-size: 16px; line-height: 1;
}
.env-remove:hover { background: var(--mc-danger-bg); color: var(--mc-danger); }
.env-add-btn {
  padding: 6px; background: none; border: 1px dashed var(--mc-border);
  border-radius: 8px; color: var(--mc-text-secondary); font-size: 12px;
  cursor: pointer; transition: 0.15s;
}
.env-add-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); }
.env-hint {
  margin-top: 8px; padding: 7px 10px;
  background: var(--mc-primary-bg); border-radius: 6px;
  font-size: 11px; color: var(--mc-text-secondary); line-height: 1.55;
}
</style>
