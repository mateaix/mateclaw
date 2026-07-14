// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'

/**
 * 复刻 ChatConsole 中模型 seed 的核心判定逻辑做纯函数测试。
 *
 * 修复的 bug：员工编辑页选定的「模型」（per-Agent override）在网页对话里被
 * 全局默认模型覆盖。根因是 applyConversationModel 在会话无 pin 时直接回落全局
 * 默认，随后 handleSendMessage 把该默认 PIN 到会话行——而会话 pin 优先级高于
 * 员工 override，导致员工选的模型被静默覆盖。
 *
 * 修复后 seed 优先级与后端 AgentGraphBuilder.resolveRuntimeBaseModel 对齐：
 *   会话 pin  >  员工 modelName override  >  全局默认
 *
 * 员工 override 这一层有两个来源（复刻 agentSeedModel）：
 *   1. agentCapabilities —— /agents/{id}/capabilities 的后端解析结果（异步、权威）
 *   2. currentAgent.modelName 在 enabledModels 里的同步解析（异步失败/切换/深链兜底）
 */

type Model = { providerId: string; model: string }
type EnabledModel = { provider: string; modelName: string }

// 复刻 agentSeedModel：先用 capabilities，再用 modelName 同步兜底。
// 关键点：capabilities 未配默认模型时后端返回的是空串 ""（见 AgentController
// 的 catch 分支），不是 null —— 空串是 JS falsy，`cap?.providerId && cap?.modelName`
// 会正确落空并走同步兜底。测试特意用 "" 锁住这个 wire 契约。
function agentSeedModel(opts: {
  capProvider?: string | null
  capModel?: string | null
  agentModelName?: string | null
  enabledModels?: EnabledModel[]
}): Model | null {
  if (opts.capProvider && opts.capModel) {
    return { providerId: opts.capProvider, model: opts.capModel }
  }
  const name = opts.agentModelName
  if (name) {
    const hit = (opts.enabledModels || []).find(m => m.modelName === name)
    if (hit?.provider) {
      return { providerId: hit.provider, model: hit.modelName }
    }
  }
  return null
}

// 复刻 applyConversationModel 的取值优先级
function resolveSeedModel(opts: {
  convProvider?: string | null
  convModel?: string | null
  capProvider?: string | null
  capModel?: string | null
  agentModelName?: string | null
  enabledModels?: EnabledModel[]
  globalDefault?: Model | null
}): Model | null {
  if (opts.convProvider && opts.convModel) {
    return { providerId: opts.convProvider, model: opts.convModel }
  }
  const agentModel = agentSeedModel(opts)
  if (agentModel) return agentModel
  if (opts.globalDefault) return { ...opts.globalDefault }
  return null
}

// 复刻 selectedAgentId watcher 中的 re-seed 守卫：
// 仅当会话无服务端 pin 且用户未手动选过模型时，才允许用刚加载的员工能力覆盖。
function shouldReseedFromAgentCaps(opts: {
  convProvider?: string | null
  convModel?: string | null
  userPickedModel: boolean
}): boolean {
  const hasServerPin = !!(opts.convProvider && opts.convModel)
  return !hasServerPin && !opts.userPickedModel
}

const GLOBAL: Model = { providerId: 'openai', model: 'gpt-x' }
const ENABLED: EnabledModel[] = [
  { provider: 'anthropic', modelName: 'claude-x' },
  { provider: 'volcano', modelName: 'doubao-pro' },
]

describe('resolveSeedModel — 模型 seed 优先级', () => {
  it('会话已有 pin 时，优先用会话 pin（压过员工与全局）', () => {
    const r = resolveSeedModel({
      convProvider: 'volcano', convModel: 'doubao-pro',
      capProvider: 'anthropic', capModel: 'claude-x',
      globalDefault: GLOBAL,
    })
    expect(r).toEqual({ providerId: 'volcano', model: 'doubao-pro' })
  })

  it('会话无 pin 时，用 capabilities 的员工 override（不再回落全局默认）', () => {
    const r = resolveSeedModel({
      capProvider: 'anthropic', capModel: 'claude-x',
      globalDefault: GLOBAL,
    })
    expect(r).toEqual({ providerId: 'anthropic', model: 'claude-x' })
  })

  it('capabilities 未就绪（切换/失败/深链）时，用 modelName 在 enabledModels 里同步兜底', () => {
    const r = resolveSeedModel({
      capProvider: null, capModel: null,
      agentModelName: 'claude-x', enabledModels: ENABLED,
      globalDefault: GLOBAL,
    })
    expect(r).toEqual({ providerId: 'anthropic', model: 'claude-x' })
  })

  it('capabilities 与同步兜底都缺失时，回落全局默认', () => {
    const r = resolveSeedModel({
      agentModelName: '', enabledModels: ENABLED,
      globalDefault: GLOBAL,
    })
    expect(r).toEqual(GLOBAL)
  })

  it('员工的 modelName 不在 enabledModels（禁用/删除）时，同步兜底落空 → 全局默认', () => {
    const r = resolveSeedModel({
      agentModelName: 'ghost-model', enabledModels: ENABLED,
      globalDefault: GLOBAL,
    })
    expect(r).toEqual(GLOBAL)
  })

  it('全部缺失时返回 null（无可用模型）', () => {
    expect(resolveSeedModel({ globalDefault: null })).toBeNull()
  })
})

describe('agentSeedModel — 空串 wire 契约（后端无默认模型时返回 "" 而非 null）', () => {
  it('capabilities 为空串对（providerId:"" modelName:""）视为无 override，走同步兜底', () => {
    const r = agentSeedModel({
      capProvider: '', capModel: '',
      agentModelName: 'claude-x', enabledModels: ENABLED,
    })
    expect(r).toEqual({ providerId: 'anthropic', model: 'claude-x' })
  })

  it('半残的 capabilities 对（provider 空串、model 非空）视为无 override', () => {
    const r = agentSeedModel({
      capProvider: '', capModel: 'claude-x',
      agentModelName: null, enabledModels: ENABLED,
    })
    expect(r).toBeNull()
  })

  it('capabilities 完整时优先于同步兜底', () => {
    const r = agentSeedModel({
      capProvider: 'openai', capModel: 'gpt-x',
      agentModelName: 'claude-x', enabledModels: ENABLED,
    })
    expect(r).toEqual({ providerId: 'openai', model: 'gpt-x' })
  })
})

describe('shouldReseedFromAgentCaps — 能力异步到达后的 re-seed 守卫', () => {
  it('新会话、无 pin、用户未手动选 → 允许 re-seed（修复 agent 切换竞态）', () => {
    expect(shouldReseedFromAgentCaps({ userPickedModel: false })).toBe(true)
  })

  it('会话已有服务端 pin → 不 re-seed（不覆盖已固定的会话）', () => {
    expect(shouldReseedFromAgentCaps({
      convProvider: 'volcano', convModel: 'doubao-pro', userPickedModel: false,
    })).toBe(false)
  })

  it('用户已手动选过模型 → 不 re-seed（不覆盖显式选择）', () => {
    expect(shouldReseedFromAgentCaps({ userPickedModel: true })).toBe(false)
  })
})
