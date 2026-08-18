import { describe, expect, it } from 'vitest'
import { prettyPrintJsonForDisplay } from '../jsonViewFormat'

describe('prettyPrintJsonForDisplay', () => {
  it('preserves large integer text in tool arguments instead of rounding through JS Number', () => {
    const raw = '{"agentId":2079862124134313986,"type":"reference"}'

    const pretty = prettyPrintJsonForDisplay(raw)

    expect(pretty).toContain('2079862124134313986')
    expect(pretty).not.toContain('2079862124134314000')
  })
})
