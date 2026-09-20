import { afterEach, describe, expect, it, vi } from 'vitest'
import { http, skillApi } from '@/api/index'

describe('skill attachment API', () => {
  afterEach(() => vi.restoreAllMocks())

  it('sends the original file and relative path without coercing large skill IDs', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({} as never)
    const file = new File([new Uint8Array([80, 75, 0, 255])], '模板.xlsx')
    await skillApi.uploadFile('9007199254740993', file, 'templates/部门/模板.xlsx')
    const [url, body] = post.mock.calls[0]
    expect(url).toBe('/skills/9007199254740993/files/upload')
    const form = body as FormData
    expect(form.get('path')).toBe('templates/部门/模板.xlsx')
    expect(form.get('overwrite')).toBe('false')
    expect(await (form.get('file') as File).arrayBuffer()).toEqual(await file.arrayBuffer())
    await skillApi.uploadFile('9007199254740993', file, 'templates/部门/模板.xlsx', true)
    expect((post.mock.calls[1][1] as FormData).get('overwrite')).toBe('true')
  })

  it('downloads authenticated bytes through the shared HTTP client', async () => {
    const blob = new Blob([new Uint8Array([0, 255])])
    const get = vi.spyOn(http, 'get').mockResolvedValue(blob as never)
    expect(await skillApi.downloadFile('9007199254740993', 'references/a.docx')).toBe(blob)
    expect(get).toHaveBeenCalledWith('/skills/9007199254740993/files/download', {
      params: { path: 'references/a.docx' }, responseType: 'blob',
    })
  })
})
