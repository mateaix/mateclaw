import { afterEach, expect, it, vi } from 'vitest'
import { createApp } from 'vue'
import JobStageBar from '../JobStageBar.vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('@/stores/useWorkspaceStore', () => ({ useWorkspaceStore: () => ({ can: () => true }) }))
afterEach(() => vi.useRealTimers())

function elapsed(status: string, finishedAt?: string, startedAt = '2026-11-23T12:00:00Z') {
  const host = document.createElement('div')
  const app = createApp(JobStageBar, { stage: status, status, startedAt, finishedAt })
  app.mount(host)
  const text = host.querySelector('.info-elapsed')?.textContent ?? ''
  app.unmount()
  return text
}

it.each(['completed', 'failed', 'partial', 'cancelled'])('freezes %s job duration at finishedAt across remounts', status => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-23T12:00:10Z'))
  expect(elapsed(status, '2026-11-23T12:00:05Z')).toContain('5s')
  vi.setSystemTime(new Date('2026-11-23T12:01:10Z'))
  expect(elapsed(status, '2026-11-23T12:00:05Z')).toContain('5s')
})

it('does not invent a duration for a legacy terminal job without finishedAt', () => {
  expect(elapsed('completed')).toBe('')
})

it('does not render invalid or negative elapsed times', () => {
  expect(elapsed('completed', 'invalid')).toBe('')
  expect(elapsed('completed', '2026-11-23T11:59:00Z')).toContain('0s')
})
