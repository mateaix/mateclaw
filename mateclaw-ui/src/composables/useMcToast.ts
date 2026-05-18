import { createApp, h, reactive, TransitionGroup } from 'vue'
import McToast from '@/components/common/McToast.vue'

// Project-native toast — frosted glass, spring slide-in from the top,
// status-colored indicator (check / x / triangle / circle), auto
// dismiss with longer hold for failures so the user has time to read.
//
// Drop-in replacement for ElMessage's success/error/warning/info calls.
// The host element + Vue subtree are mounted lazily on first use so
// nothing renders until a toast is actually fired.

export type McToastType = 'success' | 'error' | 'warning' | 'info'

export interface McToastOptions {
  /** Override auto-dismiss in ms. Default: 3000 (success/info), 4000 (warning), 4500 (error). */
  duration?: number
}

interface ToastEntry {
  id: number
  type: McToastType
  message: string
}

const toasts = reactive<ToastEntry[]>([])
let nextId = 1
let mounted = false

function defaultDuration(type: McToastType): number {
  if (type === 'error') return 4500
  if (type === 'warning') return 4000
  return 3000
}

function ensureHost(): void {
  if (mounted || typeof document === 'undefined') return
  // HMR safety: if a prior module instance left a host behind, drop it
  // so this module's reactive array is what renders.
  document.querySelector('.mc-toast-host')?.remove()
  mounted = true

  const host = document.createElement('div')
  host.className = 'mc-toast-host'
  document.body.appendChild(host)

  // Inject host CSS once — kept here (not scoped to McToast) because the
  // stack itself is a global overlay sibling of the app root, and the
  // enter/leave transition names need to match TransitionGroup's class
  // contract.
  if (!document.querySelector('#mc-toast-host-style')) {
    const style = document.createElement('style')
    style.id = 'mc-toast-host-style'
    style.textContent = `
.mc-toast-host {
  position: fixed;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 3000;
  pointer-events: none;
}
.mc-toast-stack {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.mc-toast-enter-active,
.mc-toast-leave-active {
  transition:
    transform 0.32s cubic-bezier(0.32, 0.72, 0, 1),
    opacity 0.22s ease;
}
.mc-toast-enter-from {
  opacity: 0;
  transform: translateY(-12px);
}
.mc-toast-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
.mc-toast-move {
  transition: transform 0.28s cubic-bezier(0.32, 0.72, 0, 1);
}
`
    document.head.appendChild(style)
  }

  createApp({
    render: () =>
      h(
        TransitionGroup as unknown as any,
        { name: 'mc-toast', tag: 'div', class: 'mc-toast-stack' },
        () =>
          toasts.map((t) =>
            h(McToast, { key: t.id, type: t.type, message: t.message }),
          ),
      ),
  }).mount(host)
}

function remove(id: number): void {
  const idx = toasts.findIndex((t) => t.id === id)
  if (idx >= 0) toasts.splice(idx, 1)
}

function show(type: McToastType, message: string, opts?: McToastOptions): void {
  ensureHost()
  const id = nextId++
  toasts.push({ id, type, message })
  const duration = opts?.duration ?? defaultDuration(type)
  setTimeout(() => remove(id), duration)
}

export const mcToast = {
  success(message: string, opts?: McToastOptions): void {
    show('success', message, opts)
  },
  error(message: string, opts?: McToastOptions): void {
    show('error', message, opts)
  },
  warning(message: string, opts?: McToastOptions): void {
    show('warning', message, opts)
  },
  info(message: string, opts?: McToastOptions): void {
    show('info', message, opts)
  },
}
