/**
 * Static MCP brand-icon registry. SVGs are bundled at build time via Vite's
 * glob import + `?raw` query, so each icon ships as an inline string and
 * can be color-themed with `currentColor` (no external network requests,
 * no per-icon component file).
 *
 * Sources:
 *   - Brand glyphs adopted from the Simple Icons collection (CC0 1.0).
 *   - Three-letter convention for filenames: lowercase + underscore.
 *     The catalog entry's `iconKey` must match the basename.
 */

/* eslint-disable @typescript-eslint/no-explicit-any */
const rawSvgs = import.meta.glob('@/assets/icons/mcp/*.svg', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function basename(path: string): string {
  return path.split('/').pop()!.replace(/\.\w+$/, '')
}

const ICON_BY_KEY = new Map<string, string>(
  Object.entries(rawSvgs).map(([path, raw]) => [basename(path), raw]),
)

/**
 * Look up an inline SVG by icon key. Returns the raw SVG markup ready
 * for v-html, with width/height stripped and `currentColor` injected so
 * the consumer can size it via CSS and tint it with `color`.
 */
export function getMcpIconSvg(iconKey: string | undefined): string | null {
  if (!iconKey) return null
  const raw = ICON_BY_KEY.get(iconKey)
  return raw ? prepareSvg(raw) : null
}

function prepareSvg(svg: string): string {
  return svg
    // Strip fixed sizing so the consumer's container governs the icon size.
    .replace(/\bwidth="[^"]*"/g, '')
    .replace(/\bheight="[^"]*"/g, '')
    // Drop any embedded <style> blocks that might re-introduce sizing.
    .replace(/<style>[\s\S]*?<\/style>/g, '')
    // Tint via currentColor so the parent's `color` cascades into the glyph.
    .replace('<svg ', '<svg fill="currentColor" preserveAspectRatio="xMidYMid meet" ')
}
