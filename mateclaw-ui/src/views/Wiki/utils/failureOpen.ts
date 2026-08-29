export interface WikiFailureOpenItem {
  kbId: string
  workspaceId?: string | null
}

export interface WikiFailureOpenDeps {
  workspaceStore: {
    currentWorkspaceId: string | null
    switchWorkspace: (id: string) => Promise<void> | void
  }
  wikiStore: {
    // The wiki store is still typed as number in places, but Snowflake KB IDs
    // are passed as strings at runtime to avoid precision loss.
    selectKB: (id: any, mode: 'browse' | 'manage') => Promise<void> | void
  }
}

export async function openWikiFailureItem(
  item: WikiFailureOpenItem,
  deps: WikiFailureOpenDeps,
) {
  const targetWorkspaceId = item.workspaceId || null
  if (targetWorkspaceId && deps.workspaceStore.currentWorkspaceId !== targetWorkspaceId) {
    await deps.workspaceStore.switchWorkspace(targetWorkspaceId)
  }
  await deps.wikiStore.selectKB(item.kbId, 'browse')
}
