import axios from 'axios'
import { handleAuthFailure, updateTokenFromHeader } from '@/utils/auth'

// Axios 实例
export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

// 请求拦截器：注入 Token + Workspace ID + Accept-Language
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  const workspaceId = localStorage.getItem('mc-workspace-id')
  if (workspaceId) {
    config.headers['X-Workspace-Id'] = workspaceId
  }
  // Forward the user's UI locale so locale-sensitive endpoints (e.g.
  // template apply) can pick the right display strings. Native browsers
  // already send Accept-Language, but the user's chosen UI language may
  // differ from the OS default — explicitly setting it keeps the two
  // in sync.
  const locale = localStorage.getItem('mateclaw_locale')
  if (locale) {
    config.headers['Accept-Language'] = locale
  }
  return config
})

// 响应拦截器：适配后端 R<T> { code, msg, data } 格式
http.interceptors.response.use(
  (res) => {
    // 滑动窗口续期：后端在 Token 接近过期时通过响应头下发新 Token
    updateTokenFromHeader(res.headers)

    const data = res.data
    // 后端统一响应格式 R<T>: { code: number, msg: string, data: T }
    if (data && typeof data === 'object' && 'code' in data) {
      if (data.code === 200) return data
      // 401 = authentication failure → log out
      // 403 = authorization failure (e.g. workspace permission denied) → keep session, surface error to caller
      if (data.code === 401) {
        handleAuthFailure()
        return Promise.reject(new Error(data.msg || 'Unauthorized'))
      }
      return Promise.reject(new Error(data.msg || 'Request failed'))
    }
    return data
  },
  (err) => {
    if (err.response?.status === 401) {
      handleAuthFailure()
    }
    // Wrap as Error so consumers can read e.message uniformly, but preserve
    // err.response so callers needing the structured payload (e.g. workflow
    // compile errors → response.data.data.errors[]) can still reach it.
    // The previous shape rejected with a bare string, which silently
    // stripped the body and made 422-class errors look like "no response"
    // on the publish/compile flows.
    const wrapped = new Error(err.response?.data?.msg || err.message || 'Request failed')
    ;(wrapped as Error & { response?: unknown }).response = err.response
    return Promise.reject(wrapped)
  }
)

// ==================== 受保护文件访问 ====================

/**
 * 使用 JWT 认证 fetch 受保护文件，返回 Blob。
 * 用于 <img> 和 <a download> 无法自动携带 Authorization header 的场景。
 * 使用原生 fetch（不走 axios），避免 baseURL 拼接和 R<T> 拦截器干扰。
 */
export async function fetchAuthenticatedBlob(fileUrl: string): Promise<Blob> {
  const token = localStorage.getItem('token')
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(fileUrl, { headers })
  if (!response.ok) throw new Error(`Fetch failed: ${response.status}`)
  return response.blob()
}

// ==================== Auth ====================
export const authApi = {
  login: (data: { username: string; password: string }) =>
    http.post('/auth/login', data),
  listUsers: () => http.get('/auth/users'),
  createUser: (data: any) => http.post('/auth/users', data),
  changePassword: (id: string | number, oldPassword: string, newPassword: string) =>
    http.put(`/auth/users/${id}/password`, null, { params: { oldPassword, newPassword } }),
}

// ==================== Agent ====================
export const agentApi = {
  /**
   * @param params.enabled when `true`, restricts the result to enabled agents
   *   (used by chat selectors so disabled agents disappear from the picker).
   *   Omit to receive enabled + disabled (admin management page).
   */
  list: (params?: { enabled?: boolean }) => http.get('/agents', { params }),
  get: (id: string | number) => http.get(`/agents/${id}`),
  create: (data: any) => http.post('/agents', data),
  update: (id: string | number, data: any) => http.put(`/agents/${id}`, data),
  delete: (id: string | number) => http.delete(`/agents/${id}`),
  chat: (id: string | number, data: any) => http.post(`/agents/${id}/chat`, data),
  execute: (id: string | number, data: any) => http.post(`/agents/${id}/execute`, data),
  getState: (id: string | number) => http.get(`/agents/${id}/state`),
  /** Lightweight capability snapshot used by the chat console attachment hint. */
  getCapabilities: (id: string | number) => http.get(`/agents/${id}/capabilities`),
}

// ==================== Templates ====================
export const templateApi = {
  list: () => http.get('/templates'),
  apply: (id: string) => http.post(`/templates/${id}/apply`),
}

// ==================== Chat ====================
export const chatApi = {
  uploadFile: async (conversationId: string, file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('conversationId', conversationId)
    return http.post('/chat/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    })
  },
  stream: (data: any, signal?: AbortSignal) => {
    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      'Cache-Control': 'no-cache',
    }
    const token = localStorage.getItem('token')
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
    return fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers,
      body: JSON.stringify(data),
      signal,
    })
  },
  stop: (conversationId: string) =>
    http.post<{ stopped: boolean }>(`/chat/${conversationId}/stop`),
  getPendingApprovals: (conversationId: string) =>
    http.get(`/chat/${conversationId}/pending-approvals`),
}

// ==================== Conversation ====================
export const conversationApi = {
  list: () => http.get('/conversations'),
  /**
   * Paginated list used by the Sessions admin page. Keyword matches title
   * or conversationId server-side; ChatConsole's left panel still uses the
   * non-paginated list() because it shows a per-agent rolling history.
   */
  page: (params: { page?: number; size?: number; keyword?: string }) =>
    http.get('/conversations/page', { params }),
  listMessages: (conversationId: string, params?: { beforeId?: number; limit?: number }) =>
    http.get(`/conversations/${conversationId}/messages`, { params }),
  getStatus: (conversationId: string) =>
    http.get(`/conversations/${conversationId}/status`),
  delete: (conversationId: string) =>
    http.delete(`/conversations/${conversationId}`),
  clearMessages: (conversationId: string) =>
    http.delete(`/conversations/${conversationId}/messages`),
  rename: (conversationId: string, title: string) =>
    http.put(`/conversations/${conversationId}/title`, { title }),
  setPinned: (conversationId: string, pinned: boolean) =>
    http.put(`/conversations/${conversationId}/pin`, { pinned }),
  /**
   * Pin this conversation to a specific (provider, model). Closes issue
   * #183 — lets the admin UI switch model for IM-channel conversations
   * (Feishu / DingTalk / WeCom / Telegram / Discord / QQ / Slack / WeChat),
   * not just for the Web channel. Both params required and non-empty.
   */
  setModel: (conversationId: string, modelProvider: string, modelName: string) =>
    http.put(`/conversations/${conversationId}/model`, { modelProvider, modelName }),
  batchDelete: (conversationIds: string[]) =>
    http.post('/conversations/batch-delete', { conversationIds }),
}

