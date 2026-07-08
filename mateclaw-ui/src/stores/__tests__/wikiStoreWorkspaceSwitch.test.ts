// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// vi.mock 在顶层会被提升，工厂内部不能引用外部变量，所以直接返回固定
// 的 mock 实现，具体的返回数据在测试用例中通过 mockResolvedValue 设置。
vi.mock('@/api/index', () => ({
  wikiApi: {
    listKBs: vi.fn(),
    listRaw: vi.fn().mockResolvedValue({ data: [] }),
    listPages: vi.fn().mockResolvedValue({ data: [] }),
    listPageRefs: vi.fn().mockResolvedValue({ data: { items: [] } }),
    getBrokenLinksReport: vi.fn().mockRejectedValue({ code: 404 }),
    getPageTypeProfile: vi.fn().mockRejectedValue(new Error('no profile')),
  },
}))

// 必须在 mock 之后导入，否则 store 初始化时拿到的是真实 wikiApi
import { useWikiStore } from '../useWikiStore'
import { wikiApi } from '@/api/index'

const KB_WS_A = { id: 100, name: 'WS-A-Wiki' }
const KB_WS_B = { id: 200, name: 'WS-B-Wiki' }

describe('useWikiStore 工作区切换 KB 清理', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('切换工作区后 currentKB 不在新列表中时清理旧 KB 上下文', async () => {
    const store = useWikiStore()
    // 模拟工作区 A：已选中 KB 并加载了 pages / pageRefs
    store.currentKB = KB_WS_A as any
    store.pages = [{ id: 1, title: 'page-A' }] as any
    store.pageRefs = [{ slug: 'page-a', title: 'Page A', archived: false }] as any

    // 切到工作区 B：listKBs 返回的列表不含 id=100
    ;(wikiApi.listKBs as any).mockResolvedValue({ data: [KB_WS_B] })

    await store.fetchKnowledgeBases()

    // backToLibrary 应被触发：currentKB / pages / pageRefs 全部归零
    expect(store.currentKB).toBeNull()
    expect(store.pages).toEqual([])
    expect(store.pageRefs).toEqual([])
    // 新列表已写入
    expect(store.knowledgeBases).toEqual([KB_WS_B])
    expect(store.loading).toBe(false)
  })

  it('同一工作区刷新 KB 列表时保留当前 currentKB', async () => {
    const store = useWikiStore()
    store.currentKB = KB_WS_A as any
    store.pages = [{ id: 1, title: 'page-A' }] as any

    // 同一工作区：listKBs 返回的列表包含 id=100
    ;(wikiApi.listKBs as any).mockResolvedValue({
      data: [KB_WS_A, { id: 101, name: 'WS-A-Wiki-2' }],
    })

    await store.fetchKnowledgeBases()

    // currentKB 保留，pages 保留（不误清理）
    expect(store.currentKB).not.toBeNull()
    expect(store.currentKB?.id).toBe(100)
    expect(store.pages).toEqual([{ id: 1, title: 'page-A' }])
  })

  it('首次进入（无 currentKB）时不触发 backToLibrary', async () => {
    const store = useWikiStore()
    // currentKB 初始为 null
    expect(store.currentKB).toBeNull()

    ;(wikiApi.listKBs as any).mockResolvedValue({ data: [KB_WS_A, KB_WS_B] })

    await store.fetchKnowledgeBases()

    expect(store.knowledgeBases).toHaveLength(2)
    expect(store.loading).toBe(false)
    // 无异常即可，backToLibrary 对 null currentKB 本身也是安全空操作
  })

  it('listKBs 接口异常时不崩溃且 loading 复位', async () => {
    const store = useWikiStore()
    store.currentKB = KB_WS_A as any

    ;(wikiApi.listKBs as any).mockRejectedValue(new Error('network down'))

    await store.fetchKnowledgeBases()

    // 异常路径：catch 吞错，knowledgeBases 不变，loading 复位
    expect(store.loading).toBe(false)
    expect(store.currentKB?.id).toBe(100)
  })

  it('selectKB 后切换工作区触发 fetchKnowledgeBases 能正确清理', async () => {
    // 验证 selectKB → fetchKnowledgeBases 的组合路径
    const store = useWikiStore()
    ;(wikiApi.listKBs as any).mockResolvedValue({ data: [KB_WS_B] })

    // 先在工作区 A 选了 KB
    store.currentKB = KB_WS_A as any
    store.pages = [{ id: 1, title: 'page-A' }] as any

    await store.fetchKnowledgeBases()

    // 旧 KB 上下文被清理
    expect(store.currentKB).toBeNull()
    expect(store.pages).toEqual([])
  })
})
