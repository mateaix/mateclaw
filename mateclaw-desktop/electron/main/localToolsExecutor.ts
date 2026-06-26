import { spawn } from 'child_process'
import {
  readFileSync,
  writeFileSync,
  mkdirSync,
  readdirSync,
  statSync,
  existsSync,
} from 'fs'
import { dirname } from 'path'
import { checkPath, shellWorkingDir } from './localToolsConfig'

// ─── Local tool executor ─────────────────────────────────────────────────────
// Runs the actual file/shell operations on the user's machine. Every file
// operation is constrained to the directory whitelist via checkPath(); shell
// commands are gated by approval (handled by the caller) and a hard timeout.
// Output limits mirror the server-side tools: ~30KB for file reads, ~10KB each
// for shell stdout/stderr.

const MAX_FILE_BYTES = 30 * 1024
const MAX_SHELL_BYTES = 10_000
const IS_WINDOWS = process.platform === 'win32'

export class LocalToolError extends Error {
  constructor(public code: string, message: string) {
    super(message)
  }
}

function requireAllowed(inputPath: string): string {
  const check = checkPath(inputPath)
  if (!check.allowed) {
    if (check.reason === 'disabled') {
      throw new LocalToolError('DISABLED', 'Local tools are disabled in the desktop app')
    }
    if (check.reason === 'unparseable') {
      throw new LocalToolError('BAD_PATH', `Invalid path: ${inputPath}`)
    }
    throw new LocalToolError(
      'WHITELIST',
      `Path is outside the allowed local directories: ${inputPath}`
    )
  }
  return check.resolved as string
}

export function readFile(filePath: string, startLine?: number, endLine?: number): unknown {
  const path = requireAllowed(filePath)
  if (!existsSync(path)) throw new LocalToolError('NOT_FOUND', `File not found: ${filePath}`)
  if (statSync(path).isDirectory()) {
    throw new LocalToolError('IS_DIR', `Path is a directory: ${filePath}`)
  }

  const raw = readFileSync(path, 'utf-8')
  const allLines = raw.split('\n')
  const totalLines = allLines.length

  const start = startLine && startLine > 0 ? startLine : 1
  const end = endLine && endLine > 0 ? Math.min(endLine, totalLines) : totalLines
  if (start > totalLines) {
    throw new LocalToolError('RANGE', `startLine ${start} exceeds total lines ${totalLines}`)
  }

  let content = ''
  let readLines = 0
  let truncated = false
  for (let i = start - 1; i < end; i++) {
    const line = `${String(i + 1).padStart(6)}\t${allLines[i]}\n`
    if (Buffer.byteLength(content + line, 'utf-8') > MAX_FILE_BYTES) {
      truncated = true
      break
    }
    content += line
    readLines++
  }

  return { filePath: path, totalLines, startLine: start, readLines, content, truncated }
}

export function writeFile(filePath: string, content: string): unknown {
  const path = requireAllowed(filePath)
  const existed = existsSync(path)
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, content ?? '', 'utf-8')
  return {
    filePath: path,
    bytesWritten: Buffer.byteLength(content ?? '', 'utf-8'),
    created: !existed,
    overwritten: existed,
  }
}

export function editFile(
  filePath: string,
  oldText: string,
  newText: string,
  replaceAll: boolean
): unknown {
  const path = requireAllowed(filePath)
  if (!existsSync(path)) throw new LocalToolError('NOT_FOUND', `File not found: ${filePath}`)

  const original = readFileSync(path, 'utf-8')
  if (!original.includes(oldText)) {
    throw new LocalToolError('NO_MATCH', 'oldText not found in file')
  }

  let replacements = 0
  let updated: string
  if (replaceAll) {
    updated = original.split(oldText).join(newText)
    replacements = original.split(oldText).length - 1
  } else {
    updated = original.replace(oldText, newText)
    replacements = 1
  }
  writeFileSync(path, updated, 'utf-8')
  return { filePath: path, replacements, replaceAll: !!replaceAll }
}

export function listDir(dirPath: string): unknown {
  const path = requireAllowed(dirPath)
  if (!existsSync(path)) throw new LocalToolError('NOT_FOUND', `Directory not found: ${dirPath}`)
  if (!statSync(path).isDirectory()) {
    throw new LocalToolError('NOT_DIR', `Path is not a directory: ${dirPath}`)
  }
  const entries = readdirSync(path, { withFileTypes: true }).map((e) => ({
    name: e.name,
    type: e.isDirectory() ? 'dir' : 'file',
  }))
  return { dirPath: path, entries }
}

export function statPath(targetPath: string): unknown {
  const path = requireAllowed(targetPath)
  if (!existsSync(path)) throw new LocalToolError('NOT_FOUND', `Path not found: ${targetPath}`)
  const st = statSync(path)
  return {
    path,
    size: st.size,
    isDirectory: st.isDirectory(),
    modifiedTime: st.mtime.toISOString(),
  }
}

function truncateUtf8(buf: Buffer, maxBytes: number): { text: string; truncated: boolean } {
  if (buf.length <= maxBytes) return { text: buf.toString('utf-8'), truncated: false }
  return {
    text: buf.subarray(0, maxBytes).toString('utf-8') +
      `\n... [output truncated, exceeds ${maxBytes} byte limit]`,
    truncated: true,
  }
}

export function executeShell(command: string, timeoutSeconds: number): Promise<unknown> {
  const cwd = shellWorkingDir()
  const timeoutMs = Math.min(Math.max(timeoutSeconds, 1), 300) * 1000

  // cmd.exe on Windows, /bin/sh on macOS/Linux — mirrors the server tool.
  const child = IS_WINDOWS
    ? spawn('cmd.exe', ['/D', '/S', '/C', command], { cwd })
    : spawn('/bin/sh', ['-c', command], { cwd })

  const stdoutChunks: Buffer[] = []
  const stderrChunks: Buffer[] = []
  child.stdout.on('data', (d: Buffer) => stdoutChunks.push(d))
  child.stderr.on('data', (d: Buffer) => stderrChunks.push(d))

  return new Promise((resolvePromise) => {
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      child.kill('SIGKILL')
    }, timeoutMs)

    const finish = (exitCode: number) => {
      clearTimeout(timer)
      const out = truncateUtf8(Buffer.concat(stdoutChunks), MAX_SHELL_BYTES)
      const err = truncateUtf8(Buffer.concat(stderrChunks), MAX_SHELL_BYTES)
      resolvePromise({
        command,
        exitCode,
        stdout: out.text,
        stderr: err.text,
        timedOut,
      })
    }

    child.on('error', (e) => {
      clearTimeout(timer)
      resolvePromise({ command, exitCode: -1, stdout: '', stderr: String(e), timedOut: false })
    })
    child.on('close', (code) => finish(code == null ? -1 : code))
  })
}
