import { describe, expect, it } from 'vitest'

import { processWithConcurrency } from '@/utils/wikiUpload'

describe('processWithConcurrency', () => {
  it('never runs more than the configured number of workers', async () => {
    let active = 0
    let maxActive = 0
    const completed: number[] = []

    await processWithConcurrency([1, 2, 3, 4, 5], async (item) => {
      active += 1
      maxActive = Math.max(maxActive, active)
      await new Promise(resolve => setTimeout(resolve, 5))
      completed.push(item)
      active -= 1
    }, 2)

    expect(maxActive).toBe(2)
    expect(completed.sort()).toEqual([1, 2, 3, 4, 5])
  })

  it('rejects invalid concurrency instead of silently skipping work', async () => {
    await expect(processWithConcurrency([1], async () => undefined, 0))
      .rejects.toThrow('concurrency must be a positive integer')
  })
})
