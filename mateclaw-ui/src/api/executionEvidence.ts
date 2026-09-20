import { http } from './index'

export interface ExecutionEvidence {
  id: string
  attemptId: string
  conversationId: string
  toolName: string
  state: 'STARTED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'UNKNOWN' | 'BLOCKED'
  effectOutcome: 'NONE' | 'CONFIRMED' | 'UNCERTAIN'
  kind: 'TOOL_RETURNED' | 'COMMAND_EXIT' | 'ARTIFACT_SNAPSHOT' | 'CHECK_RESULT'
  result: 'OBSERVED' | 'PASS' | 'FAIL' | 'UNKNOWN'
  sourceLevel: string
  validity: 'UNKNOWN' | 'UNAVAILABLE' | 'STALE' | 'VALID'
  summary: string | null
  observedAt: string
  expiresAt: string | null
  artifactRef: string | null
  artifactDigest: string | null
  checkScope?: string | null
}
export interface ExecutionEvidencePage { items: ExecutionEvidence[]; nextCursor: string | null }
export interface ExecutionEvidenceQuery {
  conversationId: string
  goalId?: string
  teamTaskId?: string
  cursor?: string
  limit?: number
}
export interface ArtifactJsonCheck {
  recipeId: string
  recipeRevision: number
  status: 'MATCH' | 'MISSING_FIELDS' | 'INVALID_JSON' | 'UNKNOWN' | 'STALE' | 'UNAVAILABLE'
  requiredFields: string[]
  missingFields: string[]
  checkedAt: string
  acceptanceEligible: false
}
export const executionEvidenceApi = {
  list: (params: ExecutionEvidenceQuery) => http.get<never, { data: ExecutionEvidencePage }>('/execution-evidence', { params }),
  checkJson: (id: string, requiredFields: string[]) => http.post<never, { data: ArtifactJsonCheck }>(`/execution-evidence/${encodeURIComponent(id)}/json-check`, { requiredFields }),
  get: (id: string) => http.get<never, { data: ExecutionEvidence }>(`/execution-evidence/${encodeURIComponent(id)}`),
}
