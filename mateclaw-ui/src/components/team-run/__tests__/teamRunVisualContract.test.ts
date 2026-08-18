import { describe, expect, it } from 'vitest'
import detail from '../TeamRunReadingSurface.vue?raw'
import drawer from '../TeamRunDrawer.vue?raw'
import teamsView from '../../../views/Teams.vue?raw'

describe('Team Run visual contract', () => {
  it('keeps dense reading surfaces opaque and responsive', () => {
    expect(detail).toContain('background: var(--mc-team-run-reading-bg')
    expect(detail).toContain('overflow-wrap: anywhere')
    expect(detail).toContain('overflow-x: auto')
    expect(detail).toContain('max-width: 100%')
  })

  it('keeps markdown table cells atomic while allowing horizontal scrolling', () => {
    expect(detail).toContain('.markdown-body th)')
    expect(detail).toContain('.markdown-body td)')
    expect(detail).toContain('overflow-wrap: normal')
    expect(detail).toContain('word-break: normal')
    expect(detail).toContain('white-space: nowrap')
    expect(detail).toContain('width: 100%')
    expect(detail).toContain('width: max-content')
    expect(detail).toContain('min-width: 6.5rem')
  })

  it('limits glass to drawer chrome with accessibility fallbacks', () => {
    expect(drawer).toContain('backdrop-filter: blur(')
    expect(drawer).toContain('@media (prefers-reduced-motion: reduce)')
    expect(drawer).toContain('@media (prefers-reduced-transparency: reduce)')
    expect(drawer).toContain('@supports not (backdrop-filter: blur(1px))')
  })

  it('keeps team header actions compact and readable on mobile', () => {
    expect(teamsView).toContain('class="detail-action-label"')
    expect(teamsView).toContain('<Refresh')
    expect(teamsView).toContain('<Delete')
    expect(teamsView).toContain('aria-label')
    expect(teamsView).toContain('.detail-action-label')
    expect(teamsView).toContain('display: none;')
  })

  it('wires management attention actions through the Teams task handlers', () => {
    expect(teamsView).toContain('management-actions')
    expect(teamsView).toContain('@view-task="openAttentionTask"')
    expect(teamsView).toContain('@retry-task="retryAttentionTask"')
    expect(teamsView).toContain('@approve-task="approveAttentionTask"')
    expect(teamsView).toContain('currentTask.task.blockedBy')
    expect(teamsView).not.toContain('if (task) await openRunTask(task)')
    expect(teamsView).toContain('runHistory.select(task.runId, task.id)')
    expect(teamsView).toContain('useWorkspaceStore')
    expect(teamsView).toContain(':management-actions="canManageSelectedRun"')
    expect(teamsView).toContain(':pending-actions="attentionPendingActions"')
  })

  it('keeps attention actions and focused task evidence inside mobile width', () => {
    expect(teamsView).toContain('management-actions')
    expect(drawer).toContain('width: min(620px, 94vw)')
  })
})