// ==================== Skill ====================
export const skillApi = {
  /** Paginated skill listing with search, source, status, and sort filters. */
  page: (params: {
    page?: number
    size?: number
    keyword?: string
    skillType?: string
    source?: string
    sort?: string
    runtime?: string
    agentId?: string | number
    enabled?: boolean
    /** 'PASSED' / 'FAILED' — filters by security_scan_status. */
    scanStatus?: string
    /** 'active' / 'stale' / 'archived' — filters by lifecycle_state. */
    lifecycleState?: string
  } = {}) => http.get('/skills', { params }),
  /** Tab count aggregate — returns { all, builtin, mcp, dynamic } */
  counts: () => http.get('/skills/counts'),
  /** RFC-042 §2.3.4 — manually rescan a single skill's security */
  rescan: (id: string | number) => http.post(`/skills/${id}/rescan`),
  listEnabled: () => http.get('/skills/enabled'),
  get: (id: string | number) => http.get(`/skills/${id}`),
  create: (data: any) => http.post('/skills', data),
  update: (id: string | number, data: any) => http.put(`/skills/${id}`, data),
  delete: (id: string | number) => http.delete(`/skills/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/skills/${id}/toggle?enabled=${enabled}`),
  getActiveSkills: () => http.get('/skills/runtime/active'),
  getRuntimeStatus: () => http.get('/skills/runtime/status'),
  refreshRuntime: () => http.post('/skills/runtime/refresh'),
  exportWorkspace: (id: string | number) => http.post(`/skills/${id}/export-workspace`),
  getWorkspaceInfo: (id: string | number) => http.get(`/skills/${id}/workspace`),
  // RFC-090 §7 + §11.4 — pre-flight requirements + LESSONS.md + reverse lookup
  requirements: (id: string | number) => http.get(`/skills/${id}/requirements`),
  getLessons: (id: string | number) => http.get(`/skills/${id}/lessons`),
  clearLessons: (id: string | number) => http.post(`/skills/${id}/lessons/clear`),
  employees: (id: string | number) => http.get(`/skills/${id}/employees`),
  /**
   * Per-skill secrets — env-var-shaped key/value pairs that get injected
   * into the script subprocess at runtime. Plaintext values never leave
   * the server; list returns masked previews only.
   */
  listSecrets: (id: string | number) => http.get(`/skills/${id}/secrets`),
  putSecret: (id: string | number, key: string, value: string) =>
    http.post(`/skills/${id}/secrets`, { key, value }),
  deleteSecret: (id: string | number, key: string) =>
    http.delete(`/skills/${id}/secrets/${encodeURIComponent(key)}`),

  // ---- Lifecycle curator ----
  /** Pin / unpin a skill — pinned skills are never auto-archived. */
  pin: (id: string | number, pinned: boolean) =>
    http.post(`/skills/${id}/pin`, { pinned }),
  /**
   * Manually archive a skill. When the skill is bound to an enabled agent
   * and {@code force} is false, the backend replies HTTP 409 with
   * {@code code: 'BOUND_SKILL_CONFIRM_REQUIRED'} and a {@code boundAgents}
   * list; retry with {@code force: true} to confirm.
   */
  archive: (id: string | number, opts: { force?: boolean; reason?: string } = {}) =>
    http.post(`/skills/${id}/archive`, { reason: opts.reason ?? null },
      { params: { force: opts.force ?? false } }),
  /** Restore an archived skill back to active. */
  restore: (id: string | number) => http.post(`/skills/${id}/restore`),
  /** Curator control-panel status (config / control / counts / lastReport). */
  curatorStatus: () => http.get('/skills/curator/status'),
  /** Run a curator dry-run preview immediately. */
  curatorDryRun: () => http.post('/skills/curator/dry-run'),
  /** Activate (apply transitions) or deactivate (preview-only) the curator. */
  curatorActivate: (activate: boolean) =>
    http.post('/skills/curator/activate', null, { params: { activate } }),
  curatorPause: () => http.post('/skills/curator/pause'),
  curatorResume: () => http.post('/skills/curator/resume'),
  /** List recent curator run report ids. */
  curatorReports: () => http.get('/skills/curator/reports'),
  /** Read one curator run report (parsed run.json). */
  curatorReport: (runId: string) => http.get(`/skills/curator/reports/${runId}`),
}

/** Shape returned by GET /skills/{id}/secrets. */
export interface SkillSecretSummary {
  key: string
  /** Masked preview, e.g. "abc...xyz". Plaintext is never shipped. */
  preview: string
  updatedAt: string
}

// ==================== Activity Feed (RFC-090 §4.5) ====================
export const activityApi = {
  feed: (params: { source?: string; page?: number; size?: number; workspaceId?: number } = {}) =>
    http.get('/activity/feed', { params }),
}

// ==================== Live (admin runtime view) ====================
export interface LiveRunCard {
  conversationId: string
  agentId: number | null
  agentName: string | null
  agentIcon: string | null
  username: string | null
  currentPhase: string | null
  runningToolName: string | null
  waitingReason: string | null
  done: boolean
  stopRequested: boolean
  firstTokenReceived: boolean
  subscriberCount: number
  queueLen: number
  ageMs: number
  msSinceLastEvent: number
  /** null when healthy; otherwise: 'idle_silent' | 'tool_silent' | 'hard_cap' */
  stuckReason: string | null
  orphan: boolean
  subagentCount: number
}

export interface LiveSubagentCard {
  subagentId: string
  parentConversationId: string | null
  childConversationId: string | null
  agentId: number | null
  agentName: string | null
  agentIcon: string | null
  goal: string | null
  status: string | null
  currentPhase: string | null
  lastTool: string | null
  toolCount: number
  ageMs: number
}

export interface LiveSummary {
  running: number
  stuck: number
  orphan: number
  queued: number
  subagentsActive: number
}

export interface LiveSnapshot {
  summary: LiveSummary
  runs: LiveRunCard[]
  subagents: LiveSubagentCard[]
  timestamp: number
}

