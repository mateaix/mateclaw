/**
 * Capability type definitions consumed by route guards and nav filtering.
 *
 * Authoritative role -> capability mapping lives on the backend
 * (`vip.mate.workspace.core.security.RoleCapabilities`). The frontend never
 * derives the set locally; it only consumes what `/api/v1/workspaces/{id}/access`
 * returns. Keeping the mapping single-source avoids the "frontend allows but
 * backend rejects" drift that v1 reviewers flagged.
 */

export type Capability =
  | 'chat'
  | 'view:wiki'
  | 'view:memory'
  | 'view:dashboard'
  | 'manage:wiki'
  | 'manage:agents'
  | 'manage:skills'
  | 'manage:channels'
  | 'manage:models'
  | 'manage:security'
  | 'manage:settings'

export type WorkspaceRole = 'viewer' | 'member' | 'admin' | 'owner'

export const ROLE_LEVEL: Record<WorkspaceRole, number> = {
  viewer: 1,
  member: 2,
  admin: 3,
  owner: 4,
}
