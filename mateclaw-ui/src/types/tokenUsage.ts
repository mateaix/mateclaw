/**
 * Token Usage 统计相关类型
 */

export interface ModelUsageItem {
  runtimeModel: string
  runtimeProvider: string
  promptTokens: number
  completionTokens: number
  messageCount: number
}

export interface DateUsageItem {
  date: string
  promptTokens: number
  completionTokens: number
  messageCount: number
}

export interface TokenUsageSummary {
  totalPromptTokens: number
  totalCompletionTokens: number
  totalCacheReadTokens: number
  totalCacheWriteTokens: number
  totalReasoningTokens: number
  totalMessages: number
  byModel: ModelUsageItem[]
  byDate: DateUsageItem[]
}

export interface TokenUsageQuery {
  startDate?: string
  endDate?: string
  modelName?: string
  providerId?: string
}
