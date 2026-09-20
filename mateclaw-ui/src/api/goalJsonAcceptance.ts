import { http } from './index'

export interface GoalJsonRequirement {
  criterionKey: string
  artifactSlot: string
  revision: string
  requiredFields: string[]
  configuredBy: string
}
export interface GoalJsonAcceptanceView { required: boolean; status: string; requirements: GoalJsonRequirement[] }
export interface ConfigureJsonRequirement { expectedRevision: string; artifactSlot: string; requiredFields: string[] }
export interface ManagedJsonArtifact {
  artifactId: string; artifactSlot: string; generation: string; sha256: string; byteLength: number
  producerKind: string; createdAt: string; expiresAt: string
}
export interface ManagedJsonSlot { artifactSlot: string; generation: string; current: ManagedJsonArtifact | null }
export interface ManagedJsonCheckState {
  criterionKey: string; requirementRevision: string; artifactId: string | null; generation: string | null
  status: string; acceptanceEligible: boolean
}
export interface ManagedJsonSnapshot {
  required: boolean; status: string; versionCount: number; requirements: GoalJsonRequirement[]
  slots: ManagedJsonSlot[]; checks: ManagedJsonCheckState[]
}
export interface ManagedJsonCheckResult extends ManagedJsonCheckState {
  missingFields: string[]; recipeId: string; recipeRevision: number; checkedAt: string; expiresAt: string
}
export const goalJsonAcceptanceApi = {
  snapshot: (goalId: string) => http.get<never, { data: ManagedJsonSnapshot }>(`/goals/${encodeURIComponent(goalId)}/json-acceptance/snapshot`),
  publish: (goalId: string, slot: string, data: { expectedGeneration: string; jsonContent: string }) =>
    http.post<never, { data: ManagedJsonArtifact }>(`/goals/${encodeURIComponent(goalId)}/json-acceptance/artifacts/${encodeURIComponent(slot)}`, data),
  version: (goalId: string, artifactId: string) =>
    http.get<never, { data: { artifact: ManagedJsonArtifact; jsonContent: string } }>(`/goals/${encodeURIComponent(goalId)}/json-acceptance/artifacts/versions/${encodeURIComponent(artifactId)}`),
  check: (goalId: string, key: string, data: { expectedRequirementRevision: string; artifactId: string; expectedGeneration: string }) =>
    http.post<never, { data: ManagedJsonCheckResult }>(`/goals/${encodeURIComponent(goalId)}/json-acceptance/checks/${encodeURIComponent(key)}`, data),
  get: (goalId: string) => http.get<never, { data: GoalJsonAcceptanceView }>(`/goals/${encodeURIComponent(goalId)}/json-acceptance`),
  configure: (goalId: string, key: string, data: ConfigureJsonRequirement) =>
    http.put<never, { data: GoalJsonRequirement }>(`/goals/${encodeURIComponent(goalId)}/json-acceptance/requirements/${encodeURIComponent(key)}`, data),
}
