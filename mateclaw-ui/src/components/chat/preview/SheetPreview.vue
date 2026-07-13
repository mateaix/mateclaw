<template>
  <div class="sheet-preview">
    <div v-if="error" class="sheet-preview__error">{{ $t('chat.preview.failed') }}</div>
    <PreviewSpinner v-else-if="loading" :label="$t('chat.preview.loading')" />
    <template v-else>
      <el-tabs v-if="sheets.length > 1" v-model="activeSheet" class="sheet-preview__tabs">
        <el-tab-pane
          v-for="sheet in sheets"
          :key="sheet.name"
          :label="sheet.name"
          :name="sheet.name"
        />
      </el-tabs>
      <div class="sheet-preview__table-wrap">
        <table v-if="currentSheet" class="sheet-preview__table">
          <tbody>
            <tr v-for="(row, ri) in currentSheet.rows" :key="ri">
              <td v-for="(cell, ci) in row" :key="ci">{{ cell }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="currentSheet?.truncated" class="sheet-preview__truncated">
          {{ $t('chat.preview.truncated', { max: MAX_ROWS }) }}
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import PreviewSpinner from './PreviewSpinner.vue'

const props = defineProps<{
  /** Raw .xlsx or .csv bytes (already fetched with auth). */
  data: ArrayBuffer
  /** Original filename — decides xlsx vs csv parsing. */
  filename: string
}>()

interface ParsedSheet {
  name: string
  rows: string[][]
  truncated: boolean
}

/** Hard cap so a million-row export can't freeze the dialog. */
const MAX_ROWS = 500

const loading = ref(true)
const error = ref(false)
const sheets = ref<ParsedSheet[]>([])
const activeSheet = ref('')

const currentSheet = computed(() =>
  sheets.value.find(s => s.name === activeSheet.value) || sheets.value[0] || null,
)

function cellText(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'object') {
    // exceljs rich values: dates, formulas ({result}), rich text ({richText}), hyperlinks ({text})
    const v = value as Record<string, unknown>
    if (value instanceof Date) return value.toISOString().slice(0, 10)
    if (v.result != null) return cellText(v.result)
    if (Array.isArray(v.richText)) return (v.richText as Array<{ text?: string }>).map(t => t.text || '').join('')
    if (v.text != null) return String(v.text)
    return String(value)
  }
  return String(value)
}

function parseCsv(text: string): string[][] {
  // Minimal RFC-4180 parser: quoted fields, escaped quotes, CRLF/LF rows.
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++ } else { inQuotes = false }
      } else {
        field += ch
      }
    } else if (ch === '"') {
      inQuotes = true
    } else if (ch === ',') {
      row.push(field); field = ''
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++
      row.push(field); field = ''
      rows.push(row); row = []
      if (rows.length > MAX_ROWS) return rows
    } else {
      field += ch
    }
  }
  if (field.length > 0 || row.length > 0) { row.push(field); rows.push(row) }
  return rows
}

onMounted(async () => {
  try {
    if (props.filename.toLowerCase().endsWith('.csv')) {
      const text = new TextDecoder().decode(props.data)
      const rows = parseCsv(text)
      const truncated = rows.length > MAX_ROWS
      sheets.value = [{ name: 'CSV', rows: rows.slice(0, MAX_ROWS), truncated }]
    } else {
      const ExcelJS = await import('exceljs')
      const workbook = new ExcelJS.Workbook()
      await workbook.xlsx.load(props.data)
      const parsed: ParsedSheet[] = []
      workbook.eachSheet((ws) => {
        const rows: string[][] = []
        let truncated = false
        ws.eachRow({ includeEmpty: false }, (row, rowNumber) => {
          if (rowNumber > MAX_ROWS) { truncated = true; return }
          const cells: string[] = []
          // row.values is 1-based with an empty slot 0
          const values = row.values as unknown[]
          for (let c = 1; c < values.length; c++) cells.push(cellText(values[c]))
          rows.push(cells)
        })
        parsed.push({ name: ws.name, rows, truncated })
      })
      sheets.value = parsed
    }
    activeSheet.value = sheets.value[0]?.name || ''
    loading.value = false
  } catch (e) {
    console.error('[SheetPreview] parse failed:', e)
    error.value = true
    loading.value = false
  }
})
</script>

<style scoped>
.sheet-preview {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--mc-bg-sunken, #ebe3db);
}
.sheet-preview__tabs {
  flex-shrink: 0;
  padding: 0 16px;
}
.sheet-preview__table-wrap {
  flex: 1;
  overflow: auto;
  margin: 12px 16px 16px;
  background: var(--mc-bg-elevated, #fff);
  border-radius: var(--mc-radius-md, 12px);
  box-shadow: var(--mc-shadow-soft, 0 10px 30px rgba(58, 32, 19, 0.08));
}
.sheet-preview__table {
  border-collapse: collapse;
  font-size: var(--mc-text-sm, 13px);
  width: 100%;
}
.sheet-preview__table td {
  border: 1px solid var(--mc-border-light, #ebe3db);
  padding: 5px 12px;
  white-space: nowrap;
  max-width: 400px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--mc-text-primary, #1d1612);
}
.sheet-preview__table tr:first-child td {
  font-weight: 600;
  background: var(--mc-bg-muted, #f1e8df);
  color: var(--mc-text-secondary, #665245);
  position: sticky;
  top: 0;
}
.sheet-preview__table tr:not(:first-child):hover td {
  background: var(--mc-primary-bg, #f6e2d7);
}
.sheet-preview__truncated {
  padding: 8px 16px 12px;
  color: var(--mc-text-tertiary, #9b7d6c);
  font-size: var(--mc-text-xs, 11px);
}
.sheet-preview__error {
  padding: 48px;
  text-align: center;
  color: var(--mc-text-secondary, #665245);
}
</style>
