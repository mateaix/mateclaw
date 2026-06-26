import WebSocket from 'ws'
import {
  readFile,
  writeFile,
  editFile,
  listDir,
  statPath,
  executeShell,
  LocalToolError,
} from './localToolsExecutor'
import { requestApproval, clearApprovalCache } from './localToolsApproval'

// ─── Desktop → server local-tool tunnel (client side) ────────────────────────
// Opens a WebSocket to the backend's /api/v1/desktop/ws endpoint, advertises the
// local tool capabilities, and services "call" frames the server forwards when a
// cloud agent invokes a local_* tool. File/shell work runs through the executor
// (whitelist-enforced) and approval (native dialog) modules. Reconnects with
// backoff while the desktop is meant to be online.

const PROTOCOL_VERSION = 1
const CAPABILITIES = ['read', 'list', 'stat', 'write', 'edit', 'shell']
const RECONNECT_MIN_MS = 2000
const RECONNECT_MAX_MS = 30_000

type TokenProvider = () => Promise<string | null>
type UrlProvider = () => string

export class LocalBridge {
  private ws: WebSocket | null = null
  private shouldRun = false
  private reconnectDelay = RECONNECT_MIN_MS
  private reconnectTimer: NodeJS.Timeout | null = null

  constructor(
    private readonly getBackendUrl: UrlProvider,
    private readonly getToken: TokenProvider
  ) {}

  // Begin maintaining a connection. Safe to call repeatedly.
  start(): void {
    if (this.shouldRun) return
    this.shouldRun = true
    void this.connect()
  }

  // Tear down the tunnel and stop reconnecting (e.g. on logout or app quit).
  stop(): void {
    this.shouldRun = false
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    clearApprovalCache()
    if (this.ws) {
      try {
        this.ws.close()
      } catch {
        /* ignore */
      }
      this.ws = null
    }
  }

  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }

  private buildWsUrl(token: string): string | null {
    const base = this.getBackendUrl()
    if (!base) return null
    const wsBase = base.replace(/^http:/i, 'ws:').replace(/^https:/i, 'wss:')
    return `${wsBase}/api/v1/desktop/ws?token=${encodeURIComponent(token)}`
  }

  private async connect(): Promise<void> {
    if (!this.shouldRun) return

    const token = await this.getToken()
    if (!token) {
      // Not logged in yet — retry shortly without escalating backoff.
      this.scheduleReconnect(RECONNECT_MIN_MS)
      return
    }
    const url = this.buildWsUrl(token)
    if (!url) {
      this.scheduleReconnect(RECONNECT_MIN_MS)
      return
    }

    console.log('[LocalBridge] Connecting tunnel…')
    // rejectUnauthorized:false mirrors the app's handling of enterprise
    // self-signed certificates for remote servers the user chose to trust.
    const ws = new WebSocket(url, { rejectUnauthorized: false })
    this.ws = ws

    ws.on('open', () => {
      console.log('[LocalBridge] Tunnel connected')
      this.reconnectDelay = RECONNECT_MIN_MS
      this.send({
        type: 'hello',
        protocolVersion: PROTOCOL_VERSION,
        capabilities: CAPABILITIES,
        platform: process.platform,
      })
    })

    ws.on('message', (raw: WebSocket.RawData) => {
      void this.onMessage(raw.toString())
    })

    ws.on('close', () => {
      console.log('[LocalBridge] Tunnel closed')
      this.ws = null
      if (this.shouldRun) this.scheduleReconnect(this.reconnectDelay)
    })

    ws.on('error', (err: Error) => {
      console.warn('[LocalBridge] Tunnel error:', err.message)
      // 'close' fires after 'error'; reconnect is scheduled there.
    })
  }

  private scheduleReconnect(delay: number): void {
    if (!this.shouldRun || this.reconnectTimer) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, RECONNECT_MAX_MS)
      void this.connect()
    }, delay)
  }

  private send(obj: unknown): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj))
    }
  }

  private async onMessage(text: string): Promise<void> {
    let frame: any
    try {
      frame = JSON.parse(text)
    } catch {
      return
    }

    if (frame.type === 'hello-ack') {
      if (frame.ok === false) console.warn('[LocalBridge] Handshake rejected:', frame.error)
      return
    }
    if (frame.type === 'pong') return
    if (frame.type !== 'call') return

    const { id, method, params } = frame
    try {
      const data = await this.dispatch(method, params || {})
      this.send({ type: 'result', id, ok: true, data })
    } catch (e) {
      const code = e instanceof LocalToolError ? e.code : 'ERROR'
      const error = e instanceof Error ? e.message : String(e)
      this.send({ type: 'result', id, ok: false, code, error })
    }
  }

  private async dispatch(method: string, params: any): Promise<unknown> {
    switch (method) {
      case 'read_file':
        return readFile(params.filePath, params.startLine, params.endLine)
      case 'list_dir':
        return listDir(params.dirPath)
      case 'stat':
        return statPath(params.path)
      case 'write_file': {
        await this.approveOrThrow('write_file', params.filePath,
          `文件: ${params.filePath}\n\n内容预览:\n${preview(params.content)}`)
        return writeFile(params.filePath, params.content)
      }
      case 'edit_file': {
        await this.approveOrThrow('edit_file', params.filePath,
          `文件: ${params.filePath}\n\n替换:\n- ${preview(params.oldText, 200)}\n+ ${preview(params.newText, 200)}`)
        return editFile(params.filePath, params.oldText, params.newText, !!params.replaceAll)
      }
      case 'execute_shell': {
        await this.approveOrThrow('execute_shell', params.command,
          `命令:\n${params.command}`)
        return executeShell(params.command, params.timeoutSeconds || 60)
      }
      default:
        throw new LocalToolError('UNKNOWN_METHOD', `Unknown method: ${method}`)
    }
  }

  private async approveOrThrow(
    kind: 'write_file' | 'edit_file' | 'execute_shell',
    subject: string,
    detail: string
  ): Promise<void> {
    const { approved } = await requestApproval({ kind, subject, detail })
    if (!approved) throw new LocalToolError('DENIED', 'User denied')
  }
}

function preview(text: string | undefined, max = 500): string {
  const s = text ?? ''
  return s.length > max ? `${s.slice(0, max)}\n…(${s.length - max} more chars)` : s
}
