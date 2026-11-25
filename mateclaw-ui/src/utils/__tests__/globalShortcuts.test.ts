import { describe, expect, it } from 'vitest'
import { getGlobalShortcutAction } from '../globalShortcuts'

function event(code: string, modifiers: Partial<KeyboardEvent> = {}): KeyboardEvent {
  return {
    code,
    altKey: true,
    ctrlKey: false,
    metaKey: false,
    shiftKey: false,
    ...modifiers,
  } as KeyboardEvent
}

describe('global shortcuts', () => {
  it('uses Alt+K and Alt+N without taking browser-reserved Ctrl combinations', () => {
    expect(getGlobalShortcutAction(event('KeyK'), document.body)).toBe('agents')
    expect(getGlobalShortcutAction(event('KeyN'), document.body)).toBe('newChat')
    expect(getGlobalShortcutAction(event('KeyK', { altKey: false, ctrlKey: true }), document.body)).toBeNull()
    expect(getGlobalShortcutAction(event('KeyN', { altKey: false, ctrlKey: true }), document.body)).toBeNull()
  })

  it('rejects extra modifiers and unrelated keys', () => {
    expect(getGlobalShortcutAction(event('KeyK', { shiftKey: true }), document.body)).toBeNull()
    expect(getGlobalShortcutAction(event('KeyN', { metaKey: true }), document.body)).toBeNull()
    expect(getGlobalShortcutAction(event('KeyP'), document.body)).toBeNull()
  })

  it('leaves editable targets alone', () => {
    const input = document.createElement('input')
    const textarea = document.createElement('textarea')
    const select = document.createElement('select')
    const contentEditable = document.createElement('div')
    contentEditable.contentEditable = 'true'

    for (const target of [input, textarea, select, contentEditable]) {
      expect(getGlobalShortcutAction(event('KeyK'), target)).toBeNull()
      expect(getGlobalShortcutAction(event('KeyN'), target)).toBeNull()
    }
  })
})
