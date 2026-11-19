import { afterEach, expect, it } from 'vitest'
import { readAnswerQuote, quoteIntoDraft } from '../answerQuote'

let root: HTMLElement
function setup() {
  root = document.createElement('div')
  root.innerHTML = `<div class="message-wrapper" data-role="assistant" data-status="completed" data-message-id="42" data-conversation-id="conv">
    <div data-quote-answer><p>第一段回答</p><p>第二段回答</p></div><div class="thinking-content">私有思考</div>
  </div><div class="message-wrapper" data-role="user" data-message-id="43" data-conversation-id="conv"><div data-quote-answer>用户内容</div></div>`
  document.body.append(root)
  return root
}
function select(start: Node, end = start) {
  const range = document.createRange(); range.setStart(start,0); range.setEnd(end,end.textContent?.length || 0)
  const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range); return selection
}
afterEach(() => { window.getSelection()?.removeAllRanges(); root?.remove() })
it('quotes only selected assistant answer text with source identity', () => {
  setup(); const node = root.querySelector('p')!.firstChild!
  expect(readAnswerQuote(select(node),root)).toEqual({ text:'第一段回答',messageId:'42',conversationId:'conv' })
})
it('supports multiple paragraphs inside one answer body', () => {
  setup(); const paragraphs = root.querySelectorAll('p')
  expect(readAnswerQuote(select(paragraphs[0]!.firstChild!,paragraphs[1]!.firstChild!),root)?.text).toContain('第二段回答')
})
it('rejects user content, thinking, cross-body ranges and outside selections', () => {
  setup()
  const thinking = root.querySelector('.thinking-content')!.firstChild!
  const user = root.querySelector('[data-role="user"] [data-quote-answer]')!.firstChild!
  expect(readAnswerQuote(select(thinking),root)).toBeNull()
  expect(readAnswerQuote(select(user),root)).toBeNull()
  expect(readAnswerQuote(select(root.querySelector('p')!.firstChild!,thinking),root)).toBeNull()
  expect(readAnswerQuote(select(document.createTextNode('outside')),root)).toBeNull()
})
it('rejects generating answers and collapsed selection', () => {
  setup(); root.querySelector('[data-role="assistant"]')!.setAttribute('data-status','generating')
  const selected = select(root.querySelector('p')!.firstChild!)
  expect(readAnswerQuote(selected,root)).toBeNull()
  selected.collapseToStart(); expect(readAnswerQuote(selected,root)).toBeNull()
})
it('formats multiline quotation without losing an existing draft', () => {
  expect(quoteIntoDraft('line1\r\nline2','已有追问\n')).toBe('> line1\n> line2\n\n已有追问\n')
  expect(quoteIntoDraft('line','')).toBe('> line\n\n')
})
