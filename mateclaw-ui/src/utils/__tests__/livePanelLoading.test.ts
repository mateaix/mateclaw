import { describe, expect, it } from 'vitest'
import { resolveLivePanelLoading } from '@/utils/livePanelLoading'

describe('resolveLivePanelLoading', () => {
  it('starts by connecting to the live view', () => {
    expect(resolveLivePanelLoading({
      hasSnapshot: false,
      snapshotLoading: true,
      teamLoading: false,
      hasRuntimeActivity: false,
      hasError: false,
      elapsedMs: 200,
    })).toEqual({ stage: 'connecting', blocksContent: true })
  })

  it('explains that it is checking active employees after the connection phase', () => {
    expect(resolveLivePanelLoading({
      hasSnapshot: false,
      snapshotLoading: true,
      teamLoading: false,
      hasRuntimeActivity: false,
      hasError: false,
      elapsedMs: 900,
    })).toEqual({ stage: 'checking', blocksContent: true })
  })

  it('surfaces a slow response after two seconds', () => {
    expect(resolveLivePanelLoading({
      hasSnapshot: false,
      snapshotLoading: true,
      teamLoading: false,
      hasRuntimeActivity: false,
      hasError: false,
      elapsedMs: 2_100,
    })).toEqual({ stage: 'slow', blocksContent: true })
  })

  it('shows snapshot content while team task context continues syncing', () => {
    expect(resolveLivePanelLoading({
      hasSnapshot: true,
      snapshotLoading: false,
      teamLoading: true,
      hasRuntimeActivity: true,
      hasError: false,
      elapsedMs: 300,
    })).toEqual({ stage: 'syncingTeams', blocksContent: false })
  })

  it('does not show team syncing for an empty snapshot', () => {
    expect(resolveLivePanelLoading({
      hasSnapshot: true,
      snapshotLoading: false,
      teamLoading: true,
      hasRuntimeActivity: false,
      hasError: false,
      elapsedMs: 300,
    })).toEqual({ stage: null, blocksContent: false })
  })

  it('turns a failed initial request into a retryable state', () => {
    expect(resolveLivePanelLoading({
      hasSnapshot: false,
      snapshotLoading: false,
      teamLoading: false,
      hasRuntimeActivity: false,
      hasError: true,
      elapsedMs: 300,
    })).toEqual({ stage: 'failed', blocksContent: true })
  })
})
