import { isTeamRunBookkeeping } from './messageMetadata'
import type { TeamRun } from '@/api'
import type { Message } from '@/types'

export type TeamRunTimelineItem =
  | { type: 'message'; key: string; message: Message; messageIndex: number }
  | { type: 'team-run'; key: string; run: TeamRun }

export function assembleTeamRunTimeline(messages: Message[], runs: TeamRun[]): TeamRunTimelineItem[] {
  const uniqueRuns = Array.from(new Map(runs.map(run => [run.id, run])).values())
  const absorbedIndexes = new Set<number>()
  const before = new Map<number, TeamRun[]>()
  const after = new Map<number, TeamRun[]>()
  const appended: TeamRun[] = []

  for (const run of uniqueRuns) {
    let firstBookkeepingIndex = -1
    messages.forEach((message, index) => {
      if (isTeamRunBookkeeping(message, run.id)) {
        absorbedIndexes.add(index)
        if (firstBookkeepingIndex < 0) firstBookkeepingIndex = index
      }
    })

    const originIndex = run.originMessageId === null
      ? -1
      : messages.findIndex(message => message.role === 'user' && String(message.id) === run.originMessageId)
    if (originIndex >= 0) {
      after.set(originIndex, [...(after.get(originIndex) ?? []), run])
    } else if (firstBookkeepingIndex >= 0) {
      before.set(firstBookkeepingIndex, [...(before.get(firstBookkeepingIndex) ?? []), run])
    } else {
      appended.push(run)
    }
  }

  const items: TeamRunTimelineItem[] = []
  messages.forEach((message, index) => {
    for (const run of before.get(index) ?? []) {
      items.push({ type: 'team-run', key: `team-run:${run.id}`, run })
    }
    if (!absorbedIndexes.has(index)) {
      items.push({ type: 'message', key: `message:${String(message.id ?? index)}`, message, messageIndex: index })
    }
    for (const run of after.get(index) ?? []) {
      items.push({ type: 'team-run', key: `team-run:${run.id}`, run })
    }
  })
  for (const run of appended) {
    items.push({ type: 'team-run', key: `team-run:${run.id}`, run })
  }
  return items
}
