import { app } from 'electron'
import { join, resolve, relative, isAbsolute } from 'path'
import { homedir } from 'os'
import { existsSync, readFileSync, writeFileSync } from 'fs'

// ─── Local tools configuration ───────────────────────────────────────────────
// Governs the desktop's local file/shell tool proxy: whether it is enabled, the
// directory whitelist every local file operation is constrained to, and the
// default policy when no whitelist is configured. Stored as its own JSON file in
// userData so it is independent of the connection config.

export interface LocalToolsConfig {
  // Master switch. When false the desktop advertises no local-tool capabilities
  // and rejects any forwarded call.
  enabled: boolean
  // Absolute (or ~-prefixed) directories the agent may touch. Every local file
  // operation must resolve to a path inside one of these.
  allowedDirs: string[]
  // Policy when allowedDirs is empty:
  //   true  (fail-closed, default) → deny all local file access
  //   false (fail-open)            → allow the entire local filesystem
  failClosed: boolean
}

const DEFAULT_CONFIG: LocalToolsConfig = {
  enabled: true,
  allowedDirs: [],
  failClosed: true,
}

function getConfigPath(): string {
  return join(app.getPath('userData'), 'local-tools.json')
}

export function loadLocalToolsConfig(): LocalToolsConfig {
  try {
    const path = getConfigPath()
    if (!existsSync(path)) return { ...DEFAULT_CONFIG }
    const raw = JSON.parse(readFileSync(path, 'utf-8')) as Partial<LocalToolsConfig>
    return {
      ...DEFAULT_CONFIG,
      ...raw,
      allowedDirs: Array.isArray(raw.allowedDirs) ? raw.allowedDirs : [],
    }
  } catch (err) {
    console.error('[MateClaw] Failed to read local-tools config:', err)
    return { ...DEFAULT_CONFIG }
  }
}

export function saveLocalToolsConfig(patch: Partial<LocalToolsConfig>): LocalToolsConfig {
  const merged: LocalToolsConfig = { ...loadLocalToolsConfig(), ...patch }
  try {
    writeFileSync(getConfigPath(), JSON.stringify(merged, null, 2), 'utf-8')
  } catch (err) {
    console.error('[MateClaw] Failed to write local-tools config:', err)
  }
  return merged
}

// Expand a leading ~ to the user's home directory and resolve to an absolute,
// normalized path. Returns null for empty input.
export function expandPath(input: string): string | null {
  const trimmed = (input || '').trim()
  if (!trimmed) return null
  const expanded = trimmed === '~' || trimmed.startsWith('~/')
    ? join(homedir(), trimmed.slice(1))
    : trimmed
  return resolve(expanded)
}

// Whether `target` is contained by `dir` (or equal to it). Both are resolved
// absolute paths. Uses path.relative so it is symlink-name-agnostic but does not
// follow symlinks — the whitelist is enforced on the lexical path the agent asked
// for, which is the path the user approved.
function isInside(dir: string, target: string): boolean {
  const rel = relative(dir, target)
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel))
}

export interface PathCheck {
  allowed: boolean
  // Resolved absolute path (when input was parseable), for use by the caller.
  resolved: string | null
  // Machine-readable reason when not allowed.
  reason?: 'disabled' | 'unparseable' | 'whitelist'
}

// Decide whether a local file operation on `inputPath` is permitted by the
// current configuration. This is the single chokepoint every file tool calls.
export function checkPath(inputPath: string): PathCheck {
  const cfg = loadLocalToolsConfig()
  if (!cfg.enabled) return { allowed: false, resolved: null, reason: 'disabled' }

  const target = expandPath(inputPath)
  if (!target) return { allowed: false, resolved: null, reason: 'unparseable' }

  if (cfg.allowedDirs.length === 0) {
    return { allowed: !cfg.failClosed, resolved: target, reason: cfg.failClosed ? 'whitelist' : undefined }
  }

  for (const dir of cfg.allowedDirs) {
    const base = expandPath(dir)
    if (base && isInside(base, target)) {
      return { allowed: true, resolved: target }
    }
  }
  return { allowed: false, resolved: target, reason: 'whitelist' }
}

// The working directory to run a shell command in: the first configured
// whitelist directory, falling back to the user's home. Shell commands are not
// path-checked (they are arbitrary), so they are gated by approval + timeout and
// pinned to a sensible cwd rather than wherever the app launched.
export function shellWorkingDir(): string {
  const cfg = loadLocalToolsConfig()
  for (const dir of cfg.allowedDirs) {
    const base = expandPath(dir)
    if (base && existsSync(base)) return base
  }
  return homedir()
}
