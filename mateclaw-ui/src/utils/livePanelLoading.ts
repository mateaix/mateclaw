export type LivePanelLoadingStage = 'connecting' | 'checking' | 'slow' | 'syncingTeams' | 'failed' | null

interface LivePanelLoadingInput {
  hasSnapshot: boolean
  snapshotLoading: boolean
  teamLoading: boolean
  hasRuntimeActivity: boolean
  hasError: boolean
  elapsedMs: number
}

export interface LivePanelLoadingState {
  stage: LivePanelLoadingStage
  blocksContent: boolean
}

export function resolveLivePanelLoading(input: LivePanelLoadingInput): LivePanelLoadingState {
  if (!input.hasSnapshot) {
    if (input.hasError && !input.snapshotLoading) {
      return { stage: 'failed', blocksContent: true }
    }
    if (input.elapsedMs >= 2_000) {
      return { stage: 'slow', blocksContent: true }
    }
    if (input.elapsedMs >= 600) {
      return { stage: 'checking', blocksContent: true }
    }
    return { stage: 'connecting', blocksContent: true }
  }

  if (input.teamLoading && input.hasRuntimeActivity) {
    return { stage: 'syncingTeams', blocksContent: false }
  }

  return { stage: null, blocksContent: false }
}
