import test from 'node:test'
import assert from 'node:assert/strict'
import {
  filterAgentBindingItems,
  filterAgentToolGroups,
} from '../src/utils/agentBindingSearch.ts'

test('filterAgentBindingItems matches skills by name, description, and version', () => {
  const skills = [
    { id: 1, name: 'Contract Review', description: 'Legal workflow', version: '1.2.0' },
    { id: 2, name: 'Web Search', description: 'Find current facts', version: '2.0.0' },
    { id: 3, name: 'Data Analyst', description: 'Spreadsheet work', version: null },
  ]

  assert.deepEqual(filterAgentBindingItems(skills, 'legal').map((skill) => skill.id), [1])
  assert.deepEqual(filterAgentBindingItems(skills, '2.0.0').map((skill) => skill.id), [2])
})

test('filterAgentBindingItems keeps all skills in original order for an empty query', () => {
  const skills = [
    { id: 1, name: 'Contract Review' },
    { id: 2, name: 'Web Search' },
  ]

  assert.deepEqual(filterAgentBindingItems(skills, '   ').map((skill) => skill.id), [1, 2])
})

test('filterAgentToolGroups matches tools by group, source, raw name, and description', () => {
  const groups = [
    {
      groupId: 'builtin',
      label: 'Built-in',
      tools: [
        { name: 'datetime', rawName: 'datetime', source: 'builtin', description: 'Current time' },
      ],
    },
    {
      groupId: 'mcp:browser',
      label: 'MCP · browser',
      tools: [
        { name: 'mcp__browser__click', rawName: 'click', source: 'mcp', providerName: 'browser', description: 'Click page elements' },
        { name: 'mcp__browser__screenshot', rawName: 'screenshot', source: 'mcp', providerName: 'browser', description: 'Capture the viewport' },
      ],
    },
  ]

  assert.deepEqual(filterAgentToolGroups(groups, 'browser').map((group) => group.groupId), ['mcp:browser'])
  assert.deepEqual(filterAgentToolGroups(groups, 'viewport')[0].tools.map((tool) => tool.name), ['mcp__browser__screenshot'])
  assert.deepEqual(filterAgentToolGroups(groups, 'built')[0].tools.map((tool) => tool.name), ['datetime'])
})

test('filterAgentToolGroups keeps all groups and tools for an empty query', () => {
  const groups = [
    { groupId: 'builtin', label: 'Built-in', tools: [{ name: 'datetime' }] },
    { groupId: 'orphan', label: 'Bound but no longer available', tools: [{ name: 'old_tool' }] },
  ]

  assert.deepEqual(filterAgentToolGroups(groups, '').map((group) => group.groupId), ['builtin', 'orphan'])
})
