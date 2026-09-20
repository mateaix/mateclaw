import { describe, expect, it } from 'vitest'
import { planSkillFileUploads } from '../skillFileUpload'

function file(name: string, relative = '', size = 1): File {
  const result = new File(['x'], name)
  Object.defineProperty(result, 'webkitRelativePath', { value: relative })
  Object.defineProperty(result, 'size', { value: size })
  return result
}

describe('skill file uploads', () => {
  it('keeps Unicode folder structure and original files under the chosen bucket', () => {
    const document = file('报告.docx', '制度/部门/报告.docx')
    expect(planSkillFileUploads([document], 'references')).toEqual([
      { file: document, path: 'references/制度/部门/报告.docx' },
    ])
    expect(planSkillFileUploads([file('report.xlsx')], 'templates')[0].path).toBe('templates/report.xlsx')
  })
  it('rejects traversal, absolute paths, duplicate paths and control characters', () => {
    for (const path of ['../a', '/tmp/a', 'x/./a', 'x//a', 'x\\a', 'x\0a', 'C:/a']) {
      expect(() => planSkillFileUploads([file('a', path)], 'references')).toThrow('invalidPath')
    }
    expect(() => planSkillFileUploads([file('a'), file('a')], 'references')).toThrow('invalidPath')
  })
  it('bounds individual files and batches before sending any requests', () => {
    expect(() => planSkillFileUploads([file('a', '', 10 * 1024 * 1024 + 1)], 'references')).toThrow('fileLimit')
    expect(() => planSkillFileUploads(Array.from({ length: 101 }, (_, i) => file(String(i))), 'references')).toThrow('batchLimit')
    expect(() => planSkillFileUploads(Array.from({ length: 6 }, (_, i) => file(String(i), '', 10 * 1024 * 1024)), 'references')).toThrow('batchLimit')
  })
})
