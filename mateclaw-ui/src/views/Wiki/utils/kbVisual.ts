// Visual helpers shared between the library grid and the workspace header so
// the same KB shows the same color and initial wherever it appears.

import type { WikiKB } from '@/stores/useWikiStore'

interface PaletteEntry {
  bg: string
  fg: string
}

// Eight-color palette tuned to the warm/earthy brand. Each entry sits around
// 45-55% lightness with mid saturation so colors stay distinct but live in the
// same room as the rust-orange primary.
const KB_PALETTE: PaletteEntry[] = [
  { bg: 'hsl(20, 68%, 50%)', fg: '#fff' },   // terracotta
  { bg: 'hsl(155, 32%, 42%)', fg: '#fff' },  // sage green
  { bg: 'hsl(212, 45%, 48%)', fg: '#fff' },  // dusk blue
  { bg: 'hsl(285, 30%, 50%)', fg: '#fff' },  // mauve
  { bg: 'hsl(38, 72%, 50%)', fg: '#fff' },   // amber
  { bg: 'hsl(195, 28%, 42%)', fg: '#fff' },  // slate teal
  { bg: 'hsl(232, 38%, 52%)', fg: '#fff' },  // indigo
  { bg: 'hsl(8, 62%, 50%)', fg: '#fff' },    // rosewood
]

type KBLike = Pick<WikiKB, 'id'> & { name?: string }

// IDs are server-side snowflakes and exceed JS safe-int range, so numeric `% N`
// loses all low bits and collapses to a single bucket. Hash the string form
// instead, mixing in the name so re-opening the same KB stays color-stable.
function paletteFor(kb: KBLike): PaletteEntry {
  const seed = String(kb.id ?? '') + '|' + (kb.name || '')
  let h = 5381
  for (let i = 0; i < seed.length; i++) {
    h = ((h << 5) + h + seed.charCodeAt(i)) | 0
  }
  const idx = ((h % KB_PALETTE.length) + KB_PALETTE.length) % KB_PALETTE.length
  return KB_PALETTE[idx]
}

export function kbAccent(kb: KBLike): string {
  return paletteFor(kb).bg
}

export function kbAccentFg(kb: KBLike): string {
  return paletteFor(kb).fg
}

export function kbInitial(kb: { name?: string }): string {
  if (!kb.name) return '?'
  // Take the first visible character — works for both ASCII and CJK.
  const ch = Array.from(kb.name.trim())[0]
  return (ch || '?').toUpperCase()
}

export function relativeTime(iso: string | null | undefined, isZh: boolean): string {
  if (!iso) return ''
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return ''
  const diffMs = Date.now() - then
  const sec = Math.floor(diffMs / 1000)
  if (sec < 60) return isZh ? '刚刚' : 'just now'
  const min = Math.floor(sec / 60)
  if (min < 60) return isZh ? `${min} 分钟前` : `${min}m ago`
  const hr = Math.floor(min / 60)
  if (hr < 24) return isZh ? `${hr} 小时前` : `${hr}h ago`
  const day = Math.floor(hr / 24)
  if (day < 30) return isZh ? `${day} 天前` : `${day}d ago`
  const mon = Math.floor(day / 30)
  if (mon < 12) return isZh ? `${mon} 个月前` : `${mon}mo ago`
  const yr = Math.floor(day / 365)
  return isZh ? `${yr} 年前` : `${yr}y ago`
}
