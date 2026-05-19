<template>
  <div class="cron-field">
    <div class="cron-field-row">
      <input
        class="cron-input mono"
        :value="modelValue"
        spellcheck="false"
        :placeholder="withSeconds ? t('cronField.placeholderSeconds') : t('cronField.placeholder')"
        @input="onRawInput"
      />
      <button type="button" class="cron-edit-btn" @click="openDialog">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="18" rx="2" /><line x1="16" y1="2" x2="16" y2="6" />
          <line x1="8" y1="2" x2="8" y2="6" /><line x1="3" y1="10" x2="21" y2="10" />
        </svg>
        {{ t('cronField.edit') }}
      </button>
    </div>
    <div v-if="describe(modelValue)" class="cron-desc">{{ describe(modelValue) }}</div>

    <Teleport to="body">
      <div v-if="dialogOpen" class="cron-dialog-overlay" @click.self="dialogOpen = false">
        <section class="cron-dialog" role="dialog" aria-modal="true">
          <header class="cron-dialog-header">
            <strong>{{ t('cronField.dialogTitle') }}</strong>
            <button type="button" class="cron-x" @click="dialogOpen = false" aria-label="close">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                   stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
            </button>
          </header>

          <div class="cron-dialog-body">
            <!-- Presets -->
            <div class="cron-presets">
              <span class="cron-presets-label">{{ t('cronField.presets') }}</span>
              <div class="cron-presets-chips">
                <button
                  v-for="p in presets"
                  :key="p.key"
                  type="button"
                  class="cron-preset-chip"
                  @click="applyPreset(p.cron)"
                >{{ t('cronField.presetItems.' + p.key) }}</button>
              </div>
            </div>

            <!-- Field tabs -->
            <nav class="cron-tabs" role="tablist">
              <button
                v-for="(f, idx) in fields"
                :key="f.key"
                type="button"
                role="tab"
                class="cron-tab"
                :class="{ active: activeIdx === idx }"
                @click="activeIdx = idx"
              >
                <span class="cron-tab-name">{{ t('cronField.tabs.' + f.key) }}</span>
                <span class="cron-tab-val mono">{{ segToStr(draft[f.key], f) }}</span>
              </button>
            </nav>

            <!-- Active field editor -->
            <div class="cron-editor">
              <div class="cron-type-row">
                <label
                  v-for="ty in TYPES"
                  :key="ty"
                  class="cron-type-opt"
                  :class="{ active: draft[activeField.key].type === ty }"
                >
                  <input type="radio" :value="ty" v-model="draft[activeField.key].type" />
                  {{ t('cronField.type.' + ty) }}
                </label>
              </div>

              <div class="cron-type-body">
                <p v-if="draft[activeField.key].type === 'every'" class="cron-line cron-muted">
                  {{ t('cronField.everyDesc', { unit: t('cronField.unit.' + activeField.key) }) }}
                </p>

                <div v-else-if="draft[activeField.key].type === 'range'" class="cron-line">
                  <input
                    type="number" class="cron-num"
                    :min="activeField.min" :max="activeField.max"
                    v-model.number="draft[activeField.key].rangeStart"
                  />
                  <span class="cron-sep">{{ t('cronField.rangeSep') }}</span>
                  <input
                    type="number" class="cron-num"
                    :min="activeField.min" :max="activeField.max"
                    v-model.number="draft[activeField.key].rangeEnd"
                  />
                </div>

                <div v-else-if="draft[activeField.key].type === 'interval'" class="cron-line">
                  <span>{{ t('cronField.intervalStart') }}</span>
                  <input
                    type="number" class="cron-num"
                    :min="activeField.min" :max="activeField.max"
                    v-model.number="draft[activeField.key].intervalStart"
                  />
                  <span>{{ t('cronField.intervalEvery') }}</span>
                  <input
                    type="number" class="cron-num" :min="1" :max="activeField.max"
                    v-model.number="draft[activeField.key].intervalStep"
                  />
                  <span>{{ t('cronField.unit.' + activeField.key) }}{{ t('cronField.intervalExec') }}</span>
                </div>

                <div v-else class="cron-specific">
                  <p v-if="!draft[activeField.key].specific.length" class="cron-muted">
                    {{ t('cronField.specificEmpty') }}
                  </p>
                  <div class="cron-chip-grid">
                    <button
                      v-for="opt in activeField.options"
                      :key="opt.value"
                      type="button"
                      class="cron-val-chip"
                      :class="{ active: draft[activeField.key].specific.includes(opt.value) }"
                      @click="toggleSpecific(activeField.key, opt.value)"
                    >{{ opt.label }}</button>
                  </div>
                </div>
              </div>
            </div>

            <!-- Live preview -->
            <div class="cron-preview">
              <div class="cron-preview-expr mono">{{ draftExpr }}</div>
              <div v-if="describe(draftExpr)" class="cron-preview-desc">{{ describe(draftExpr) }}</div>
            </div>
          </div>

          <footer class="cron-dialog-footer">
            <button type="button" class="cron-btn-ghost" @click="dialogOpen = false">
              {{ t('cronField.cancel') }}
            </button>
            <button type="button" class="cron-btn-primary" @click="confirm">
              {{ t('cronField.confirm') }}
            </button>
          </footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'

