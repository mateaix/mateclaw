// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import type { PickableAgent } from '../AgentPickerDialog.vue'

/**
 * 提取 AgentPickerDialog 中 isUnknown / triggerLabel 的核心判定逻辑做纯函数测试。
 * 这些 computed 的判定决定了触发器在「列表未加载」「员工被删除」等边界下显示什么，
 * 历史上因为缺少 agents.length > 0 的守卫，组件重建瞬间会闪现原始数字 ID（Bug 1）。
 */

const AGENTS: PickableAgent[] = [
  { id: 1000000001, name: '通用助手' },
  { id: 1000000002, name: '代码助手' },
]

// hasValue：复刻组件中的判定
function hasValue(modelValue: string | number | null | undefined): boolean {
  return modelValue !== '' && modelValue !== null && modelValue !== undefined
}

// selectedAgent：复刻组件中的查找
function findSelected(
  modelValue: string | number | null | undefined,
  agents: PickableAgent[],
): PickableAgent | null {
  if (!hasValue(modelValue)) return null
  return agents.find(a => String(a.id) === String(modelValue)) || null
}

// isUnknown：复刻组件中的 computed —— 关键修复点
// hasValue && !selectedAgent && agents.length > 0
function computeIsUnknown(
  modelValue: string | number | null | undefined,
  agents: PickableAgent[],
): boolean {
  const selected = findSelected(modelValue, agents)
  return hasValue(modelValue) && !selected && agents.length > 0
}

// triggerLabel：复刻组件中的优先级链
function computeTriggerLabel(
  modelValue: string | number | null | undefined,
  agents: PickableAgent[],
  placeholder: string,
  unknownLabel: string,
): string {
  const selected = findSelected(modelValue, agents)
  if (selected) return selected.name
  if (computeIsUnknown(modelValue, agents)) return unknownLabel
  return placeholder
}

describe('AgentPickerDialog isUnknown 判定', () => {
  describe('空值场景', () => {
    it('modelValue 为 null 时不判定为未知', () => {
      expect(computeIsUnknown(null, AGENTS)).toBe(false)
    })
    it('modelValue 为空字符串时不判定为未知', () => {
      expect(computeIsUnknown('', AGENTS)).toBe(false)
    })
    it('modelValue 为 undefined 时不判定为未知', () => {
      expect(computeIsUnknown(undefined, AGENTS)).toBe(false)
    })
  })

  describe('正常匹配场景', () => {
    it('数字 id 匹配到员工时不判定为未知', () => {
      expect(computeIsUnknown(1000000001, AGENTS)).toBe(false)
    })
    it('字符串 id 匹配到员工时不判定为未知（Snowflake 精度场景）', () => {
      expect(computeIsUnknown('1000000001', AGENTS)).toBe(false)
    })
  })

  describe('列表为空场景（Bug 1 核心修复点）', () => {
    // 组件被 keepAlive 重建后 /agents 尚在飞行中，此时 agents=[]
    // 旧逻辑：hasValue && !selectedAgent → true → 显示原始 ID
    // 新逻辑：追加 agents.length > 0 → false → 走 placeholder
    it('modelValue 有值但员工列表为空时不判定为未知', () => {
      expect(computeIsUnknown(1000000001, [])).toBe(false)
      expect(computeIsUnknown('1000000001', [])).toBe(false)
    })
    it('modelValue 为无效值且列表为空时也不判定为未知', () => {
      expect(computeIsUnknown(9999999999, [])).toBe(false)
    })
  })

  describe('员工被删除/改名场景', () => {
    it('modelValue 有值、列表非空但无匹配时判定为未知', () => {
      expect(computeIsUnknown(9999999999, AGENTS)).toBe(true)
      expect(computeIsUnknown('9999999999', AGENTS)).toBe(true)
    })
    it('被删除的员工在恢复列表后仍显示未知标记，直到用户重新选择', () => {
      // 模拟：员工 1000000001 被从列表移除
      const reduced = AGENTS.filter(a => a.id !== 1000000001)
      expect(computeIsUnknown(1000000001, reduced)).toBe(true)
    })
  })
})

describe('AgentPickerDialog triggerLabel 优先级链', () => {
  const PLACEHOLDER = '请选择员工'
  const UNKNOWN_LABEL = '未知员工'

  it('选中员工时显示员工名称（最高优先级）', () => {
    expect(computeTriggerLabel(1000000001, AGENTS, PLACEHOLDER, UNKNOWN_LABEL))
      .toBe('通用助手')
  })

  it('列表为空 + modelValue 有值时显示 placeholder（Bug 1 修复核心）', () => {
    // 旧逻辑会显示 "1000000001"，新逻辑走 placeholder
    expect(computeTriggerLabel(1000000001, [], PLACEHOLDER, UNKNOWN_LABEL))
      .toBe(PLACEHOLDER)
  })

  it('列表非空但无匹配时显示 unknownLabel', () => {
    expect(computeTriggerLabel(9999999999, AGENTS, PLACEHOLDER, UNKNOWN_LABEL))
      .toBe(UNKNOWN_LABEL)
  })

  it('未选择时显示 placeholder', () => {
    expect(computeTriggerLabel(null, AGENTS, PLACEHOLDER, UNKNOWN_LABEL))
      .toBe(PLACEHOLDER)
  })
})
