const DEFAULT_RECENT_ID_LIMIT = 2_048

/** Compare positive decimal SSE ids without losing 64-bit precision. */
export function isHigherSseEventId(candidate: string, current?: string | null): boolean {
  if (current == null) return true
  if (!/^\d+$/.test(candidate) || !/^\d+$/.test(current)) return false
  const normalizedCandidate = candidate.replace(/^0+(?=\d)/, '')
  const normalizedCurrent = current.replace(/^0+(?=\d)/, '')
  if (normalizedCandidate.length !== normalizedCurrent.length) {
    return normalizedCandidate.length > normalizedCurrent.length
  }
  return normalizedCandidate > normalizedCurrent
}

/** Fixed-size FIFO membership window for replay de-duplication. */
export class RecentSseEventIds {
  private readonly ids = new Set<string>()
  private readonly order: string[] = []
  private readonly limit: number

  constructor(limit = DEFAULT_RECENT_ID_LIMIT) {
    this.limit = Math.max(1, limit)
  }

  has(id: string): boolean {
    return this.ids.has(id)
  }

  add(id: string): void {
    if (this.ids.has(id)) return
    this.ids.add(id)
    this.order.push(id)
    if (this.order.length > this.limit) {
      this.ids.delete(this.order.shift()!)
    }
  }

  clear(): void {
    this.ids.clear()
    this.order.length = 0
  }
}
