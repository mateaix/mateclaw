export function parseTeamBriefing(
  raw: string | null | undefined,
): {
  schemaVersion: number
  objective: string
  description: string
  acceptanceCriteria: string
  constraints: string
} | null {
  try {
    const b = JSON.parse(raw || '{}')?.briefing
    if (!b || b.schemaVersion !== 1) return null
    for (const [key, max] of Object.entries({
      objective: 4000,
      description: 12000,
      acceptanceCriteria: 4000,
      constraints: 4000,
    }))
      if (b[key] != null && (typeof b[key] !== 'string' || b[key].length > max))
        return null
    return {
      schemaVersion: 1,
      objective: b.objective ?? '',
      description: b.description ?? '',
      acceptanceCriteria: b.acceptanceCriteria ?? '',
      constraints: b.constraints ?? '',
    }
  } catch {
    return null
  }
}