interface Props {
  /** The cron expression — 5-field (min hour dom mon dow) or 6-field with seconds. */
  modelValue: string
  /** When true the editor adds a leading seconds field and emits a 6-field cron. */
  withSeconds?: boolean
}
const props = withDefaults(defineProps<Props>(), { withSeconds: false })
const emit = defineEmits<{ 'update:modelValue': [string] }>()

const { t } = useI18n()

type SegType = 'every' | 'range' | 'interval' | 'specific'
const TYPES: SegType[] = ['every', 'range', 'interval', 'specific']

interface Segment {
  type: SegType
  rangeStart: number
  rangeEnd: number
  intervalStart: number
  intervalStep: number
  specific: number[]
}

interface FieldDef {
  key: 'second' | 'minute' | 'hour' | 'day' | 'month' | 'week'
  min: number
  max: number
  options: { value: number; label: string }[]
}

// Day-of-week chip labels (0 = Sunday) read from i18n.
function weekLabel(n: number): string {
  return t('cronField.weekShort.' + n)
}

function range(min: number, max: number): number[] {
  const out: number[] = []
  for (let i = min; i <= max; i++) out.push(i)
  return out
}

const ALL_FIELDS = computed<FieldDef[]>(() => {
  const numOpts = (min: number, max: number) =>
    range(min, max).map((v) => ({ value: v, label: String(v) }))
  return [
    { key: 'second', min: 0, max: 59, options: numOpts(0, 59) },
    { key: 'minute', min: 0, max: 59, options: numOpts(0, 59) },
    { key: 'hour', min: 0, max: 23, options: numOpts(0, 23) },
    { key: 'day', min: 1, max: 31, options: numOpts(1, 31) },
    { key: 'month', min: 1, max: 12, options: numOpts(1, 12) },
    { key: 'week', min: 0, max: 6, options: range(0, 6).map((v) => ({ value: v, label: weekLabel(v) })) },
  ]
})

// Visible fields — seconds only when the parent asks for a 6-field cron.
const fields = computed<FieldDef[]>(() =>
  props.withSeconds ? ALL_FIELDS.value : ALL_FIELDS.value.filter((f) => f.key !== 'second'),
)

function blankSegment(field: FieldDef): Segment {
  return {
    type: 'every',
    rangeStart: field.min,
    rangeEnd: field.max,
    intervalStart: field.min,
    intervalStep: 1,
    specific: [],
  }
}

// Draft segments keyed by field — always holds all six so toggling
// withSeconds (per-usage, not runtime) never leaves a hole.
const draft = reactive<Record<string, Segment>>({})
ALL_FIELDS.value.forEach((f) => {
  draft[f.key] = blankSegment(f)
})

const dialogOpen = ref(false)
const activeIdx = ref(0)
const activeField = computed(() => fields.value[activeIdx.value] ?? fields.value[0])

// ── Segment <-> string ──

function clamp(n: number, min: number, max: number): number {
  if (Number.isNaN(n)) return min
  return Math.min(Math.max(Math.round(n), min), max)
}

function segToStr(seg: Segment, field: FieldDef): string {
  if (!seg) return '*'
  switch (seg.type) {
    case 'range':
      return `${clamp(seg.rangeStart, field.min, field.max)}-${clamp(seg.rangeEnd, field.min, field.max)}`
    case 'interval': {
      const start = clamp(seg.intervalStart, field.min, field.max)
      const step = clamp(seg.intervalStep, 1, field.max)
      // `*/n` covers the common "every n" case; a non-min start needs an
      // explicit range so Spring's CronExpression accepts the step.
      return start <= field.min ? `*/${step}` : `${start}-${field.max}/${step}`
    }
    case 'specific':
      return seg.specific.length
        ? [...seg.specific].sort((a, b) => a - b).join(',')
        : '*'
    default:
      return '*'
  }
}

