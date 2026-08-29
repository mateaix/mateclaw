import type { ChannelFieldDef } from '@/types'
import { CHANNEL_FIELD_DEFS } from '@/types'

export interface AccessControlValue {
  dm_policy: string
  group_policy: string
  allow_from: string
  deny_message: string
  require_mention: boolean
}

export interface RenderConfigValue {
  filter_thinking: boolean
  filter_tool_messages: boolean
  message_format: string
}

export const defaultAccessControl = (): AccessControlValue => ({
  dm_policy: 'open',
  group_policy: 'open',
  allow_from: '',
  deny_message: '',
  require_mention: false,
})

export const defaultRenderConfig = (): RenderConfigValue => ({
  filter_thinking: true,
  filter_tool_messages: true,
  message_format: 'auto',
})

// Browser-rendered channels stream structured message parts over SSE and let
// the client decide what to draw. They never go through the adapter's outbound
// text render path, where message-filter config is read.
const BROWSER_RENDERED_TYPES = new Set(['web', 'webchat'])

export function supportsChannelMessageFilter(channelType?: string | null): boolean {
  return !BROWSER_RENDERED_TYPES.has(channelType || '')
}

export function parseConfigJson(json?: string): Record<string, any> {
  if (!json) return {}
  try { return JSON.parse(json) } catch { return {} }
}

export function extractChannelFields(cfg: Record<string, any>, channelType: string): Record<string, any> {
  const fields: ChannelFieldDef[] = CHANNEL_FIELD_DEFS[channelType] || []
  // Backwards compat: legacy Telegram configs without connection_mode but with
  // webhook_url should be treated as webhook mode.
  if (channelType === 'telegram' && cfg.webhook_url && !cfg.connection_mode) {
    cfg = { ...cfg, connection_mode: 'webhook' }
  }
  const result: Record<string, any> = {}
  for (const f of fields) {
    if (cfg[f.key] !== undefined) result[f.key] = cfg[f.key]
    else if (f.defaultValue !== undefined) result[f.key] = f.defaultValue
  }
  return result
}

export function extractAccessControl(cfg: Record<string, any>): AccessControlValue {
  const d = defaultAccessControl()
  return {
    dm_policy: cfg.dm_policy || d.dm_policy,
    group_policy: cfg.group_policy || d.group_policy,
    allow_from: Array.isArray(cfg.allow_from) ? cfg.allow_from.join(', ') : (cfg.allow_from || ''),
    deny_message: cfg.deny_message || d.deny_message,
    require_mention: cfg.require_mention === true,
  }
}

export function extractRenderConfig(cfg: Record<string, any>): RenderConfigValue {
  const d = defaultRenderConfig()
  return {
    filter_thinking: cfg.filter_thinking !== false,
    filter_tool_messages: cfg.filter_tool_messages !== false,
    message_format: cfg.message_format || d.message_format,
  }
}

export interface BuildConfigJsonInput {
  channelType: string
  channelConfig: Record<string, any>
  accessControl: AccessControlValue
  renderConfig: RenderConfigValue
}

export function buildConfigJson(input: BuildConfigJsonInput): string {
  const cfg: Record<string, any> = {}

  const fields: ChannelFieldDef[] = CHANNEL_FIELD_DEFS[input.channelType] || []
  for (const f of fields) {
    const val = input.channelConfig[f.key]
    if (val !== undefined && val !== '' && val !== null) cfg[f.key] = val
  }

  cfg.dm_policy = input.accessControl.dm_policy
  cfg.group_policy = input.accessControl.group_policy
  cfg.allow_from = input.accessControl.allow_from
    ? input.accessControl.allow_from.split(/[,，]/).map((s: string) => s.trim()).filter(Boolean)
    : []
  cfg.deny_message = input.accessControl.deny_message
  cfg.require_mention = input.accessControl.require_mention

  cfg.filter_thinking = input.renderConfig.filter_thinking
  cfg.filter_tool_messages = input.renderConfig.filter_tool_messages
  cfg.message_format = input.renderConfig.message_format

  return JSON.stringify(cfg, null, 2)
}
