import { describe, expect, it, vi } from 'vitest'
import { openWikiFailureItem } from '../utils/failureOpen'

describe('openWikiFailureItem', () => {
  it('switches to the owning workspace before opening a cross-workspace KB', async () => {
    const calls: string[] = []
    const workspaceStore = {
      currentWorkspaceId: 'ws-a',
      switchWorkspace: vi.fn(async (id: string) => {
        calls.push(`switch:${id}`)
      }),
    }
    const wikiStore = {
      selectKB: vi.fn(async (id: string, mode: 'browse' | 'manage') => {
        calls.push(`select:${id}:${mode}`)
      }),
    }

    await openWikiFailureItem(
      { kbId: 'kb-2', workspaceId: 'ws-b' },
      { workspaceStore, wikiStore },
    )

    expect(calls).toEqual(['switch:ws-b', 'select:kb-2:browse'])
  })

  it('opens directly when the failure item belongs to the active workspace', async () => {
    const workspaceStore = {
      currentWorkspaceId: 'ws-a',
      switchWorkspace: vi.fn(),
    }
    const wikiStore = {
      selectKB: vi.fn(async () => {}),
    }

    await openWikiFailureItem(
      { kbId: 'kb-1', workspaceId: 'ws-a' },
      { workspaceStore, wikiStore },
    )

    expect(workspaceStore.switchWorkspace).not.toHaveBeenCalled()
    expect(wikiStore.selectKB).toHaveBeenCalledWith('kb-1', 'browse')
  })
})
