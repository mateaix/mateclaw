<template>
  <div class="kv-editor">
    <div
      v-for="(pair, idx) in pairs"
      :key="idx"
      class="kv-row"
      :class="{ 'kv-row--credential': isCredentialKey(pair.key) }"
    >
      <div class="kv-key-wrap">
        <input
          v-model="pair.key"
          class="kv-input kv-input--key"
          :placeholder="keyPlaceholder || 'KEY'"
          autocomplete="off"
          spellcheck="false"
          @input="emitChange"
        />
        <span
          v-if="isCredentialKey(pair.key)"
          class="kv-credential-badge"
          :class="isRequiredCredential(pair.key) ? 'kv-credential-badge--required' : 'kv-credential-badge--optional'"
        >
          {{ isRequiredCredential(pair.key) ? t('mcp.kv.required') : t('mcp.kv.optional') }}
        </span>
      </div>
      <input
        v-model="pair.value"
        class="kv-input kv-input--value"
        :class="{ 'kv-input--placeholder': isPlaceholderValue(pair.value) }"
        :placeholder="valuePlaceholder || 'value'"
        autocomplete="off"
        spellcheck="false"
        @input="emitChange"
      />
      <button
        type="button"
        class="kv-remove"
        :title="t('common.delete')"
        :aria-label="t('common.delete')"
        @click="removeRow(idx)"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="6" y1="6" x2="18" y2="18" />
          <line x1="6" y1="18" x2="18" y2="6" />
        </svg>
      </button>
    </div>
    <button type="button" class="kv-add" @click="addRow">
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
        <line x1="12" y1="5" x2="12" y2="19" />
        <line x1="5" y1="12" x2="19" y2="12" />
      </svg>
      {{ addLabel || t('mcp.kv.addEnv') }}
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  modelValue: string
  keyPlaceholder?: string
  valuePlaceholder?: string
  addLabel?: string
  /** Catalog-supplied credential keys — when a row's key matches, render
      a "required" / "optional" badge so the user knows which entries
      need real secrets. */
  credentialKeys?: ReadonlyArray<{ key: string; required: boolean }>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const { t } = useI18n()

interface Pair {
  key: string
  value: string
}

const pairs = ref<Pair[]>([{ key: '', value: '' }])

function parse(json: string): Pair[] {
  if (!json || !json.trim()) return [{ key: '', value: '' }]
  try {
    const obj = JSON.parse(json) as unknown
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
      return [{ key: '', value: '' }]
    }
    const list = Object.entries(obj as Record<string, unknown>).map(([k, v]) => ({
      key: k,
      value: v == null ? '' : String(v),
    }))
    return list.length ? list : [{ key: '', value: '' }]
  } catch {
    return [{ key: '', value: '' }]
  }
}

function serialize(list: Pair[]): string {
  const obj: Record<string, string> = {}
  for (const { key, value } of list) {
    const k = key.trim()
    if (!k) continue
    obj[k] = value
  }
  return Object.keys(obj).length === 0 ? '' : JSON.stringify(obj, null, 2)
}

watch(
  () => props.modelValue,
  (val) => {
    // Only re-parse from props if the serialized form actually differs —
    // prevents the watcher from clobbering rows the user is typing into
    // (e.g. a half-typed key that hasn't been committed to JSON yet).
    if (val !== serialize(pairs.value)) {
      pairs.value = parse(val)
    }
  },
  { immediate: true },
)

function emitChange() {
  emit('update:modelValue', serialize(pairs.value))
}

function addRow() {
  pairs.value.push({ key: '', value: '' })
}

// Catalog placeholder values look like `YOUR_API_KEY`, `YOUR_TOKEN`, etc.
// Detecting them lets the input tint amber until the user replaces the
// stub with a real secret.
const PLACEHOLDER_PATTERN = /^YOUR[_-][A-Z0-9_]+$/i

function isCredentialKey(key: string): boolean {
  const k = (key || '').trim()
  if (!k || !props.credentialKeys) return false
  return props.credentialKeys.some(c => c.key === k)
}

function isRequiredCredential(key: string): boolean {
  const k = (key || '').trim()
  if (!k || !props.credentialKeys) return false
  return props.credentialKeys.some(c => c.key === k && c.required)
}

function isPlaceholderValue(value: string): boolean {
  return PLACEHOLDER_PATTERN.test((value || '').trim())
}

function removeRow(idx: number) {
  pairs.value.splice(idx, 1)
  // Keep at least one empty row so the editor always has a typing target.
  if (pairs.value.length === 0) pairs.value.push({ key: '', value: '' })
  emitChange()
}
</script>

<style scoped>
.kv-editor { display: flex; flex-direction: column; gap: 6px; }

.kv-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr) auto;
  gap: 6px;
  align-items: center;
}

.kv-key-wrap {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
}
.kv-key-wrap .kv-input--key {
  /* Reserve room on the right for the credential badge. */
  padding-right: 60px;
}

.kv-credential-badge {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  padding: 1px 6px;
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  border-radius: 4px;
  pointer-events: none;
  white-space: nowrap;
}
.kv-credential-badge--required {
  background: rgba(217, 119, 87, 0.12);
  color: var(--mc-primary);
}
.kv-credential-badge--optional {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
}

.kv-input--placeholder {
  background: rgba(252, 211, 77, 0.08);
  border-color: rgba(252, 211, 77, 0.35);
  color: var(--mc-text-secondary);
}

.kv-input {
  padding: 7px 10px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 12.5px;
  color: var(--mc-text-primary);
  background: var(--mc-bg-sunken);
  outline: none;
  min-width: 0;
  width: 100%;
}
.kv-input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 2px var(--mc-primary-bg);
}
.kv-input::placeholder {
  color: var(--mc-text-tertiary);
  letter-spacing: 0.02em;
}
.kv-input--key::placeholder { text-transform: uppercase; }

.kv-remove {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  border-radius: 7px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.kv-remove:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }

.kv-add {
  align-self: flex-start;
  margin-top: 2px;
  padding: 5px 8px;
  background: transparent;
  border: none;
  color: var(--mc-primary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border-radius: 6px;
}
.kv-add:hover { background: var(--mc-primary-bg); }
</style>
