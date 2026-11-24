import { expect, it } from 'vitest'
import { isCurrentRawJob } from '../utils/wikiJob'

it.each(['completed', 'failed', 'partial', 'cancelled'])('does not reuse a historical %s job during reprocessing', status => {
  expect(isCurrentRawJob('pending', { status })).toBe(false)
  expect(isCurrentRawJob('processing', { status })).toBe(false)
})

it('keeps current running and terminal jobs visible', () => {
  expect(isCurrentRawJob('pending', { status: 'queued' })).toBe(true)
  expect(isCurrentRawJob('processing', { status: 'running' })).toBe(true)
  expect(isCurrentRawJob('completed', { status: 'completed' })).toBe(true)
  expect(isCurrentRawJob('failed', { status: 'failed' })).toBe(true)
  expect(isCurrentRawJob('pending', undefined)).toBe(false)
})
