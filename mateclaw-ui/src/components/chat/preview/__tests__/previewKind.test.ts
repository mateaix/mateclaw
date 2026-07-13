import { describe, it, expect } from 'vitest'
import { previewKindOf, textFlavorOf, extensionOf } from '../previewKind'

describe('previewKindOf', () => {
  it('routes PDF by extension and by MIME', () => {
    expect(previewKindOf({ name: 'report.pdf', contentType: '' })).toBe('pdf')
    expect(previewKindOf({ name: 'nomatch', contentType: 'application/pdf' })).toBe('pdf')
  })

  it('routes docx to the client-side docx renderer', () => {
    expect(previewKindOf({ name: 'a.docx', contentType: '' })).toBe('docx')
  })

  it('routes xlsx and csv to the sheet renderer', () => {
    expect(previewKindOf({ name: 'a.xlsx', contentType: '' })).toBe('sheet')
    expect(previewKindOf({ name: 'data.csv', contentType: '' })).toBe('sheet')
  })

  it('routes html to the sandboxed html renderer', () => {
    expect(previewKindOf({ name: 'page.html', contentType: '' })).toBe('html')
    expect(previewKindOf({ name: 'page.htm', contentType: '' })).toBe('html')
  })

  it('routes markdown / code / text / text-MIME to the text renderer', () => {
    expect(previewKindOf({ name: 'notes.md', contentType: '' })).toBe('text')
    expect(previewKindOf({ name: 'app.ts', contentType: '' })).toBe('text')
    expect(previewKindOf({ name: 'log.txt', contentType: '' })).toBe('text')
    expect(previewKindOf({ name: 'weird', contentType: 'text/plain' })).toBe('text')
  })

  it('routes legacy/binary office formats to server-side conversion', () => {
    for (const ext of ['pptx', 'ppt', 'doc', 'xls', 'odt', 'ods', 'odp', 'rtf', 'wps']) {
      expect(previewKindOf({ name: `f.${ext}`, contentType: '' })).toBe('office')
    }
  })

  it('returns null (download-only) for unknown binary formats', () => {
    expect(previewKindOf({ name: 'archive.zip', contentType: 'application/zip' })).toBeNull()
    expect(previewKindOf({ name: 'firmware.bin', contentType: '' })).toBeNull()
    expect(previewKindOf({ name: 'noextension', contentType: '' })).toBeNull()
  })

  it('does not treat images/video/audio/model as document previews (handled elsewhere)', () => {
    // These carry image/* etc. MIME and are rendered by MessageBubble's own
    // branches; previewKindOf only sees the fileAttachments residue, but guard
    // anyway that a stray image name is not mis-routed to a doc kind.
    expect(previewKindOf({ name: 'pic.png', contentType: 'image/png' })).toBeNull()
  })
})

describe('textFlavorOf', () => {
  it('classifies markdown, code, and plain text', () => {
    expect(textFlavorOf('a.md')).toBe('markdown')
    expect(textFlavorOf('a.ts')).toBe('code')
    expect(textFlavorOf('a.json')).toBe('code')
    expect(textFlavorOf('a.txt')).toBe('plain')
    expect(textFlavorOf('a.unknown')).toBe('plain')
  })
})

describe('extensionOf', () => {
  it('lowercases and handles dotless names', () => {
    expect(extensionOf('Report.PDF')).toBe('pdf')
    expect(extensionOf('name.tar.gz')).toBe('gz')
    expect(extensionOf('noext')).toBe('')
    expect(extensionOf(undefined)).toBe('')
  })
})
