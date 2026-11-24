import { afterEach, expect, it } from 'vitest'
import { http, wikiApi } from '@/api'

const originalAdapter = http.defaults.adapter
afterEach(() => { http.defaults.adapter = originalAdapter })

it('allows slow raw downloads without changing the global API timeout', async () => {
  let downloadTimeout: number | undefined
  http.defaults.adapter = async config => {
    downloadTimeout = config.timeout
    return { data: new Blob(['source']), status: 200, statusText: 'OK', headers: {}, config }
  }
  await wikiApi.downloadRaw(42, 43)
  expect(downloadTimeout).toBe(0)
  expect(http.defaults.timeout).toBe(30_000)
})
