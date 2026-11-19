import type { MessageSegment } from '@/types'

export interface ShellFailure {
  reason: 'exit' | 'timeout' | 'cancelled' | 'offline' | 'failed'
  exitCode?: number
}

/** Compatibility for historical shell events whose transport success ignored command failure. */
export function shellToolFailure(segment: MessageSegment): ShellFailure | null {
  if (!['execute_shell_command', 'local_execute_shell'].includes(segment.toolName || '')
      || !['completed', 'error'].includes(segment.status)) return null
  let value: unknown = segment.toolResult
  try {
    // Spring tool callbacks can serialize a returned JSON string once more.
    for (let i = 0; i < 2 && typeof value === 'string'; i++) value = JSON.parse(value)
  } catch { value = null }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const result = value as Record<string, unknown>
    if (result.cancelled === true) return { reason: 'cancelled' }
    if (result.timedOut === true) return { reason: 'timeout' }
    if (result.error === true && result.code === 'OFFLINE') return { reason: 'offline' }
    if (typeof result.exitCode === 'number' && Number.isInteger(result.exitCode) && result.exitCode !== 0)
      return { reason: 'exit', exitCode: result.exitCode }
    if (result.error === true || typeof result.error === 'string' && result.error.length > 0)
      return { reason: 'failed' }
  }
  return segment.status === 'error' || segment.toolSuccess === false ? { reason: 'failed' } : null
}
