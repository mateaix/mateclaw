/**
 * Shared notification-summary store + poller.
 *
 * Centralizes the counts that drive sidebar attention badges so every
 * subscriber reads from the same cached snapshot — multiple mount/unmount
 * cycles don't multiply HTTP traffic.
 *
 * Non-admin users never poll: the backend's per-user view today returns
 * pendingApprovals only, and stuckAgents requires admin. The existing UI
 * convention (MainLayout) already hides the global attention dot from
 * non-admins, so we mirror it here.
 */
import { computed, onScopeDispose, ref } from 'vue'
import { notificationApi, type NotificationSummary } from '@/api'

const POLL_INTERVAL_MS = 15_000

const summary = ref<NotificationSummary>({
  pendingApprovals: 0,
  stuckAgents: 0,
  failedCrons: 0,
  downChannels: 0,
  downMcps: 0,
})

let refCount = 0
let timer: ReturnType<typeof setInterval> | null = null
let inFlight = false

function isAdminRole(): boolean {
  return (localStorage.getItem('role') || 'user') === 'admin'
}

/**
 * The server's global Jackson config serializes Long as a string for
 * ID precision — counts can come through as either number or stringified
 * number depending on whether the backend casts to int. Coerce here so
 * the badge always sees a real number regardless of which side fixed it.
 */
function toCount(v: unknown): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : 0
  if (typeof v === 'string') {
    const n = Number(v)
    return Number.isFinite(n) ? n : 0
  }
  return 0
}

async function refresh(): Promise<void> {
  if (!isAdminRole()) return
  if (inFlight) return
  inFlight = true
  try {
    const res: any = await notificationApi.summary()
    const raw = (res?.data ?? res) as Partial<NotificationSummary> | undefined
    if (raw) {
      summary.value = {
        pendingApprovals: toCount(raw.pendingApprovals),
        stuckAgents: toCount(raw.stuckAgents),
        failedCrons: toCount(raw.failedCrons),
        downChannels: toCount(raw.downChannels),
        downMcps: toCount(raw.downMcps),
      }
    }
  } catch {
    // Silent: stale counts beat a flapping badge.
  } finally {
    inFlight = false
  }
}

function ensurePolling(): void {
  if (timer || !isAdminRole()) return
  // Fire once immediately so the badge doesn't lag the interval.
  void refresh()
  timer = setInterval(refresh, POLL_INTERVAL_MS)
}

function maybeStopPolling(): void {
  if (refCount > 0) return
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

export function useNotificationCenter() {
  refCount++
  ensurePolling()

  onScopeDispose(() => {
    refCount = Math.max(0, refCount - 1)
    maybeStopPolling()
  })

  return {
    summary: computed(() => summary.value),
    pendingApprovals: computed(() => summary.value.pendingApprovals),
    stuckAgents: computed(() => summary.value.stuckAgents),
    refresh,
  }
}
