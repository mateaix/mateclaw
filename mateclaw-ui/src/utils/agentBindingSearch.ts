type SearchableRecord = Record<string, unknown>

export interface AgentToolGroup<T extends SearchableRecord = SearchableRecord> {
  groupId: string
  label: string
  tools: T[]
}

function normalizeQuery(query: string): string {
  return query.trim().toLowerCase()
}

function includesQuery(value: unknown, query: string): boolean {
  if (value === null || value === undefined) return false
  return String(value).toLowerCase().includes(query)
}

export function filterAgentBindingItems<T extends SearchableRecord>(items: T[], query: string): T[] {
  const q = normalizeQuery(query)
  if (!q) return items

  return items.filter((item) => (
    includesQuery(item.name, q) ||
    includesQuery(item.rawName, q) ||
    includesQuery(item.description, q) ||
    includesQuery(item.version, q) ||
    includesQuery(item.source, q) ||
    includesQuery(item.group, q) ||
    includesQuery(item.providerName, q)
  ))
}

export function filterAgentToolGroups<T extends SearchableRecord>(
  groups: Array<AgentToolGroup<T>>,
  query: string,
): Array<AgentToolGroup<T>> {
  const q = normalizeQuery(query)
  if (!q) return groups

  return groups
    .map((group) => {
      const groupMatches = includesQuery(group.label, q) || includesQuery(group.groupId, q)
      const tools = groupMatches ? group.tools : filterAgentBindingItems(group.tools, q)
      return { ...group, tools }
    })
    .filter((group) => group.tools.length > 0)
}