function strToSeg(raw: string, field: FieldDef): Segment {
  const seg = blankSegment(field)
  const str = (raw || '').trim()
  if (!str || str === '*' || str === '?') return seg
  if (str.includes('/')) {
    const [base, stepRaw] = str.split('/')
    seg.type = 'interval'
    seg.intervalStep = clamp(parseInt(stepRaw, 10), 1, field.max)
    if (base === '*' || base === '') {
      seg.intervalStart = field.min
    } else if (base.includes('-')) {
      seg.intervalStart = clamp(parseInt(base.split('-')[0], 10), field.min, field.max)
    } else {
      seg.intervalStart = clamp(parseInt(base, 10), field.min, field.max)
    }
    return seg
  }
  if (str.includes('-')) {
    const [a, b] = str.split('-')
    seg.type = 'range'
    seg.rangeStart = clamp(parseInt(a, 10), field.min, field.max)
    seg.rangeEnd = clamp(parseInt(b, 10), field.min, field.max)
    return seg
  }
  if (/^\d+(,\d+)*$/.test(str)) {
    seg.type = 'specific'
    seg.specific = str
      .split(',')
      .map((v) => clamp(parseInt(v, 10), field.min, field.max))
      .filter((v, i, arr) => arr.indexOf(v) === i)
    return seg
  }
  return seg
}

function assemble(): string {
  return fields.value.map((f) => segToStr(draft[f.key], f)).join(' ')
}

const draftExpr = computed(() => assemble())

function toggleSpecific(key: string, value: number) {
  const list = draft[key].specific
  const idx = list.indexOf(value)
  if (idx >= 0) list.splice(idx, 1)
  else list.push(value)
}

// ── Dialog lifecycle ──

function openDialog() {
  const parts = (props.modelValue || '').trim().split(/\s+/).filter(Boolean)
  // Accept a value that has the other arity by padding / trimming the
  // seconds field, so editing never silently discards the expression.
  let core = parts
  if (props.withSeconds && parts.length === 5) core = ['0', ...parts]
  else if (!props.withSeconds && parts.length === 6) core = parts.slice(1)
  if (core.length === fields.value.length) {
    fields.value.forEach((f, i) => {
      draft[f.key] = strToSeg(core[i], f)
    })
  } else {
    fields.value.forEach((f) => {
      draft[f.key] = blankSegment(f)
    })
  }
  activeIdx.value = 0
  dialogOpen.value = true
}

function applyPreset(cron: string) {
  const parts = cron.trim().split(/\s+/)
  fields.value.forEach((f, i) => {
    draft[f.key] = strToSeg(parts[i] ?? '*', f)
  })
}

function confirm() {
  emit('update:modelValue', assemble())
  dialogOpen.value = false
}

function onRawInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLInputElement).value)
}

// ── Presets ──

const PRESET_DEFS = [
  { key: 'everyMinute', cron5: '* * * * *' },
  { key: 'every5Min', cron5: '*/5 * * * *' },
  { key: 'every30Min', cron5: '*/30 * * * *' },
  { key: 'hourly', cron5: '0 * * * *' },
  { key: 'daily0', cron5: '0 0 * * *' },
  { key: 'daily9', cron5: '0 9 * * *' },
  { key: 'weekday9', cron5: '0 9 * * 1-5' },
  { key: 'weekly1', cron5: '0 9 * * 1' },
  { key: 'monthly1', cron5: '0 0 1 * *' },
]
const presets = computed(() =>
  PRESET_DEFS.map((p) => ({
    key: p.key,
    cron: props.withSeconds ? `0 ${p.cron5}` : p.cron5,
  })),
)

// ── Human-readable description ──

function pad(n: string | number): string {
  const s = String(n)
  return s.length < 2 ? '0' + s : s
}

function describeDow(dow: string): string {
  const fmt = (n: string) => weekLabel(Number(n) % 7)
  if (/^\d+-\d+$/.test(dow)) {
    const [a, b] = dow.split('-')
    return `${fmt(a)}-${fmt(b)}`
  }
  if (/^\d+(,\d+)+$/.test(dow)) {
    return dow.split(',').map(fmt).join('/')
  }
  if (/^\d+$/.test(dow)) return fmt(dow)
  return dow
}

