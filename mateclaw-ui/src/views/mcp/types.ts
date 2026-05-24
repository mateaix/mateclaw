/**
 * Shape returned by GET /api/v1/mcp/servers — mirrors the McpServerEntity
 * column set. Kept here (rather than in the API module) so the MCP page
 * components can import a single domain type without pulling axios with it.
 */
export interface McpServer {
  id: number
  name: string
  description: string
  transport: 'stdio' | 'sse' | 'streamable_http'
  url: string
  headersJson: string
  command: string
  argsJson: string
  envJson: string
  cwd: string
  enabled: boolean
  connectTimeoutSeconds: number
  readTimeoutSeconds: number
  lastStatus: 'connected' | 'disconnected' | 'error' | string
  lastError: string
  lastConnectedTime: string
  toolCount: number
  builtin: boolean
  /** Whole-server disclosure tier: 'core' | 'extension'. Null/absent = core. */
  disclosureTier?: string
}

/** Result of POST /api/v1/mcp/servers/{id}/test. */
export interface McpTestResult {
  success: boolean
  message: string
  toolCount: number
  latencyMs: number
  discoveredTools: string[]
}

/** Form payload — superset of the create/edit body. */
export interface McpServerForm {
  name: string
  description: string
  transport: 'stdio' | 'sse' | 'streamable_http'
  url: string
  headersJson: string
  command: string
  argsJson: string
  envJson: string
  cwd: string
  connectTimeoutSeconds: number
  readTimeoutSeconds: number
  enabled: boolean
}

export function emptyMcpForm(): McpServerForm {
  return {
    name: '',
    description: '',
    transport: 'stdio',
    url: '',
    headersJson: '',
    command: '',
    argsJson: '',
    envJson: '',
    cwd: '',
    connectTimeoutSeconds: 30,
    readTimeoutSeconds: 30,
    enabled: true,
  }
}
