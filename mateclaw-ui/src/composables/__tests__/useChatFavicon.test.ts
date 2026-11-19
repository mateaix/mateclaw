import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, shallowRef } from 'vue'
import type { ChatTitleContext, ChatTitleStatus } from '../useChatDocumentTitle'
import { useChatFavicon } from '../useChatFavicon'
const scopes: ReturnType<typeof effectScope>[] = []
const images: { onload: (() => void) | null; onerror: (() => void) | null; src: string }[] = []
const symbols: string[] = []
let serial = 0
beforeEach(() => {
  document.head.innerHTML = '<link rel="icon" href="/logo/favicon.ico" type="image/x-icon">'
  images.length = 0; symbols.length = 0; serial = 0
  vi.stubGlobal('Image', class {
    onload: (() => void) | null = null
    onerror: (() => void) | null = null
    src = ''
    constructor() { images.push(this) }
  })
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    drawImage:vi.fn(), beginPath:vi.fn(), arc:vi.fn(), fill:vi.fn(), stroke:vi.fn(),
    fillText:(value: string) => symbols.push(value),
  } as unknown as CanvasRenderingContext2D)
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockImplementation(() => `data:image/png;base64,icon${++serial}`)
})
afterEach(() => { scopes.splice(0).forEach(s => s.stop()); vi.restoreAllMocks(); vi.unstubAllGlobals(); document.head.innerHTML = '' })
function mount(status: ChatTitleStatus = 'idle') {
  const context = shallowRef<ChatTitleContext | null>({ conversationId:'a', name:'A', status })
  const scope = effectScope(); scopes.push(scope)
  scope.run(() => useChatFavicon(context))
  return context
}
const icon = () => document.querySelector<HTMLLinkElement>('link[rel="icon"]')
const load = () => images.at(-1)?.onload?.()
describe('chat status favicon', () => {
  it('leaves the original icon untouched for idle pages', () => {
    mount(); expect(icon()?.getAttribute('href')).toBe('/logo/favicon.ico'); expect(images).toHaveLength(0)
  })
  it.each(['running','completed','awaiting_approval','incomplete','failed','stopped'] as ChatTitleStatus[])('renders %s over the brand icon and restores its attributes', async status => {
    const context = mount(status); load()
    expect(icon()?.getAttribute('href')).toBe('data:image/png;base64,icon1')
    expect(icon()?.getAttribute('type')).toBe('image/png')
    expect(symbols).toHaveLength(1)
    context.value = null; await nextTick()
    expect(icon()?.getAttribute('href')).toBe('/logo/favicon.ico')
    expect(icon()?.getAttribute('type')).toBe('image/x-icon')
  })
  it('ignores delayed image loads from a previous chat and releases on disposal', async () => {
    const context = mount('running'); const staleLoad = images[0]!.onload!
    context.value = {conversationId:'b',name:'B',status:'failed'}; await nextTick(); load()
    const currentHref = icon()?.getAttribute('href'); staleLoad()
    expect(icon()?.getAttribute('href')).toBe(currentHref); expect(symbols).toEqual(['×'])
    scopes[0]!.stop(); expect(icon()?.getAttribute('href')).toBe('/logo/favicon.ico')
  })
  it('removes only the temporary icon if the page had none', async () => {
    icon()?.remove(); const context = mount('running'); load()
    expect(icon()).not.toBeNull(); context.value = null; await nextTick(); expect(icon()).toBeNull()
  })
  it('does not add a duplicate if another feature inserts an icon while loading', () => {
    icon()?.remove(); mount('running')
    const external = document.createElement('link'); external.rel = 'icon'; external.href = '/custom.ico'
    document.head.appendChild(external); load()
    expect(document.querySelectorAll('link[rel="icon"]')).toHaveLength(1)
    expect(icon()?.getAttribute('href')).toBe('/custom.ico')
  })
  it('retains the original icon when image loading or canvas rendering fails', () => {
    mount('running'); images[0]?.onerror?.(); expect(icon()?.getAttribute('href')).toBe('/logo/favicon.ico')
    vi.mocked(HTMLCanvasElement.prototype.toDataURL).mockImplementation(() => { throw new Error('tainted') }); load()
    expect(icon()?.getAttribute('href')).toBe('/logo/favicon.ico')
  })
  it('does not overwrite an icon replaced by another feature during cleanup', () => {
    mount('running'); load(); icon()!.setAttribute('href','/custom.ico'); scopes[0]!.stop()
    expect(icon()?.getAttribute('href')).toBe('/custom.ico')
  })
})
