import type { ChatAttachment } from '@/types'

/**
 * In-browser preview strategy for a chat attachment.
 *
 * - 'pdf'    — rendered client-side with pdfjs-dist
 * - 'docx'   — rendered client-side with docx-preview
 * - 'sheet'  — xlsx/csv parsed client-side with exceljs
 * - 'text'   — markdown / code / plain text, reuses the chat markdown renderer
 * - 'html'   — rendered inside a fully sandboxed iframe (no scripts, no origin)
 * - 'office' — needs server-side conversion to PDF (soffice); the frontend
 *              requests `{url}/preview` and renders the result as 'pdf'.
 *              Falls back to download when the server has no converter (501).
 */
export type PreviewKind = 'pdf' | 'docx' | 'sheet' | 'text' | 'html' | 'office'

/** Extensions rendered as markdown (full rich rendering). */
const MARKDOWN_EXTS = new Set(['md', 'markdown'])

/** Extensions rendered as syntax-highlighted code. */
const CODE_EXTS = new Set([
  'json', 'yaml', 'yml', 'toml', 'xml', 'sql', 'sh', 'bash', 'zsh',
  'js', 'ts', 'jsx', 'tsx', 'vue', 'py', 'java', 'kt', 'go', 'rs',
  'rb', 'c', 'cpp', 'h', 'cs', 'php', 'lua', 'css', 'scss', 'less',
  'properties', 'ini', 'conf', 'gradle', 'dockerfile',
])

/** Extensions rendered as plain preformatted text. */
const PLAIN_TEXT_EXTS = new Set(['txt', 'log', 'csv-report', 'text'])

/** Extensions the server-side office→PDF converter accepts. */
const OFFICE_CONVERT_EXTS = new Set([
  'ppt', 'pptx', 'doc', 'xls', 'odt', 'ods', 'odp', 'rtf', 'wps',
])

export function extensionOf(name: string | undefined): string {
  if (!name) return ''
  const idx = name.lastIndexOf('.')
  return idx >= 0 ? name.slice(idx + 1).toLowerCase() : ''
}

/** Sub-flavor for the text preview so it can pick a rendering mode. */
export function textFlavorOf(name: string | undefined): 'markdown' | 'code' | 'plain' {
  const ext = extensionOf(name)
  if (MARKDOWN_EXTS.has(ext)) return 'markdown'
  if (CODE_EXTS.has(ext)) return 'code'
  return 'plain'
}

/**
 * Decide how (and whether) an attachment can be previewed in-browser.
 * Returns null when the only sensible action is download.
 */
export function previewKindOf(attachment: Pick<ChatAttachment, 'name' | 'contentType'>): PreviewKind | null {
  const ext = extensionOf(attachment.name)
  const mime = attachment.contentType || ''

  if (ext === 'pdf' || mime === 'application/pdf') return 'pdf'
  if (ext === 'docx'
      || mime === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') {
    return 'docx'
  }
  if (ext === 'xlsx' || ext === 'csv'
      || mime === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      || mime === 'text/csv') {
    return 'sheet'
  }
  if (ext === 'html' || ext === 'htm' || mime === 'text/html') return 'html'
  if (MARKDOWN_EXTS.has(ext) || CODE_EXTS.has(ext) || PLAIN_TEXT_EXTS.has(ext)
      || mime.startsWith('text/')) {
    return 'text'
  }
  if (OFFICE_CONVERT_EXTS.has(ext)) return 'office'
  return null
}
