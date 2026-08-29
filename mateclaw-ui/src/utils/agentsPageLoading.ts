export type AgentsPageView = 'roster' | 'live' | 'plans'

export function planAgentsPageLoads(options: {
  view: AgentsPageView
  isAdmin: boolean
}): {
  loadRoster: boolean
  loadAgentFormLookups: boolean
  pollLiveBadge: boolean
} {
  const isLiveView = options.view === 'live'
  return {
    loadRoster: !isLiveView,
    loadAgentFormLookups: !isLiveView,
    pollLiveBadge: options.isAdmin && !isLiveView,
  }
}

export function shouldShowAgentsLiveBadge(options: {
  view: AgentsPageView
  running: number
}): boolean {
  return options.view !== 'live' && options.running > 0
}
