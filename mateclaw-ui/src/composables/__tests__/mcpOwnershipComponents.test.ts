import { createApp, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
import McpCard from '@/views/mcp/McpCard.vue'
import McpFormModal from '@/views/mcp/McpFormModal.vue'
import type { McpServer } from '@/views/mcp/types'
it('labels shared servers and disables global mutation controls', () => {
  const el = document.createElement('div'); const edit = vi.fn()
  const app = createApp(McpCard, { server: { id: '9223372036854775800', workspaceId: null, name: 'shared', enabled: true } as McpServer, canManage: false, onEdit: edit })
  app.mount(el)
  expect(el.textContent).toContain('mcp.ownership.global')
  expect(el.querySelector<HTMLInputElement>('input[type=checkbox]')!.disabled).toBe(true)
  expect(el.querySelector<HTMLButtonElement>('.mcp-tier-pill')!.disabled).toBe(true)
  el.querySelector<HTMLElement>('.mcp-card')!.click(); expect(edit).not.toHaveBeenCalled(); app.unmount()
})
it('lets a system administrator migrate a legacy global server into current workspace', async () => {
  const el = document.createElement('div'); const save = vi.fn()
  const app = createApp(McpFormModal, { modelValue: true, isGlobalAdmin: true, editing: { id: '9223372036854775800', workspaceId: null, name: 'shared', transport: 'stdio', command: 'test', enabled: true } as McpServer, onSave: save })
  app.mount(el)
  const select = el.querySelector<HTMLSelectElement>('select')!
  expect(select.selectedIndex).toBe(1)
  select.selectedIndex = 0; select.dispatchEvent(new Event('change')); await nextTick()
  el.querySelector<HTMLButtonElement>('.btn-primary')!.click()
  expect(save.mock.calls[0]![0].global).toBe(false); app.unmount()
})
