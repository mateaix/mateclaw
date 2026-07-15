import { afterEach, describe, expect, it, vi } from 'vitest'

import { http, wikiApi } from '@/api'

describe('wikiApi.uploadRaw', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('uses the dedicated five-minute upload timeout instead of the global request timeout', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { id: 'raw-1' } })
    const formData = new FormData()
    formData.append('file', new File(['fixture'], 'fixture.txt', { type: 'text/plain' }))

    await wikiApi.uploadRaw(42, formData)

    expect(post).toHaveBeenCalledWith(
      '/wiki/knowledge-bases/42/raw/upload',
      formData,
      expect.objectContaining({ timeout: 300_000 }),
    )
  })
})
