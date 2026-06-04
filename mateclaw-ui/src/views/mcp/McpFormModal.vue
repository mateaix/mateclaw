<template>
  <div v-if="modelValue" class="modal-overlay" @click.self="close">
    <div class="modal modal-wide">
      <div class="modal-header">
        <h2>
          {{ headerTitle }}
          <span v-if="catalogBadge" class="catalog-badge">{{ catalogBadge }}</span>
        </h2>
        <button class="modal-close" @click="close" :aria-label="t('common.cancel')">&times;</button>
      </div>

      <div class="modal-body">
        <div v-if="credentialHint" class="credential-hint">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 9v4M12 17h.01"/>
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
          </svg>
          <span>{{ credentialHint }}</span>
        </div>

        <div class="form-grid">
          <div class="form-group">
            <label class="form-label">{{ t('mcp.fields.name') }} *</label>
            <input v-model="form.name" class="form-input" :placeholder="t('mcp.placeholders.name')" />
          </div>
          <div class="form-group">
            <label class="form-label">{{ t('mcp.fields.transport') }} *</label>
            <select v-model="form.transport" class="form-input">
              <option value="stdio">Stdio</option>
              <option value="sse">SSE</option>
              <option value="streamable_http">Streamable HTTP</option>
            </select>
          </div>
          <div class="form-group full-width">
            <label class="form-label">{{ t('mcp.fields.description') }}</label>
            <input v-model="form.description" class="form-input" :placeholder="t('mcp.placeholders.description')" />
          </div>

          <template v-if="form.transport === 'stdio'">
            <div class="form-group">
              <label class="form-label">{{ t('mcp.fields.command') }} *</label>
              <input v-model="form.command" class="form-input" :placeholder="t('mcp.placeholders.command')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('mcp.fields.cwd') }}</label>
              <input v-model="form.cwd" class="form-input" :placeholder="t('mcp.placeholders.cwd')" />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('mcp.fields.args') }}</label>
              <input
                v-model="form.argsJson"
                class="form-input mono"
                placeholder='["-y", "@modelcontextprotocol/server-xxx"]'
              />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('mcp.fields.env') }}</label>
              <McpKvEditor
                v-model="form.envJson"
                :key-placeholder="t('mcp.kv.envKey')"
                :value-placeholder="t('mcp.kv.envValue')"
                :add-label="t('mcp.kv.addEnv')"
                :credential-keys="catalogSource?.credentialKeys"
              />
            </div>
          </template>
          <template v-else>
            <div class="form-group full-width">
              <label class="form-label">{{ t('mcp.fields.url') }} *</label>
              <input
                v-model="form.url"
                class="form-input mono"
                :placeholder="t('mcp.placeholders.url')"
              />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('mcp.fields.headers') }}</label>
              <McpKvEditor
                v-model="form.headersJson"
                :key-placeholder="t('mcp.kv.headerKey')"
                :value-placeholder="t('mcp.kv.headerValue')"
                :add-label="t('mcp.kv.addHeader')"
                :credential-keys="catalogSource?.credentialKeys"
              />
            </div>
          </template>

          <div class="form-group">
            <label class="form-label">{{ t('mcp.fields.connectTimeout') }}</label>
            <input
              v-model.number="form.connectTimeoutSeconds"
              type="number"
              class="form-input"
              min="5"
              max="300"
            />
          </div>
          <div class="form-group">
            <label class="form-label">{{ t('mcp.fields.readTimeout') }}</label>
            <input
              v-model.number="form.readTimeoutSeconds"
              type="number"
              class="form-input"
              min="5"
              max="300"
            />
          </div>
          <div class="form-group full-width">
            <label class="toggle-inline">
              <input type="checkbox" v-model="form.enabled" />
              {{ t('mcp.fields.enabled') }}
            </label>
          </div>
        </div>
      </div>

      <div class="modal-footer">
        <button
          v-if="editing"
          class="btn-danger"
          @click="onDelete"
        >
          {{ t('common.delete') }}
        </button>
        <div class="footer-spacer" />
        <button class="btn-secondary" @click="close">{{ t('common.cancel') }}</button>
        <button class="btn-primary" :disabled="!canSave" @click="onSave">
          {{ t('common.save') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { emptyMcpForm, type McpServer, type McpServerForm } from './types'
import type { McpCatalogEntry } from './catalog'
import McpKvEditor from './McpKvEditor.vue'

const props = defineProps<{
  modelValue: boolean
  editing?: McpServer | null
  prefill?: McpCatalogEntry | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'save', form: McpServerForm, editing: McpServer | null): void
  (e: 'delete', server: McpServer): void
}>()

const { t } = useI18n()

const form = reactive<McpServerForm>(emptyMcpForm())
// Capture the catalog source for the header badge — even if the user
// changes transport / fields, the badge keeps showing where it came from
// until the modal closes.
const catalogSource = ref<McpCatalogEntry | null>(null)

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    if (props.editing) {
      hydrateFromServer(props.editing)
      catalogSource.value = null
    } else if (props.prefill) {
      hydrateFromCatalog(props.prefill)
      catalogSource.value = props.prefill
    } else {
      Object.assign(form, emptyMcpForm())
      catalogSource.value = null
    }
  },
  { immediate: true },
)

