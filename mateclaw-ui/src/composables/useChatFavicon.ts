import { watchEffect, type Ref } from 'vue'
import type { ChatTitleContext, ChatTitleStatus } from './useChatDocumentTitle'

const badges: Record<Exclude<ChatTitleStatus, 'idle'>, { color: string; symbol: string }> = {
  running: { color: '#0284c7', symbol: '▶' },
  completed: { color: '#16a34a', symbol: '✓' },
  awaiting_approval: { color: '#d97706', symbol: 'Ⅱ' },
  incomplete: { color: '#d97706', symbol: '!' },
  failed: { color: '#dc2626', symbol: '×' },
  stopped: { color: '#64748b', symbol: '■' },
}

/** Shares the title's state; no polling, animation timers, or extra chat lifecycle. */
export function useChatFavicon(context: Ref<ChatTitleContext | null>) {
  watchEffect(onCleanup => {
    const chat = context.value
    if (!chat || chat.status === 'idle') return
    const badge = badges[chat.status]
    const existing = document.querySelector<HTMLLinkElement>('link[rel~="icon"]')
    const icon = existing ?? document.createElement('link')
    const originalHref = icon.getAttribute('href')
    const originalType = icon.getAttribute('type')
    const image = new Image()
    let cancelled = false
    let writtenHref: string | null = null

    image.onload = () => {
      if (cancelled) return
      try {
        const canvas = document.createElement('canvas')
        canvas.width = canvas.height = 32
        const ctx = canvas.getContext('2d')
        if (!ctx) return
        ctx.drawImage(image, 0, 0, 32, 32)
        ctx.beginPath()
        ctx.arc(25, 25, 7, 0, Math.PI * 2)
        ctx.fillStyle = badge.color
        ctx.fill()
        ctx.lineWidth = 2
        ctx.strokeStyle = '#ffffff'
        ctx.stroke()
        ctx.fillStyle = '#ffffff'
        ctx.font = 'bold 11px sans-serif'
        ctx.textAlign = 'center'
        ctx.textBaseline = 'middle'
        ctx.fillText(badge.symbol, 25, 25)
        const href = canvas.toDataURL('image/png')
        // A different feature may replace the icon while the logo is loading.
        if (existing && (icon.getAttribute('href') !== originalHref || !icon.isConnected)) return
        if (!existing && document.querySelector('link[rel~="icon"]')) return
        writtenHref = href
        icon.rel = existing?.rel || 'icon'
        icon.type = 'image/png'
        icon.href = href
        if (!existing) document.head.appendChild(icon)
      } catch {
        // A missing/remote logo or unavailable canvas must leave the original icon intact.
      }
    }
    image.src = originalHref || '/logo/favicon.ico'

    onCleanup(() => {
      cancelled = true
      image.onload = image.onerror = null
      if (writtenHref === null || icon.getAttribute('href') !== writtenHref) return
      if (!existing) { icon.remove(); return }
      if (originalHref === null) icon.removeAttribute('href')
      else icon.setAttribute('href', originalHref)
      if (originalType === null) icon.removeAttribute('type')
      else icon.setAttribute('type', originalType)
    })
  })
}
