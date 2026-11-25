export type GlobalShortcutAction = 'agents' | 'newChat'

export function getGlobalShortcutAction(
  event: Pick<KeyboardEvent, 'code' | 'altKey' | 'ctrlKey' | 'metaKey' | 'shiftKey'>,
  target: EventTarget | null,
): GlobalShortcutAction | null {
  if (!event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return null
  if (event.code !== 'KeyK' && event.code !== 'KeyN') return null
  if (isEditableTarget(target)) return null
  return event.code === 'KeyK' ? 'agents' : 'newChat'
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  return target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT'
}
