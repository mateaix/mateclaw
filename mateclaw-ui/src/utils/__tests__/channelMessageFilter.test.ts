import { describe, expect, it } from 'vitest'
import { supportsChannelMessageFilter } from '../channelConfigJson'
import { CHANNEL_FIELD_DEFS } from '../../types'

describe('supportsChannelMessageFilter', () => {
  it('exposes message filter controls for WeCom and Weixin channels', () => {
    expect(supportsChannelMessageFilter('wecom')).toBe(true)
    expect(supportsChannelMessageFilter('weixin')).toBe(true)
  })

  it('hides message filter controls for browser-rendered channels', () => {
    expect(supportsChannelMessageFilter('web')).toBe(false)
    expect(supportsChannelMessageFilter('webchat')).toBe(false)
  })

  it('exposes execution trace controls for WeCom and Weixin channels', () => {
    expect(CHANNEL_FIELD_DEFS.wecom.map(field => field.key)).toContain('stream_progress')
    expect(CHANNEL_FIELD_DEFS.weixin.map(field => field.key)).toContain('stream_progress')
  })
})
