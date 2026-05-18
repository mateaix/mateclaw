import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '@/api'

export interface DreamReportItem {
  id: string
  agentId: string | number
  mode: string
  topic: string | null
  triggerSource: string
  triggeredBy: string
  startedAt: string
  finishedAt: string
  candidateCount: number
  promotedCount: number
  rejectedCount: number
  memoryDiff: string | null
  llmReason: string | null
  status: string
  errorMessage: string | null
}

export const useMemoryStore = defineStore('memory', () => {
  const reports = ref<DreamReportItem[]>([])
  const total = ref(0)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const currentReport = ref<DreamReportItem | null>(null)
  let pollTimer: ReturnType<typeof setInterval> | null = null

  async function fetchReports(agentId: string | number, page = 1, size = 20) {
    loading.value = true
    error.value = null
    try {
      const res = await http.get(`/memory/${agentId}/dream/reports`, {
        params: { page, size },
      })
      reports.value = res.data.records || []
      total.value = res.data.total || 0
    } catch (e: any) {
      error.value = e.message || 'Failed to load reports'
      reports.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  async function fetchReport(agentId: string | number, reportId: string) {
    loading.value = true
    try {
      const res = await http.get(`/memory/${agentId}/dream/reports/${reportId}`)
      currentReport.value = res.data
      error.value = null
    } catch (e: any) {
      currentReport.value = null
      error.value = e.message || 'Failed to load report'
    } finally {
      loading.value = false
    }
  }

  /**
   * Poll for new dream events instead of SSE.
   * SSE via EventSource doesn't support Authorization headers,
   * causing 401 errors. Polling every 15s is sufficient for dream events.
   */
  function subscribeEvents(agentId: string | number) {
    unsubscribeEvents()
    pollTimer = setInterval(() => {
      fetchReports(agentId, 1, 20)
    }, 15000)
  }

  function unsubscribeEvents() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  return {
    reports, total, loading, error, currentReport,
    fetchReports, fetchReport,
    subscribeEvents, unsubscribeEvents,
  }
})
