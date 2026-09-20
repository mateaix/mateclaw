import { afterEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/api/index'
import { executionEvidenceApi } from '@/api/executionEvidence'

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('executionEvidenceApi', () => {
  it('preserves opaque cursors and string IDs on read-only endpoints', () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({} as never)
    const params = { conversationId: 'chat/#one', goalId: '9223372036854775802', teamTaskId: '9223372036854775803', cursor: 'opaque/cursor', limit: 20 }
    executionEvidenceApi.list(params)
    executionEvidenceApi.get('9223372036854775804')
    expect(get).toHaveBeenNthCalledWith(1, '/execution-evidence', { params })
    expect(get).toHaveBeenNthCalledWith(2, '/execution-evidence/9223372036854775804')
  })
  it('preserves the permission code from an R envelope', async () => {
    vi.stubGlobal('localStorage', { getItem: () => null })
    await expect(http.get('/execution-evidence', {
      adapter: async config => ({ data: { code: 403, msg: 'Forbidden', data: null }, status: 200, statusText: 'OK', headers: {}, config }),
    })).rejects.toMatchObject({ code: 403, message: 'Forbidden' })
  })
  it('sends explicit requirements while preserving the evidence ID', () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({} as never)
    executionEvidenceApi.checkJson('9223372036854775804', ['report', 'appendix'])
    expect(post).toHaveBeenCalledWith('/execution-evidence/9223372036854775804/json-check', { requiredFields: ['report', 'appendix'] })
  })

})
