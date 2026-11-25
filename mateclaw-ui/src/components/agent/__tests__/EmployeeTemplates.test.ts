import { createApp, nextTick, type Component } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { agentContextApi, customTemplateApi } from '@/api'
import SaveDialog from '../EmployeeTemplateSaveDialog.vue'
import TemplatePanel from '../WorkspaceTemplatePanel.vue'
import zh from '@/i18n/locales/zh-CN'
import en from '@/i18n/locales/en-US'

const cleanups: Array<() => void> = []
afterEach(() => { cleanups.splice(0).forEach(fn => fn()); vi.restoreAllMocks() })
function mount(component: Component, props: Record<string, unknown> = {}, locale = 'en-US') {
  const host = document.createElement('div'); document.body.appendChild(host)
  const app = createApp(component, props)
  app.use(createI18n({ legacy: false, locale, messages: { 'en-US': en, 'zh-CN': zh } }))
  app.mount(host); cleanups.push(() => { app.unmount(); host.remove() })
  return host
}
async function setInput(host: HTMLElement, selector: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(selector)!
  input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
}
const source = { id: '9007199254740993123', name: 'Researcher', description: 'Find evidence', runtimeType: 'native' }
const row = { id: '9007199254740993124', name: 'Research template', description: 'Evidence workflow', icon: '', agentType: 'react', canDelete: true }

describe('saving employee templates', () => {
  it('offers only shared seed documents and saves explicit selections without snapshot content', async () => {
    vi.spyOn(agentContextApi, 'listFiles').mockResolvedValue({ data: [
      { filename: 'SOUL.md', scope: 'TEAM' }, { filename: 'AGENTS.md', scope: 'GLOBAL' },
      { filename: 'TOOLS.md', scope: 'PERSONAL' }, { filename: 'MEMORY.md', scope: 'TEAM' },
      { filename: '.env', scope: 'TEAM' }, { filename: 'memory/day.md', scope: 'TEAM' },
    ] } as never)
    const save = vi.spyOn(customTemplateApi, 'save').mockResolvedValue({ data: row } as never)
    const saved = vi.fn(); const host = mount(SaveDialog, { source, onSaved: saved })
    await vi.waitFor(() => expect(host.querySelectorAll('[data-template-file]')).toHaveLength(2))
    expect(host.textContent).not.toContain('MEMORY.md')
    expect(host.textContent).not.toContain('TOOLS.md')
    const checkbox = host.querySelector<HTMLInputElement>('[data-template-file="SOUL.md"]')!
    checkbox.click(); await nextTick()
    await setInput(host, '[data-template-name]', 'Reusable researcher')
    host.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await vi.waitFor(() => expect(saved).toHaveBeenCalled())
    expect(save).toHaveBeenCalledWith({ sourceAgentId: source.id, name: 'Reusable researcher', description: source.description, workspaceFileNames: ['SOUL.md'] })
  })

  it('keeps the dialog open and shows server validation errors', async () => {
    vi.spyOn(agentContextApi, 'listFiles').mockResolvedValue({ data: [] } as never)
    vi.spyOn(customTemplateApi, 'save').mockRejectedValue(new Error('Source is unavailable'))
    const saved = vi.fn(); const host = mount(SaveDialog, { source, onSaved: saved }, 'zh-CN')
    await vi.waitFor(() => expect(host.querySelector('[data-template-save]')?.hasAttribute('disabled')).toBe(false))
    host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await vi.waitFor(() => expect(host.textContent).toContain('Source is unavailable'))
    expect(saved).not.toHaveBeenCalled()
  })
})

describe('recruiting from workspace templates', () => {
  it('uses a custom employee name and preserves large ids', async () => {
    vi.spyOn(customTemplateApi, 'list').mockResolvedValue({ data: [row] } as never)
    const apply = vi.spyOn(customTemplateApi, 'apply').mockResolvedValue({ data: { id: 'new-agent' } } as never)
    const hired = vi.fn(); const host = mount(TemplatePanel, { onHired: hired })
    await vi.waitFor(() => expect(host.textContent).toContain(row.name))
    await setInput(host, '[data-template-hire-name]', 'Researcher two')
    host.querySelector<HTMLButtonElement>('[data-template-hire]')!.click()
    await vi.waitFor(() => expect(hired).toHaveBeenCalled())
    expect(apply).toHaveBeenCalledWith(row.id, { name: 'Researcher two' })
  })

  it('retains a template after dependency failure and hides deletion when forbidden', async () => {
    vi.spyOn(customTemplateApi, 'list').mockResolvedValue({ data: [{ ...row, canDelete: false }] } as never)
    vi.spyOn(customTemplateApi, 'apply').mockRejectedValue(new Error('Missing skill: research'))
    const hired = vi.fn(); const host = mount(TemplatePanel, { onHired: hired })
    await vi.waitFor(() => expect(host.querySelector('[data-template-hire]')).not.toBeNull())
    host.querySelector<HTMLButtonElement>('[data-template-hire]')!.click()
    await vi.waitFor(() => expect(host.textContent).toContain('Missing skill: research'))
    expect(host.textContent).toContain(row.name)
    expect(host.querySelector('[data-template-delete]')).toBeNull()
    expect(hired).not.toHaveBeenCalled()
  })

  it('deletes only after confirmation and retains the row on failure', async () => {
    vi.spyOn(customTemplateApi, 'list').mockResolvedValue({ data: [row] } as never)
    const remove = vi.spyOn(customTemplateApi, 'delete').mockRejectedValueOnce(new Error('Cannot delete template')).mockResolvedValue({} as never)
    const host = mount(TemplatePanel)
    await vi.waitFor(() => expect(host.querySelector('[data-template-delete]')).not.toBeNull())
    host.querySelector<HTMLButtonElement>('[data-template-delete]')!.click(); await nextTick()
    expect(remove).not.toHaveBeenCalled()
    host.querySelector<HTMLButtonElement>('[data-template-confirm-delete]')!.click()
    await vi.waitFor(() => expect(host.textContent).toContain('Cannot delete template'))
    expect(host.textContent).toContain(row.name)
    host.querySelector<HTMLButtonElement>('[data-template-confirm-delete]')!.click()
    await vi.waitFor(() => expect(host.querySelector('[data-workspace-template]')).toBeNull())
  })
})