export const liveApi = {
  snapshot: () => http.get<{ data: LiveSnapshot }>('/admin/agent-runtime/snapshot'),
  stop: (conversationId: string) =>
    http.post(`/admin/agent-runtime/runs/${encodeURIComponent(conversationId)}/stop`),
  recycle: (conversationId: string) =>
    http.post(`/admin/agent-runtime/runs/${encodeURIComponent(conversationId)}/recycle`),
  interruptSubagent: (subagentId: string) =>
    http.post(`/admin/agent-runtime/subagents/${encodeURIComponent(subagentId)}/interrupt`),
  sweep: () => http.post('/admin/agent-runtime/sweep'),
}

// ==================== Notification summary (sidebar attention badges) ====================
export interface NotificationSummary {
  pendingApprovals: number
  stuckAgents: number
  failedCrons: number
  downChannels: number
  downMcps: number
}

export const notificationApi = {
  summary: () => http.get<{ data: NotificationSummary }>('/notifications/summary'),
}

// ==================== ACP Endpoints (RFC-090 Phase 7) ====================
export const acpApi = {
  list: () => http.get('/acp/endpoints'),
  get: (id: number | string) => http.get(`/acp/endpoints/${id}`),
  create: (data: any) => http.post('/acp/endpoints', data),
  update: (id: number | string, data: any) => http.put(`/acp/endpoints/${id}`, data),
  delete: (id: number | string) => http.delete(`/acp/endpoints/${id}`),
  toggle: (id: number | string, enabled: boolean) =>
    http.put(`/acp/endpoints/${id}/toggle?enabled=${enabled}`),
  test: (id: number | string) => http.post(`/acp/endpoints/${id}/test`),
}

// ==================== Skill Templates (RFC-091) ====================
export const skillTemplateApi = {
  list: () => http.get('/skill-templates'),
  get: (id: string) => http.get(`/skill-templates/${id}`),
  instantiate: (id: string, values: Record<string, unknown>) =>
    http.post(`/skill-templates/${id}/instantiate`, values),
}

