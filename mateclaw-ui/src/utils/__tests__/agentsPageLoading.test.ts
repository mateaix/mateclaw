import { describe, expect, it } from 'vitest'
import { planAgentsPageLoads, shouldShowAgentsLiveBadge } from '../agentsPageLoading'

describe('planAgentsPageLoads', () => {
  it('keeps the live view free of roster and duplicate badge requests', () => {
    expect(planAgentsPageLoads({ view: 'live', isAdmin: true })).toEqual({
      loadRoster: false,
      loadAgentFormLookups: false,
      pollLiveBadge: false,
    })
  })

  it('loads roster data and live badge outside the live view for admins', () => {
    expect(planAgentsPageLoads({ view: 'roster', isAdmin: true })).toEqual({
      loadRoster: true,
      loadAgentFormLookups: true,
      pollLiveBadge: true,
    })
  })

  it('does not poll the admin live badge for ordinary users', () => {
    expect(planAgentsPageLoads({ view: 'roster', isAdmin: false })).toEqual({
      loadRoster: true,
      loadAgentFormLookups: true,
      pollLiveBadge: false,
    })
  })

  it('shows the live badge only as an entry hint outside the live view', () => {
    expect(shouldShowAgentsLiveBadge({ view: 'live', running: 5 })).toBe(false)
    expect(shouldShowAgentsLiveBadge({ view: 'roster', running: 5 })).toBe(true)
    expect(shouldShowAgentsLiveBadge({ view: 'plans', running: 0 })).toBe(false)
  })
})
