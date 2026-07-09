/**
 * Rewrites bare generated-file download URLs in assistant text into
 * `[filename](url)` markdown links, so the chat renders the file name instead
 * of the raw UUID URL.
 *
 * The backend does the same rewrite before persisting the final answer; this
 * client-side pass covers the live-streamed bubble, whose text arrived as raw
 * deltas before persistence. File names come from `metadata.generatedFiles`,
 * which the server extracts from tool results during the same turn.
 */

const GENERATED_URL_RE = /(\]\(|<)?(?:https?:\/\/[^\s)\]<>]+)?\/api\/v1\/files\/generated\/([a-zA-Z0-9-]+)/g
const GENERATED_ID_RE = /\/api\/v1\/files\/generated\/([a-zA-Z0-9-]+)/

export interface GeneratedFileRef {
  name?: string
  url?: string
}

/** Build an id → display-name map from `metadata.generatedFiles`. */
export function buildGeneratedFileNameMap(files: unknown): Map<string, string> {
  const names = new Map<string, string>()
  if (!Array.isArray(files)) return names
  for (const f of files as GeneratedFileRef[]) {
    const m = GENERATED_ID_RE.exec(String(f?.url || ''))
    if (m && f?.name) names.set(m[1], String(f.name))
  }
  return names
}

/**
 * Wrap bare generated-file URLs whose id has a known name into
 * `[name](url)`. URLs already serving as a markdown link destination
 * (preceded by `](`) or angle-bracket autolinks (`<url>`) are left as-is.
 */
export function linkifyGeneratedFileUrls(text: string, names: Map<string, string>): string {
  if (!text || !names.size || !text.includes('/api/v1/files/generated/')) return text
  return text.replace(GENERATED_URL_RE, (full, prefix: string | undefined, id: string) => {
    if (prefix) return full
    const name = names.get(id)
    if (!name) return full
    return `[${name.replace(/[[\]]/g, '')}](${full})`
  })
}
