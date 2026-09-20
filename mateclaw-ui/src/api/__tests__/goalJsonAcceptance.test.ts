import { afterEach, expect, it, vi } from 'vitest'
import { http } from '@/api/index'
import { goalJsonAcceptanceApi } from '@/api/goalJsonAcceptance'
afterEach(() => vi.restoreAllMocks())
it('keeps goal IDs and optimistic revisions as exact strings', () => {
  const get = vi.spyOn(http, 'get').mockResolvedValue({} as never)
  const put = vi.spyOn(http, 'put').mockResolvedValue({} as never)
  const goal = '9223372036854775801'
  const data = { expectedRevision: '9223372036854775802', artifactSlot: 'report', requiredFields: ['summary'] }
  goalJsonAcceptanceApi.get(goal); goalJsonAcceptanceApi.configure(goal, 'report-fields', data)
  expect(get).toHaveBeenCalledWith(`/goals/${goal}/json-acceptance`)
  expect(put).toHaveBeenCalledWith(`/goals/${goal}/json-acceptance/requirements/report-fields`, data)
})
it('preserves artifact identity and exact generation strings across the managed API', () => {
  const get = vi.spyOn(http, 'get').mockResolvedValue({} as never)
  const post = vi.spyOn(http, 'post').mockResolvedValue({} as never)
  const goal = '9223372036854775801', generation = '9223372036854775802'
  const check = { expectedRequirementRevision: '9223372036854775803', artifactId: 'artifact-id', expectedGeneration: generation }
  goalJsonAcceptanceApi.snapshot(goal)
  goalJsonAcceptanceApi.publish(goal, 'report', { expectedGeneration: generation, jsonContent: '{}' })
  goalJsonAcceptanceApi.check(goal, 'report-fields', check)
  goalJsonAcceptanceApi.version(goal, 'artifact-id')
  expect(get).toHaveBeenCalledWith(`/goals/${goal}/json-acceptance/snapshot`)
  expect(get).toHaveBeenCalledWith(`/goals/${goal}/json-acceptance/artifacts/versions/artifact-id`)
  expect(post).toHaveBeenCalledWith(`/goals/${goal}/json-acceptance/checks/report-fields`, check)
  expect(post).toHaveBeenCalledWith(`/goals/${goal}/json-acceptance/artifacts/report`, { expectedGeneration: generation, jsonContent: '{}' })
})