function hydrateFromServer(s: McpServer) {
  Object.assign(form, {
    name: s.name,
    description: s.description || '',
    transport: s.transport,
    url: s.url || '',
    headersJson: s.headersJson || '',
    command: s.command || '',
    argsJson: s.argsJson || '',
    envJson: s.envJson || '',
    cwd: s.cwd || '',
    connectTimeoutSeconds: s.connectTimeoutSeconds || 30,
    readTimeoutSeconds: s.readTimeoutSeconds || 60,
    enabled: s.enabled,
  })
}

function hydrateFromCatalog(entry: McpCatalogEntry) {
  const fresh = emptyMcpForm()
  fresh.name = entry.key
  fresh.description = entry.description
  fresh.transport = entry.config.transport
  if (entry.config.transport === 'stdio') {
    fresh.command = entry.config.command
    fresh.argsJson = entry.config.argsJson || ''
    fresh.envJson = entry.config.envJson || ''
  } else {
    fresh.url = entry.config.url
    fresh.headersJson = entry.config.headersJson || ''
  }
  Object.assign(form, fresh)
}

const headerTitle = computed(() =>
  props.editing ? t('mcp.modal.editTitle') : t('mcp.modal.newTitle'),
)
const catalogBadge = computed(() => {
  const src = catalogSource.value
  return src ? t('mcp.catalogBadge', { name: src.name }) : ''
})

/**
 * Surface catalog credential expectations in two buckets so the user can
 * tell at a glance which keys MUST be filled (server won't auth otherwise)
 * vs which keys are optional capability unlocks. Single-bucket catalogs
 * fall back to the simpler "required only" / "optional only" phrasings.
 */
const credentialHint = computed(() => {
  const src = catalogSource.value
  if (!src || !src.credentialKeys || src.credentialKeys.length === 0) return ''
  const required = src.credentialKeys.filter(k => k.required).map(k => k.key)
  const optional = src.credentialKeys.filter(k => !k.required).map(k => k.key)
  if (required.length && optional.length) {
    return t('mcp.credentialHintBoth', {
      required: required.join(', '),
      optional: optional.join(', '),
    })
  }
  if (required.length) {
    return t('mcp.credentialHintRequired', { keys: required.join(', ') })
  }
  return t('mcp.credentialHintOptional', { keys: optional.join(', ') })
})

const canSave = computed(() => {
  if (!form.name) return false
  if (form.transport === 'stdio' && !form.command) return false
  if (form.transport !== 'stdio' && !form.url) return false
  return true
})

function close() { emit('update:modelValue', false) }

function onSave() {
  if (!canSave.value) return
  emit('save', { ...form }, props.editing ?? null)
}

function onDelete() {
  if (props.editing) emit('delete', props.editing)
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}
.modal {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  width: 100%;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: var(--mc-shadow-medium);
}
.modal-wide { max-width: 580px; }
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px;
  border-bottom: 1px solid var(--mc-border-light);
  gap: 12px;
}
.modal-header h2 {
  font-size: 17px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0;
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.catalog-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.modal-close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  font-size: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
}
.modal-close:hover { background: var(--mc-bg-sunken); }
.modal-body { flex: 1; overflow-y: auto; padding: 18px 22px; }

.credential-hint {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0 0 14px;
  padding: 9px 12px;
  background: rgba(252, 211, 77, 0.14);
  border: 1px solid rgba(252, 211, 77, 0.35);
  color: var(--mc-text-primary);
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.5;
}
.credential-hint svg { flex-shrink: 0; color: #d97706; margin-top: 1px; }

.modal-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 22px;
  border-top: 1px solid var(--mc-border-light);
}
.footer-spacer { flex: 1; }

.btn-primary {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 16px;
  background: var(--mc-primary);
  color: #fff;
  border: none;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }
.btn-secondary {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 16px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  font-size: 14px;
  cursor: pointer;
}
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-danger {
  padding: 9px 14px;
  background: transparent;
  color: var(--mc-danger, #ef4444);
  border: 1px solid var(--mc-danger, #ef4444);
  border-radius: 10px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.btn-danger:hover { background: rgba(239, 68, 68, 0.08); }

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.form-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-group.full-width { grid-column: 1 / -1; }
.form-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-secondary);
}
.form-input {
  padding: 8px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  font-size: 14px;
  color: var(--mc-text-primary);
  outline: none;
  background: var(--mc-bg-sunken);
  width: 100%;
}
.form-input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 2px var(--mc-primary-bg);
}
.form-textarea {
  resize: vertical;
  min-height: 40px;
  font-family: 'SF Mono', 'Fira Code', monospace;
}
.mono {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 13px;
}
.toggle-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--mc-text-primary);
  cursor: pointer;
}
.toggle-inline input {
  width: 16px;
  height: 16px;
  accent-color: var(--mc-primary);
}

@media (max-width: 640px) {
  .form-grid { grid-template-columns: 1fr; }
}
</style>
