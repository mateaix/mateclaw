import { describe, expect, it } from 'vitest'
import chatConsoleSource from '../ChatConsole.vue?raw'

describe('worker conversation write guards', () => {
  it('guards regenerate rewind and file upload handlers in readonly worker sessions', () => {
    for (const handler of [
      'handleRegenerate',
      'handleRewind',
      'handleFileSelect',
      'processDroppedItems',
      'handleDirectoryAttach',
    ]) {
      const start = chatConsoleSource.indexOf(`function ${handler}`)
      expect(start).toBeGreaterThan(-1)
      const body = chatConsoleSource.slice(start, start + 500)
      expect(body).toContain('workerConversationReadOnly.value')
    }
  })

  it('passes readonly state into the message list', () => {
    expect(chatConsoleSource).toContain(':readonly="workerConversationReadOnly"')
  })
})