/** Best-effort plain-language summary; returns '' for shapes it can't phrase. */
function describe(expr: string): string {
  if (!expr || !expr.trim()) return ''
  const parts = expr.trim().split(/\s+/)
  let core: string[]
  if (parts.length === 6) core = parts.slice(1)
  else if (parts.length === 5) core = parts
  else return ''
  const [mi, ho, dom, mon, dow] = core
  if (core.every((p) => p === '*')) return t('cronField.desc.everyMinute')
  if (/^\*\/\d+$/.test(mi) && ho === '*' && dom === '*' && mon === '*' && dow === '*') {
    return t('cronField.desc.everyNMin', { n: mi.slice(2) })
  }
  if (mi === '0' && /^\*\/\d+$/.test(ho) && dom === '*' && mon === '*' && dow === '*') {
    return t('cronField.desc.everyNHour', { n: ho.slice(2) })
  }
  if (/^\d+$/.test(mi) && ho === '*' && dom === '*' && mon === '*' && dow === '*') {
    return t('cronField.desc.hourlyAt', { m: mi })
  }
  if (/^\d+$/.test(mi) && /^\d+$/.test(ho)) {
    const time = `${pad(ho)}:${pad(mi)}`
    if (dom === '*' && mon === '*' && dow === '*') {
      return t('cronField.desc.dailyAt', { time })
    }
    if (dom === '*' && mon === '*' && dow !== '*') {
      return t('cronField.desc.weeklyAt', { days: describeDow(dow), time })
    }
    if (/^\d+$/.test(dom) && mon === '*' && dow === '*') {
      return t('cronField.desc.monthlyAt', { day: dom, time })
    }
  }
  return ''
}
</script>

<style scoped>
.cron-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.cron-field-row {
  display: flex;
  gap: 8px;
}
.cron-input {
  flex: 1 1 auto;
  min-width: 0;
  padding: 8px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  font-size: 14px;
  outline: none;
}
.cron-input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1);
}
.cron-input.mono,
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.cron-edit-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding: 8px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}
.cron-edit-btn:hover {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}
.cron-desc {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  line-height: 1.4;
}

/* ── Dialog ── */
.cron-dialog-overlay {
  position: fixed;
  inset: 0;
  z-index: 2600;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(17, 12, 8, 0.46);
  backdrop-filter: blur(6px);
}
.cron-dialog {
  width: min(560px, calc(100vw - 40px));
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.3);
}
.cron-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--mc-border-light);
}
.cron-dialog-header strong {
  font-size: 15px;
  font-weight: 700;
}
.cron-x {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 7px;
  background: none;
  color: var(--mc-text-tertiary);
  cursor: pointer;
}
.cron-x:hover {
  background: var(--mc-bg-sunken);
}
.cron-dialog-body {
  padding: 16px 18px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.cron-presets {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.cron-presets-label,
.cron-tab-name {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--mc-text-tertiary);
}
.cron-presets-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.cron-preset-chip {
  padding: 4px 10px;
  border: 1px solid var(--mc-border);
  border-radius: 999px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.12s;
}
.cron-preset-chip:hover {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}

.cron-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--mc-border);
}
.cron-tab {
  flex: 1 1 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 8px 4px;
  border: none;
  background: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  cursor: pointer;
}
.cron-tab-val {
  display: block;
  width: 100%;
  padding: 3px 4px;
  border-radius: 5px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  font-size: 11px;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cron-tab.active .cron-tab-name {
  color: var(--mc-primary);
}
.cron-tab.active .cron-tab-val {
  background: var(--mc-primary);
  color: #fff;
}
.cron-tab.active {
  border-bottom-color: var(--mc-primary);
}

.cron-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.cron-type-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.cron-type-opt {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  font-size: 13px;
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: all 0.12s;
}
.cron-type-opt:hover {
  border-color: var(--mc-primary);
}
.cron-type-opt.active {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}
.cron-type-opt input {
  display: none;
}
.cron-type-body {
  min-height: 40px;
}
.cron-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  color: var(--mc-text-secondary);
}
.cron-muted {
  font-size: 13px;
  color: var(--mc-text-tertiary);
  margin: 0;
}
.cron-sep {
  color: var(--mc-text-tertiary);
}
.cron-num {
  width: 68px;
  padding: 6px 8px;
  border: 1px solid var(--mc-border);
  border-radius: 7px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  font-size: 13px;
  outline: none;
}
.cron-num:focus {
  border-color: var(--mc-primary);
}
.cron-specific {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cron-chip-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.cron-val-chip {
  min-width: 30px;
  padding: 4px 6px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.1s;
}
.cron-val-chip:hover {
  border-color: var(--mc-primary);
}
.cron-val-chip.active {
  border-color: var(--mc-primary);
  background: var(--mc-primary);
  color: #fff;
}

.cron-preview {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-sunken);
}
.cron-preview-expr {
  font-size: 14px;
  font-weight: 700;
  color: var(--mc-primary);
  word-break: break-all;
}
.cron-preview-desc {
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

.cron-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 18px;
  border-top: 1px solid var(--mc-border-light);
}
.cron-btn-ghost,
.cron-btn-primary {
  padding: 8px 18px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.cron-btn-ghost {
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
}
.cron-btn-ghost:hover {
  background: var(--mc-bg-sunken);
}
.cron-btn-primary {
  border: 1px solid var(--mc-primary);
  background: var(--mc-primary);
  color: #fff;
}
.cron-btn-primary:hover {
  background: var(--mc-primary-hover);
}
</style>
