import { describe, expect, it } from 'vitest'
import { isHigherSseEventId } from '@/composables/sseEventIds'

describe('isHigherSseEventId', () => {
  it('orders adjacent ids above the JavaScript safe integer range', () => {
    expect(isHigherSseEventId('1850000000000000001', '1850000000000000000')).toBe(true)
    expect(isHigherSseEventId('1850000000000000000', '1850000000000000001')).toBe(false)
  })

  it('compares decimal ids by magnitude without numeric coercion', () => {
    expect(isHigherSseEventId('1000', '999')).toBe(true)
    expect(isHigherSseEventId('0001000', '999')).toBe(true)
    expect(isHigherSseEventId('not-numeric', '999')).toBe(false)
  })
})
