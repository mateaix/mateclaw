import { describe, it, expect } from 'vitest'
import { parseTeamBriefing } from '../teamBriefing'
describe('saved team briefing', () => {
  it('reads only valid versioned bounded string fields', () => {
    const briefing = {
      schemaVersion: 1,
      objective: 'Goal',
      description: 'Details',
      acceptanceCriteria: 'Done',
      constraints: 'Budget',
    }
    expect(parseTeamBriefing(JSON.stringify({ briefing }))).toEqual(briefing)
    for (const b of [
      { ...briefing, schemaVersion: 2 },
      { ...briefing, objective: 3 },
      { ...briefing, constraints: 'x'.repeat(4001) },
    ])
      expect(parseTeamBriefing(JSON.stringify({ briefing: b }))).toBeNull()
    expect(parseTeamBriefing('{}')).toBeNull()
    expect(parseTeamBriefing('bad')).toBeNull()
  })
})
it('normalizes omitted and null optional snapshot fields', () => {
  expect(
    parseTeamBriefing(
      '{"briefing":{"schemaVersion":1,"description":"task","constraints":null}}',
    ),
  ).toEqual({
    schemaVersion: 1,
    objective: '',
    description: 'task',
    acceptanceCriteria: '',
    constraints: '',
  })
  expect(parseTeamBriefing('{"briefing":{"schemaVersion":1}}')?.objective).toBe(
    '',
  )
})
