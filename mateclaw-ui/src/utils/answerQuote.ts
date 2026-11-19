export interface AnswerQuote { text: string; messageId: string; conversationId: string }

export function readAnswerQuote(selection: Selection | null, root: HTMLElement): AnswerQuote | null {
  if (!selection || selection.isCollapsed || selection.rangeCount !== 1) return null
  const element = (node: Node | null) => node instanceof Element ? node : node?.parentElement
  const start = element(selection.anchorNode), end = element(selection.focusNode)
  const body = start?.closest<HTMLElement>('[data-quote-answer]')
  if (!body || !end || !body.contains(end) || !root.contains(body)) return null
  const wrapper = body.closest<HTMLElement>('.message-wrapper[data-role="assistant"]')
  if (!wrapper || wrapper.dataset.status === 'generating') return null
  const messageId = wrapper.dataset.messageId, conversationId = wrapper.dataset.conversationId
  const text = selection.toString().trim()
  return messageId && conversationId && text ? { text, messageId, conversationId } : null
}

export function quoteIntoDraft(text: string, draft: string): string {
  return text.replace(/\r\n?/g,'\n').split('\n').map(line => `> ${line}`).join('\n') + '\n\n' + draft
}
