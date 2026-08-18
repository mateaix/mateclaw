import type { TeamRun, TeamRunAttentionItem, TeamRunContribution, TeamRunDeliverable } from '@/api'
import { extractRunDeliverables } from './teamRunPresentation'
import { isSafeFileUrl } from '@/utils/generatedFileLinks'

export const runDeliverables = (run: TeamRun): TeamRunDeliverable[] => run.deliverables?.length
  ? run.deliverables.filter(item => isSafeFileUrl(item.url))
  : extractRunDeliverables(run).map((item, index) => ({ id: `legacy:${index}:${item.url}`, name: item.name, url: item.url, type: 'file', sourceTaskIds: [], sourceAgentIds: [], createdAt: null, verificationStatus: 'legacy' }))
export const runAttention = (run: TeamRun): TeamRunAttentionItem[] => [...(run.attentionItems ?? [])].sort((a, b) => a.priority - b.priority)
export const runContributions = (run: TeamRun): TeamRunContribution[] => run.contributions ?? []
