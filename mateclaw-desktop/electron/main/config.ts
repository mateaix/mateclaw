import { app } from 'electron'
import { join } from 'path'
import { existsSync, readFileSync, writeFileSync } from 'fs'

// ─── Connection configuration ────────────────────────────────────────────────
// Persists how the desktop shell reaches its backend: either an embedded local
// JVM ("local") or a centrally deployed remote server ("remote"). Stored as a
// small JSON file in userData so no extra dependency is required.

export type ConnectionMode = 'local' | 'remote'

export interface RemoteServer {
  url: string
  name?: string
  lastUsed?: number
}

export interface ConnectionConfig {
  // null = no choice made yet (first run → show the connection chooser)
  mode: ConnectionMode | null
  remoteUrl: string
  servers: RemoteServer[]
}

const DEFAULT_CONFIG: ConnectionConfig = {
  mode: null,
  remoteUrl: '',
  servers: [],
}

function getConfigPath(): string {
  return join(app.getPath('userData'), 'connection.json')
}

export function loadConfig(): ConnectionConfig {
  try {
    const path = getConfigPath()
    if (!existsSync(path)) return { ...DEFAULT_CONFIG }
    const raw = JSON.parse(readFileSync(path, 'utf-8')) as Partial<ConnectionConfig>
    return {
      ...DEFAULT_CONFIG,
      ...raw,
      servers: Array.isArray(raw.servers) ? raw.servers : [],
    }
  } catch (err) {
    console.error('[MateClaw] Failed to read connection config:', err)
    return { ...DEFAULT_CONFIG }
  }
}

export function saveConfig(patch: Partial<ConnectionConfig>): ConnectionConfig {
  const merged: ConnectionConfig = { ...loadConfig(), ...patch }
  try {
    writeFileSync(getConfigPath(), JSON.stringify(merged, null, 2), 'utf-8')
  } catch (err) {
    console.error('[MateClaw] Failed to write connection config:', err)
  }
  return merged
}

// Normalize a user-entered server URL: trim, default to https when no scheme is
// given, and strip a trailing slash. Returns null when the input cannot form a
// valid http(s) URL.
export function normalizeServerUrl(input: string): string | null {
  const trimmed = (input || '').trim()
  if (!trimmed) return null

  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`
  try {
    const url = new URL(withScheme)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
    // Drop a trailing slash on the path-less root so URLs compare cleanly.
    return withScheme.replace(/\/+$/, '')
  } catch {
    return null
  }
}

// Record a successful remote connection in the most-recently-used server list,
// de-duplicating by URL and capping the history length.
export function recordServer(url: string, name?: string): ConnectionConfig {
  const cfg = loadConfig()
  const now = Date.now()
  const without = cfg.servers.filter((s) => s.url !== url)
  const servers: RemoteServer[] = [{ url, name, lastUsed: now }, ...without].slice(0, 8)
  return saveConfig({ servers })
}
