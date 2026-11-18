import { createApp, nextTick, defineComponent } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MessageSegment } from '@/types'
vi.mock('@/composables/useToolLabel', () => ({ useToolLabel: () => ({ getToolLabel: (name: string) => name }) }))
vi.mock('../ExecutionDetailDialog.vue', () => ({ default: { template: '<div />' } }))
import ToolCallSegment from '../ToolCallSegment.vue'
import { registerToolResultRenderer } from '../tool-results/registry'
const apps: ReturnType<typeof createApp>[] = []
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); document.body.innerHTML = '' })
function mount(segment: MessageSegment) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(ToolCallSegment, { segment })
  app.config.globalProperties.$t = (key: string) => key
  app.component('el-icon', { template: '<span><slot /></span>' })
  app.mount(host)
  apps.push(app)
  return host
}
const table = { mateclawUi: { version: 1, blocks: [{ type: 'table', title: '<script>title</script>', data: { columns: [{ key: 'value', label: 'Value' }], rows: [{ value: '<img src=x onerror=alert(1)>' }] } }] } }
const segment = (status: MessageSegment['status'] = 'completed'): MessageSegment => ({ id: 'a', type: 'tool_call', toolName: 'search', status, toolResult: 'plain result', structuredContent: table })
describe('structured tool cards', () => {
  it('shows a table immediately while raw details stay collapsed, and escapes text', async () => {
    const host = mount(segment())
    expect(host.querySelector('table')).not.toBeNull()
    expect(host.querySelector('.seg-tool__body')).toBeNull()
    expect(host.querySelector('td')?.textContent).toBe('<img src=x onerror=alert(1)>')
    expect(host.querySelector('script, img')).toBeNull()
    ;(host.querySelector('.seg-tool__header') as HTMLElement).click()
    await nextTick()
    expect(host.querySelector('table')).not.toBeNull()
    expect(host.querySelector('.seg-tool__body')?.textContent).toContain('plain result')
  })
  it('isolates a failing trusted renderer and shows escaped JSON', async () => {
    registerToolResultRenderer('broken', { component: defineComponent({ setup() { throw new Error('Broken renderer') } }), validate: () => true })
    const host = mount({ ...segment(), structuredContent: { mateclawUi: { version: 1, blocks: [{ type: 'broken', data: '<img onerror=bad>' }] } } })
    await nextTick()
    expect(host.querySelector('pre')?.textContent).toContain('<img onerror=bad>')
    expect(host.querySelector('img')).toBeNull()
  })
  it('does not display rich results for running or failed calls', () => {
    for (const value of [segment('running'), segment('error'), { ...segment(), toolSuccess: false }]) expect(mount(value).querySelector('table')).toBeNull()
  })
  it('escapes fallback JSON and renders safe product media without dangerous navigation', () => {
    const value = segment()
    value.structuredContent = { mateclawUi: { version: 1, blocks: [{ type: 'unknown', data: '<script>bad</script>' }, { type: 'product-cards', data: [{ name: '<img onerror=bad>', url: 'javascript:alert(1)', imageUrl: 'data:image/svg+xml,bad', price: 12 }, { name: 'Good', url: 'https://shop.test/p', imageUrl: 'https://shop.test/p.png' }] }] } }
    const host = mount(value)
    expect(host.querySelector('pre')?.textContent).toContain('<script>bad</script>')
    expect(host.querySelector('script')).toBeNull()
    expect(host.querySelectorAll('img')).toHaveLength(1)
    expect(host.querySelectorAll('a')).toHaveLength(1)
    expect(host.querySelector('a')?.getAttribute('rel')).toContain('noopener')
    expect(host.querySelector('a')?.getAttribute('href')).toBe('https://shop.test/p')
  })
})

describe('shell intent titles', () => {
  it('shows a readable intent while retaining the actual command and failure state', async () => {
    const host = mount({ ...segment('error'), toolName: 'execute_shell_command', toolArgs: JSON.stringify({command:'git status --short', title:'检查当前 Git 状态'}), toolResult:'exit 1', toolSuccess:false })
    expect(host.querySelector('.seg-tool__intent')?.textContent).toBe('检查当前 Git 状态')
    expect(host.querySelector('.seg-tool__name')?.textContent).toBe('execute_shell_command')
    expect(host.querySelector('.seg-tool__args')?.textContent).toBe('git status --short')
    expect(host.querySelector('.seg-tool')?.classList.contains('is-error')).toBe(true)
    ;(host.querySelector('.seg-tool__header') as HTMLElement).click(); await nextTick()
    expect(host.querySelector('.seg-tool__body')?.textContent).toContain('git status --short')
  })
  it('escapes and bounds titles, ignoring non-shell and malformed arguments', () => {
    const local = mount({ ...segment(), toolName:'local_execute_shell', toolArgs:JSON.stringify({command:'pwd',title:'<img onerror=bad>\n inspect '+ 'x'.repeat(200)}) })
    expect(local.querySelector('img')).toBeNull()
    expect(local.querySelector('.seg-tool__intent')?.textContent?.length).toBeLessThanOrEqual(121)
    expect(local.querySelector('.seg-tool__intent')?.textContent).not.toContain('\n')
    expect(mount({...segment(),toolArgs:'{"title":"not shell"}'}).querySelector('.seg-tool__intent')).toBeNull()
    expect(mount({...segment(),toolName:'execute_shell_command',toolArgs:'{"title":'}).querySelector('.seg-tool__intent')).toBeNull()
  })
})