// ==================== Skill Install ====================
export const skillInstallApi = {
  searchHub: (q: string, limit = 20) =>
    http.get('/skills/install/hub/search', { params: { q, limit } }),
  startInstall: (data: { bundleUrl: string; version?: string; enable?: boolean; targetName?: string; overwrite?: boolean }) =>
    http.post('/skills/install/start', data),
  getStatus: (taskId: string) =>
    http.get(`/skills/install/status/${taskId}`),
  cancelInstall: (taskId: string) =>
    http.post(`/skills/install/cancel/${taskId}`),
  uninstall: (skillName: string) =>
    http.delete(`/skills/install/${skillName}`),
  uploadZip: (file: File, options?: { enable?: boolean; overwrite?: boolean; targetName?: string }) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post('/skills/install/upload', formData, {
      params: options,
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}

// ==================== Datasource ====================
export const datasourceApi = {
  list: () => http.get('/datasources'),
  get: (id: string | number) => http.get(`/datasources/${id}`),
  create: (data: any) => http.post('/datasources', data),
  update: (id: string | number, data: any) => http.put(`/datasources/${id}`, data),
  delete: (id: string | number) => http.delete(`/datasources/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/datasources/${id}/toggle?enabled=${enabled}`),
  test: (id: string | number) => http.post(`/datasources/${id}/test`),
}

// ==================== Tool ====================
export const toolApi = {
  list: () => http.get('/tools'),
  listEnabled: () => http.get('/tools/enabled'),
  /**
   * Unified picker source for the agent edit tool tab — returns built-in
   * tools plus every MCP-discovered tool grouped by server. The `name`
   * field is what gets saved into mate_agent_tool.tool_name.
   */
  listAvailable: () => http.get('/tools/available'),
  get: (id: string | number) => http.get(`/tools/${id}`),
  create: (data: any) => http.post('/tools', data),
  update: (id: string | number, data: any) => http.put(`/tools/${id}`, data),
  delete: (id: string | number) => http.delete(`/tools/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/tools/${id}/toggle?enabled=${enabled}`),
}

// ==================== Channel ====================
export const channelApi = {
  list: () => http.get('/channels'),
  get: (id: string | number) => http.get(`/channels/${id}`),
  create: (data: any) => http.post('/channels', data),
  update: (id: string | number, data: any) => http.put(`/channels/${id}`, data),
  delete: (id: string | number) => http.delete(`/channels/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/channels/${id}/toggle?enabled=${enabled}`),
  status: () => http.get('/channels/status'),
  /** Real-time per-channel health (true transport state, not DB enabled flag). */
  health: (id: string | number) => http.get(`/channels/${id}/health`),
  /** Batch health for all channels in current workspace. */
  healthAll: () => http.get('/channels/health'),
  /**
   * Wizard Step 2 — validate a draft config without persisting.
   * Returns a VerificationResult: { ok, skipped, durationMs, headline,
   * identity, invalidField, hint }.
   */
  preflight: (channelType: string, configJson: string) =>
    http.post('/channels/preflight', { channelType, configJson }),
  // 微信 iLink Bot QR 码登录
  weixinQrcode: () => http.get('/channels/webhook/weixin/qrcode'),
  weixinQrcodeStatus: (qrcode: string) =>
    http.get(`/channels/webhook/weixin/qrcode/status?qrcode=${encodeURIComponent(qrcode)}`),
  // Feishu one-click app registration (oapi-sdk 2.6+ scene/registration)
  feishuRegisterBegin: (domain: string) =>
    http.post(`/channels/webhook/feishu/register/begin?domain=${encodeURIComponent(domain)}`),
  feishuRegisterStatus: (sessionId: string) =>
    http.get(`/channels/webhook/feishu/register/status?session=${encodeURIComponent(sessionId)}`),
  // DingTalk one-click app registration (OAuth Device Flow)
  dingtalkRegisterBegin: () =>
    http.post('/channels/webhook/dingtalk/register/begin'),
  dingtalkRegisterStatus: (sessionId: string) =>
    http.get(`/channels/webhook/dingtalk/register/status?session=${encodeURIComponent(sessionId)}`),
  // QQ Bot scan-to-bind (Lite portal). Uses the unified channel QR auth endpoint.
  qqRegisterBegin: () =>
    http.post('/channels/qrcode/qq/begin'),
  qqRegisterStatus: (sessionId: string) =>
    http.get(`/channels/qrcode/qq/status?session=${encodeURIComponent(sessionId)}`),
}

// ==================== MCP Server ====================
export const mcpApi = {
  list: () => http.get('/mcp/servers'),
  get: (id: string | number) => http.get(`/mcp/servers/${id}`),
  create: (data: any) => http.post('/mcp/servers', data),
  update: (id: string | number, data: any) => http.put(`/mcp/servers/${id}`, data),
  delete: (id: string | number) => http.delete(`/mcp/servers/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/mcp/servers/${id}/toggle?enabled=${enabled}`),
  test: (id: string | number) => http.post(`/mcp/servers/${id}/test`),
  refresh: () => http.post('/mcp/servers/refresh'),
}

// ==================== Plan ====================
export const planApi = {
  listByAgent: (agentId: string) => http.get(`/plans?agentId=${agentId}`),
  get: (id: string | number) => http.get(`/plans/${id}`),
}

// ==================== Model ====================
export const modelApi = {
  listProviders: () => http.get('/models'),
  listEnabled: () => http.get('/models/enabled'),
  get: (id: string | number) => http.get(`/models/${id}`),
  getDefault: () => http.get('/models/default'),
  create: (data: any) => http.post('/models', data),
  update: (id: string | number, data: any) => http.put(`/models/${id}`, data),
  delete: (id: string | number) => http.delete(`/models/${id}`),
  setDefault: (id: string | number) => http.post(`/models/${id}/default`),
  updateProviderConfig: (providerId: string, data: any) =>
    http.put(`/models/${providerId}/config`, data),
  createCustomProvider: (data: any) => http.post('/models/custom-providers', data),
  // Issue #39: fall back to a query-param endpoint when the providerId can't
  // safely sit in a path segment (slash / space / etc.) — those rows would
  // otherwise be undeletable because Spring's {providerId} doesn't span "/".
  deleteCustomProvider: (providerId: string) => {
    const safe = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/.test(providerId)
    return safe
      ? http.delete(`/models/custom-providers/${providerId}`)
      : http.delete('/models/custom-providers', { params: { providerId } })
  },
  addProviderModel: (providerId: string, data: any) =>
    http.post(`/models/${providerId}/models`, data),
  removeProviderModel: (providerId: string, modelId: string) =>
    http.delete(`/models/${providerId}/models`, { params: { modelId } }),
  getActive: () => http.get('/models/active'),
  setActive: (data: { providerId: string; model: string }) =>
    http.put('/models/active', data),
  // 模型发现与连接测试
  discoverModels: (providerId: string) =>
    http.post(`/models/${providerId}/discover`),
  applyDiscoveredModels: (providerId: string, modelIds: string[]) =>
    http.post(`/models/${providerId}/discover/apply`, { modelIds }),
  testConnection: (providerId: string) =>
    http.post(`/models/${providerId}/test-connection`),
  testModel: (providerId: string, modelId: string) =>
    http.post(`/models/${providerId}/models/test`, null, { params: { modelId } }),

  // ==================== RFC-074: enabled / catalog ====================
  /** Full provider catalog including enabled=false rows; powers the Add Provider drawer. */
  catalog: () => http.get('/models/catalog'),
  /** Opt a provider into the dropdown; backend triggers re-probe via ModelConfigChangedEvent. */
  enableProvider: (providerId: string) => http.post(`/models/${providerId}/enable`),
  /** Hide a provider; if it owned the current default model, backend auto-promotes a replacement. */
  disableProvider: (providerId: string) => http.post(`/models/${providerId}/disable`),

  // ==================== Embedding Model (RFC Embedding UI) ====================
  listByType: (modelType: 'chat' | 'embedding', modality?: 'vision' | 'video' | 'audio') =>
    http.get('/models/by-type', { params: { modelType, modality } }),
  testEmbedding: (modelId: string | number) =>
    http.post(`/models/embedding/${modelId}/test`),
  getDefaultEmbedding: () => http.get('/models/embedding/default'),
  setDefaultEmbedding: (modelId: string | number | '') =>
    http.post('/models/embedding/default', { modelId }),
}

// ==================== Provider Pool (RFC-009 Phase 4) ====================
export interface ProviderPoolEntry {
  providerId: string
  providerName: string
  inPool: boolean
  removalSource: string | null
  removalMessage: string | null
  removedAtMs: number | null
  inCooldown: boolean
  cooldownRemainingMs: number
  consecutiveFailures: number
}

export interface ReprobeResult {
  providerId: string
  success: boolean
  latencyMs: number
  errorMessage: string | null
  inPool: boolean
}

export const providerPoolApi = {
  snapshot: () => http.get<ProviderPoolEntry[]>('/llm/provider-pool'),
  reprobe: (providerId: string) =>
    http.post<ReprobeResult>(`/llm/provider-pool/${encodeURIComponent(providerId)}/reprobe`),
}

// ==================== OAuth ====================
export const oauthApi = {
  authorize: () => http.get('/oauth/openai/authorize'),
  status: () => http.get('/oauth/openai/status'),
  refresh: () => http.post('/oauth/openai/refresh'),
  revoke: () => http.delete('/oauth/openai/revoke'),
  callbackPaste: (callbackUrl: string) =>
    http.post('/oauth/openai/callback-paste', { callbackUrl }),
  // Device Authorization Grant — used when MateClaw runs on a remote host so the
  // browser cannot reach localhost:1455 for the PKCE callback.
  deviceStart: () => http.post('/oauth/openai/device/start'),
  devicePoll: (deviceAuthId: string) =>
    http.post('/oauth/openai/device/poll', { deviceAuthId }),
  deviceCancel: (deviceAuthId: string) =>
    http.post('/oauth/openai/device/cancel', { deviceAuthId }),
}

// RFC-062: Claude Code OAuth piggybacks on the user's local Claude Code
// install — no in-app authorize/revoke flow yet (PR-4). Until then the UI
// can only check status + force a re-detect from disk.
export const claudeCodeOAuthApi = {
  status: () => http.get('/oauth/anthropic/status'),
  reload: () => http.post('/oauth/anthropic/reload'),
}

// ==================== Setup ====================
export const setupApi = {
  onboardingStatus: () => http.get('/setup/onboarding-status'),
}

// ==================== Settings ====================
export const settingsApi = {
  get: () => http.get('/settings'),
  update: (data: any) => http.put('/settings', data),
  getLanguage: () => http.get('/settings/language'),
  updateLanguage: (language: string) => http.put('/settings/language', { language }),
  // Dedicated endpoint for the multimodal sidecar configuration. The bulk
  // /settings PUT now guards vision/video model ids with non-null checks so
  // unrelated settings pages can't clobber them via partial payloads. This
  // endpoint is the only path that writes those fields unconditionally —
  // pass {defaultVisionModelId: null} here to explicitly clear a sidecar.
  // Model IDs are accepted as either JSON numbers or strings — Jackson
  // coerces both into Long. The string form is preferred from the UI to
  // sidestep JS Number precision loss on 19-digit Snowflake IDs.
  updateSidecar: (data: { defaultVisionModelId: number | string | null; defaultVideoModelId: number | string | null }) =>
    http.put('/settings/sidecar', data),
}

// ==================== Workspace ====================
const encodeFilePath = (filename: string) =>
  filename.split('/').map(encodeURIComponent).join('/')

export const agentContextApi = {
  listFiles: (agentId: string | number) =>
    http.get(`/agents/${agentId}/workspace/files`),
  getFile: (agentId: string | number, filename: string) =>
    http.get(`/agents/${agentId}/workspace/files/${encodeFilePath(filename)}`),
  saveFile: (agentId: string | number, filename: string, content: string) =>
    http.put(`/agents/${agentId}/workspace/files/${encodeFilePath(filename)}`, { content }),
  deleteFile: (agentId: string | number, filename: string) =>
    http.delete(`/agents/${agentId}/workspace/files/${encodeFilePath(filename)}`),
  getPromptFiles: (agentId: string | number) =>
    http.get(`/agents/${agentId}/workspace/prompt-files`),
  setPromptFiles: (agentId: string | number, files: string[]) =>
    http.put(`/agents/${agentId}/workspace/prompt-files`, { files }),

  // Memory snapshot — export downloads a ZIP via native fetch (axios R<T>
  // interceptor would mis-handle the binary body); import + preview go
  // through axios with multipart so the standard auth / workspace headers
  // flow automatically.
  exportMemorySnapshot: async (agentId: string | number): Promise<Blob> => {
    const token = localStorage.getItem('token')
    const workspaceId = localStorage.getItem('mc-workspace-id')
    const headers: Record<string, string> = {}
    if (token) headers.Authorization = `Bearer ${token}`
    if (workspaceId) headers['X-Workspace-Id'] = workspaceId
    const url = `/api/v1/agents/${agentId}/workspace/memory/export`
    const response = await fetch(url, { headers })
    if (!response.ok) {
      // Try to parse the standard R<T> error envelope for a useful message.
      let detail = `HTTP ${response.status}`
      try {
        const body = await response.json()
        if (body && typeof body.msg === 'string') detail = body.msg
      } catch { /* ignore — non-JSON body */ }
      throw new Error(detail)
    }
    return response.blob()
  },
  previewImportMemorySnapshot: (agentId: string | number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post(`/agents/${agentId}/workspace/memory/import/preview`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  applyImportMemorySnapshot: (agentId: string | number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post(`/agents/${agentId}/workspace/memory/import`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}

// ==================== Security ====================
export const securityApi = {
  getGuardConfig: () => http.get('/security/guard/config'),
  updateGuardConfig: (data: any) => http.put('/security/guard/config', data),
  getFileGuardConfig: () => http.get('/security/guard/config/file-guard'),
  updateFileGuardConfig: (data: any) => http.put('/security/guard/config/file-guard', data),
  listRules: (params?: any) => http.get('/security/guard/rules', { params }),
  listBuiltinRules: (params?: any) => http.get('/security/guard/rules/builtin', { params }),
  createRule: (data: any) => http.post('/security/guard/rules', data),
  updateRule: (ruleId: string, data: any) => http.put(`/security/guard/rules/${ruleId}`, data),
  toggleRule: (ruleId: string, enabled: boolean) =>
    http.put(`/security/guard/rules/${ruleId}/toggle?enabled=${enabled}`),
  deleteRule: (ruleId: string) => http.delete(`/security/guard/rules/${ruleId}`),
  exportRules: () => http.get('/security/guard/rules/export'),
  importRules: (data: { rules: any[] }) => http.post('/security/guard/rules/import', data),
  listAuditLogs: (params?: any) => http.get('/security/audit/logs', { params }),
  getAuditStats: () => http.get('/security/audit/stats'),
  listApprovals: (params?: any) => http.get('/security/approvals', { params }),
}

// ==================== Token Usage ====================
export const tokenUsageApi = {
  getSummary: (params?: { startDate?: string; endDate?: string; modelName?: string; providerId?: string }) =>
    http.get('/token-usage', { params }),
}

// ==================== CronJob ====================
export const cronJobApi = {
  list: () => http.get('/cron-jobs'),
  get: (id: string | number) => http.get(`/cron-jobs/${id}`),
  create: (data: any) => http.post('/cron-jobs', data),
  update: (id: string | number, data: any) => http.put(`/cron-jobs/${id}`, data),
  delete: (id: string | number) => http.delete(`/cron-jobs/${id}`),
  toggle: (id: string | number, enabled: boolean) =>
    http.put(`/cron-jobs/${id}/toggle`, null, { params: { enabled } }),
  runNow: (id: string | number) => http.post(`/cron-jobs/${id}/run`),
  activeRuns: (conversationId: string) =>
    http.get('/cron-jobs/active-runs', { params: { conversationId } }),
}

// ==================== Wiki Knowledge Base ====================
export const wikiApi = {
  // Knowledge Base
  listKBs: () => http.get('/wiki/knowledge-bases'),
  getKB: (id: number) => http.get(`/wiki/knowledge-bases/${id}`),
  listKBsByAgent: (agentId: number) => http.get(`/wiki/knowledge-bases/agent/${agentId}`),
  createKB: (data: { name: string; description?: string; agentId?: number }) =>
    http.post('/wiki/knowledge-bases', data),
  updateKB: (id: number, data: { name?: string; description?: string; agentId?: number; embeddingModelId?: string | number | null }) =>
    http.put(`/wiki/knowledge-bases/${id}`, data),
  deleteKB: (id: number) => http.delete(`/wiki/knowledge-bases/${id}`),
  getConfig: (id: number) => http.get(`/wiki/knowledge-bases/${id}/config`),
  updateConfig: (id: number, content: string) =>
    http.put(`/wiki/knowledge-bases/${id}/config`, { content }),

  // Directory Scan
  setSourceDirectory: (id: number, path: string) =>
    http.put(`/wiki/knowledge-bases/${id}/source-directory`, { path }),
  scanDirectory: (id: number) => http.post(`/wiki/knowledge-bases/${id}/scan`),

  // Raw Materials
  listRaw: (kbId: number) => http.get(`/wiki/knowledge-bases/${kbId}/raw`),
  addRawText: (kbId: number, data: { title: string; content: string }) =>
    http.post(`/wiki/knowledge-bases/${kbId}/raw/text`, data),
  uploadRaw: (kbId: number, formData: FormData, onProgress?: (pct: number) => void) =>
    http.post(`/wiki/knowledge-bases/${kbId}/raw/upload`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: onProgress
        ? (e) => { if (e.total) onProgress(Math.round((e.loaded / e.total) * 100)) }
        : undefined,
    }),
  deleteRaw: (kbId: number, rawId: number) =>
    http.delete(`/wiki/knowledge-bases/${kbId}/raw/${rawId}`),
  reprocessRaw: (kbId: number, rawId: number) =>
    http.post(`/wiki/knowledge-bases/${kbId}/raw/${rawId}/reprocess`),
  cancelRaw: (kbId: number, rawId: number) =>
    http.post(`/wiki/knowledge-bases/${kbId}/raw/${rawId}/cancel`),
  downloadRaw: (kbId: number, rawId: number) =>
    http.get<Blob>(`/wiki/knowledge-bases/${kbId}/raw/${rawId}/download`, {
      responseType: 'blob',
    }),

  // Wiki Pages
  listPages: (kbId: number, rawId?: number) =>
    http.get(`/wiki/knowledge-bases/${kbId}/pages`, rawId != null ? { params: { rawId } } : undefined),
  getPage: (kbId: number, slug: string) =>
    http.get(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}`),
  updatePage: (kbId: number, slug: string, content: string) =>
    http.put(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}`, { content }),
  deletePage: (kbId: number, slug: string) =>
    http.delete(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}`),
  batchDeletePages: (kbId: number, slugs: string[]) =>
    http.delete(`/wiki/knowledge-bases/${kbId}/pages/batch`, { data: slugs }),
  getBacklinks: (kbId: number, slug: string) =>
    http.get(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}/backlinks`),

  // Archived pages
  listArchivedPages: (kbId: number) =>
    http.get(`/wiki/knowledge-bases/${kbId}/pages/archived`),
  archivePage: (kbId: number, slug: string) =>
    http.post(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}/archive`),
  unarchivePage: (kbId: number, slug: string) =>
    http.post(`/wiki/knowledge-bases/${kbId}/pages/${encodeURIComponent(slug)}/unarchive`),

  // Processing
  processKB: (kbId: number) => http.post(`/wiki/knowledge-bases/${kbId}/process`),
  getProcessingStatus: (kbId: number) => http.get(`/wiki/knowledge-bases/${kbId}/processing-status`),

  // RFC-029: Relations
  getRelatedPages: (kbId: number | string, slug: string, topK = 5) =>
    http.get(`/wiki/kb/${kbId}/pages/${encodeURIComponent(slug)}/related`, { params: { topK } }),
  explainRelation: (kbId: number, slugA: string, slugB: string) =>
    http.get(`/wiki/kb/${kbId}/pages/${encodeURIComponent(slugA)}/relation/${encodeURIComponent(slugB)}`),
  getPageCitations: (kbId: number | string, pageId: number | string) =>
    http.get(`/wiki/kb/${kbId}/pages/${pageId}/citations`),

  // RFC-030: Jobs
  getWikiJobs: (kbId: number, rawId: number) =>
    http.get(`/wiki/kb/${kbId}/jobs`, { params: { rawId } }),
  getKBStats: (kbId: number) =>
    http.get(`/wiki/kb/${kbId}/stats`),

  // RFC-031: Enrichment & Repair
  enrichPage: (kbId: number, slug: string) =>
    http.post(`/wiki/kb/${kbId}/pages/${encodeURIComponent(slug)}/enrich`),
  repairPage: (kbId: number, slug: string) =>
    http.post(`/wiki/kb/${kbId}/pages/${encodeURIComponent(slug)}/repair`),

  // RFC-032: Search preview
  searchPreview: (kbId: number, data: { query: string; mode?: string; topK?: number }) =>
    http.post(`/wiki/kb/${kbId}/search-preview`, data),

  // Transformations: reusable prompt templates run over raw materials
  listTransformations: (kbId?: number) =>
    http.get('/wiki/transformations', kbId != null ? { params: { kbId } } : undefined),
  getTransformation: (id: number) =>
    http.get(`/wiki/transformations/${id}`),
  createTransformation: (data: {
    kbId?: number | null
    name: string
    title: string
    description?: string
    promptTemplate: string
    applyDefault?: boolean
    enabled?: boolean
    modelId?: number | null
    outputTarget?: 'none' | 'page'
    outputFormat?: 'markdown' | 'json'
    outputSchema?: string | null
  }) =>
    http.post('/wiki/transformations', data),
  updateTransformation: (id: number, data: {
    title?: string
    description?: string
    promptTemplate?: string
    applyDefault?: boolean
    enabled?: boolean
    modelId?: number | null
    outputTarget?: 'none' | 'page'
    outputFormat?: 'markdown' | 'json'
    outputSchema?: string | null
  }) =>
    http.put(`/wiki/transformations/${id}`, data),
  deleteTransformation: (id: number) =>
    http.delete(`/wiki/transformations/${id}`),
  applyTransformation: (id: number, rawId: number, sync = true) =>
    http.post(`/wiki/transformations/${id}/apply`, { rawId }, { params: { sync } }),
  applyTransformationToPage: (id: number, pageId: number, sync = true) =>
    http.post(`/wiki/transformations/${id}/apply`, { pageId }, { params: { sync } }),
  aggregateTransformation: (id: number, kbId: number) =>
    http.post(`/wiki/transformations/${id}/aggregate`, undefined, { params: { kbId } }),
  listTransformationRuns: (params: { rawId?: number; kbId?: number; transformationId?: number; limit?: number }) =>
    http.get('/wiki/transformations/runs', { params }),
  getTransformationRun: (runId: number) =>
    http.get(`/wiki/transformations/runs/${runId}`),
  deleteTransformationRun: (runId: number) =>
    http.delete(`/wiki/transformations/runs/${runId}`),
  saveTransformationRunAsPage: (runId: number) =>
    http.post(`/wiki/transformations/runs/${runId}/save-as-page`),
  cancelTransformationRun: (runId: number) =>
    http.post(`/wiki/transformations/runs/${runId}/cancel`),
}

// ==================== Workspace (Team) ====================
export const workspaceTeamApi = {
  list: () => http.get('/workspaces'),
  get: (id: string | number) => http.get(`/workspaces/${id}`),
  getAccess: (id: string | number) => http.get(`/workspaces/${id}/access`),
  create: (data: any) => http.post('/workspaces', data),
  update: (id: string | number, data: any) => http.put(`/workspaces/${id}`, data),
  delete: (id: string | number) => http.delete(`/workspaces/${id}`),
  listMembers: (id: string | number) => http.get(`/workspaces/${id}/members`),
  addMember: (id: string | number, data: { username: string; password?: string; nickname?: string; role?: string }) =>
    http.post(`/workspaces/${id}/members`, data),
  updateMemberRole: (id: string | number, memberId: string | number, role: string) =>
    http.put(`/workspaces/${id}/members/${memberId}`, { role }),
  removeMember: (id: string | number, memberId: string | number) =>
    http.delete(`/workspaces/${id}/members/${memberId}`),
}

// ==================== Agent Binding ====================
export const agentBindingApi = {
  listSkills: (agentId: string | number) => http.get(`/agents/${agentId}/skills`),
  setSkills: (agentId: string | number, skillIds: number[]) => http.put(`/agents/${agentId}/skills`, skillIds),
  bindSkill: (agentId: string | number, skillId: number) => http.post(`/agents/${agentId}/skills/${skillId}`),
  unbindSkill: (agentId: string | number, skillId: number) => http.delete(`/agents/${agentId}/skills/${skillId}`),
  listTools: (agentId: string | number) => http.get(`/agents/${agentId}/tools`),
  setTools: (agentId: string | number, toolNames: string[]) => http.put(`/agents/${agentId}/tools`, toolNames),
  // RFC-009 PR-3: per-agent provider preference order. Empty list = use global chain order.
  listProviderPreferences: (agentId: string | number) =>
    http.get(`/agents/${agentId}/provider-preferences`),
  setProviderPreferences: (agentId: string | number, providerIds: string[]) =>
    http.put(`/agents/${agentId}/provider-preferences`, providerIds),
}

// ==================== Dashboard ====================
export const dashboardApi = {
  overview: () => http.get('/dashboard/overview'),
  trend: (days = 30) => http.get('/dashboard/trend', { params: { days } }),
  agentRanking: (days = 7, topN = 10) => http.get('/dashboard/agent-ranking', { params: { days, topN } }),
  cronJobRuns: (cronJobId: string | number, limit = 20) => http.get(`/dashboard/cron-runs/${cronJobId}`, { params: { limit } }),
  recentRuns: (limit = 20) => http.get('/dashboard/cron-runs', { params: { limit } }),
}

// ==================== Plugins ====================
export const pluginApi = {
  list: () => http.get('/plugins'),
  get: (name: string) => http.get(`/plugins/${name}`),
  disable: (name: string) => http.post(`/plugins/${name}/disable`),
  enable: (name: string) => http.post(`/plugins/${name}/enable`),
  updateConfig: (name: string, config: Record<string, any>) => http.put(`/plugins/${name}/config`, config),
}

// ==================== Audit Events ====================
export const auditApi = {
  listEvents: (params: {
    action?: string
    resourceType?: string
    startTime?: string
    endTime?: string
    page?: number
    size?: number
  }) => http.get('/audit/events', { params }),
}

// ==================== Feature Flags ====================
export interface FeatureFlag {
  id: number
  flagKey: string
  enabled: boolean
  description?: string
  whitelistKbIds?: string
  whitelistUserIds?: string
  rolloutPercent?: number
  createTime?: string
  updateTime?: string
}

export interface FeatureFlagUpdate {
  enabled?: boolean
  description?: string
  whitelistKbIds?: string
  whitelistUserIds?: string
  rolloutPercent?: number
}

export const featureFlagApi = {
  list: () => http.get<FeatureFlag[]>('/feature-flags'),
  update: (flagKey: string, data: FeatureFlagUpdate) =>
    http.put(`/feature-flags/${flagKey}`, data),
}

export interface WikiHotCache {
  id: number
  kbId: number
  content: string | null
  contentHash: string | null
  lastUpdated: string | null
  updateReason: string | null
  rebuildCount: number
  lastRebuildStartedAt: string | null
  lastRebuildDurationMs: number | null
  lastRebuildError: string | null
  createTime: string
  updateTime: string
}

export const hotCacheApi = {
  get: (kbId: number) => http.get<WikiHotCache | null>(`/wiki/hot-cache/${kbId}`),
  regenerate: (kbId: number) => http.post(`/wiki/hot-cache/${kbId}/regenerate`),
  reset: (kbId: number) => http.delete(`/wiki/hot-cache/${kbId}`),
}

// ==================== Workflow ====================

export interface WorkflowSummary {
  id: number
  workspaceId: string | number
  name: string
  description?: string
  enabled: boolean
  draftJson?: string
  draftUpdatedAt?: string
  latestRevisionId?: number
  /** Human version number of the latest published revision (1, 2, 3…) — shown
   *  as "v3" instead of the latestRevisionId snowflake. Null when unpublished. */
  latestRevisionNumber?: number
  /** Latest published revision's graph JSON — populated by GET /workflows/{id}
   *  so the editor can render a published workflow whose draft was cleared. */
  publishedGraphJson?: string
  createTime: string
  updateTime: string
}

export interface WorkflowCompileError {
  code: string
  path: string
  message: string
}

export interface WorkflowCompileFailure {
  errorCount: number
  errors: WorkflowCompileError[]
}

export interface WorkflowRun {
  id: number
  workflowId: number
  revisionId: number
  workspaceId: string | number
  state: string
  triggeredBy?: string
  initialInputRef?: string
  finalOutputRef?: string
  errorMessage?: string
  startedAt?: string
  completedAt?: string
}

export interface WorkflowRunStep {
  id: number
  runId: number
  stepIndex: number
  iterationIndex?: number
  stepName?: string
  state: string
  outputRef?: string
  outputSummary?: string
  outputContentType?: string
  errorMessage?: string
  durationMs?: number
  startedAt?: string
  completedAt?: string
}

/** Pause record carried inline on a paused run so the UI can resume
 *  without a second roundtrip. Mirrors mate_workflow_run_pause. */
export interface WorkflowRunPause {
  id: number
  runId: number
  stepId?: number
  pauseKind: string
  pauseToken: string
  externalApprovalId?: number
  pausedAt: string
  resumeDeadline?: string
  resumePayloadRef?: string
  resumedAt?: string
  resumeOutcome?: string
}

export interface PausedRunSummary {
  run: WorkflowRun
  pause: WorkflowRunPause | null
}

export interface RunDetail {
  run: WorkflowRun
  steps: WorkflowRunStep[]
  /** Most recent unresolved pause record; null when the run is not paused. */
  activePause: WorkflowRunPause | null
}

export type ResumeOutcome = 'approved' | 'rejected' | 'timeout' | 'cancelled'

export interface ResumeResponse {
  kind: string
  runId?: number | null
  errorMessage?: string | null
}

export const workflowApi = {
  list: (workspaceId: string | number) =>
    http.get<WorkflowSummary[]>('/workflows', { params: { workspaceId } }),
  get: (id: number) => http.get<WorkflowSummary>(`/workflows/${id}`),
  create: (data: Partial<WorkflowSummary>) =>
    http.post<WorkflowSummary>('/workflows', data),
  update: (id: number, data: Partial<WorkflowSummary>) =>
    http.put<WorkflowSummary>(`/workflows/${id}`, data),
  delete: (id: number) => http.delete(`/workflows/${id}`),
  saveDraft: (id: number, draftJson: string, userId?: number) =>
    http.put(`/workflows/${id}/draft`, { draftJson }, { params: userId ? { userId } : {} }),
  compile: (id: number) => http.post(`/workflows/${id}/compile`),
  publish: (id: number, note?: string, userId?: number) =>
    http.post(`/workflows/${id}/publish`,
      note ? { note } : {},
      { params: userId ? { userId } : {} }),
  runs: (id: number, limit = 50) =>
    http.get<WorkflowRun[]>(`/workflows/${id}/runs`, { params: { limit } }),
  runDetail: (runId: number) =>
    http.get<RunDetail>(`/workflows/runs/${runId}`),
  /** List paused runs across the workspace so operators can resume them. */
  listPausedRuns: (limit = 50) =>
    http.get<PausedRunSummary[]>('/workflows/runs/paused', { params: { limit } }),
  /** Resume a paused workflow run with one of the four documented outcomes. */
  resumeRun: (runId: number, pauseToken: string, outcome: ResumeOutcome, payload?: string) =>
    http.post<ResumeResponse>(`/workflows/runs/${runId}/resume`, {
      pauseToken,
      outcome,
      payload,
    }),
  /** Generate a workflow draft from natural language. Returns the parsed
   *  shape; the caller is responsible for creating a workflow row +
   *  saving the draft if the user accepts it. */
  generateDraft: (description: string) =>
    http.post<GeneratedDraft>('/workflows/draft/generate', { description }),
  /** Canonical workflow templates the generator can apply directly. */
  listDraftTemplates: () =>
    http.get<WorkflowDraftTemplate[]>('/workflows/draft/templates'),
  /** Compile arbitrary draft JSON without persisting. Used by the template
   *  picker / generator-result preview to give the operator the same compile
   *  signal the existing /workflows/{id}/compile endpoint provides for saved
   *  workflows. Resolves on success; rejects with an axios error whose
   *  response.data.data carries the WorkflowCompileFailure on a 422. */
  previewCompileDraft: (draftJson: string) =>
    http.post('/workflows/draft/preview-compile', { draftJson }),
}

export interface GeneratedDraft {
  name: string
  description: string
  draftJson: string
  triggerDrafts: Array<Record<string, unknown>>
  warnings: string[]
  missingFields: string[]
  confidence?: number | null
  compileOk: boolean
  compileErrors: WorkflowCompileError[]
}

export interface WorkflowDraftTemplate {
  id: string
  label: string
  description: string
  matchHints: string[]
  draftJson: string
  triggerDraftsJson: string
}

// ==================== Trigger ====================

export interface TriggerSummary {
  id: number
  workspaceId: string | number
  name?: string
  patternType: string
  patternJson: string
  targetType: string
  // Snowflake ID — backend serializes Long as string (ToStringSerializer).
  // Keep the union so v-model can hold the string form without TS errors
  // and JS Number() coercion stays out of the round-trip.
  targetId: number | string
  payloadTemplate?: string
  rateLimitPerMin: number
  dedupWindowSecs: number
  botSelfFilter: boolean
  enabled: boolean
  fireCount: number
  maxFires: number
  lastFiredAt?: string
  /** Stamp of the last dispatch attempt regardless of outcome (FIRED /
   *  SKIPPED / FAILED). Distinguishes "never attempted" from
   *  "attempted but the pre-flight skipped". */
  lastDispatchedAt?: string
  /** Most recent dispatch outcome message; null when the last attempt
   *  fired cleanly. */
  lastError?: string
  patternVersion: number
  createTime: string
  updateTime: string
}

export const triggerApi = {
  list: (workspaceId: string | number) =>
    http.get<TriggerSummary[]>('/triggers', { params: { workspaceId } }),
  get: (id: number) => http.get<TriggerSummary>(`/triggers/${id}`),
  create: (data: Partial<TriggerSummary>) =>
    http.post<TriggerSummary>('/triggers', data),
  update: (id: number, data: Partial<TriggerSummary>) =>
    http.put<TriggerSummary>(`/triggers/${id}`, data),
  delete: (id: number) => http.delete(`/triggers/${id}`),
  ingestEvent: (envelope: {
    workspaceId: string | number
    patternType: string
    eventId?: string
    senderId?: string
    data?: Record<string, unknown>
  }) => http.post('/triggers/events', envelope),
}
