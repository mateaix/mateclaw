# Adaptive Context Probe Layer - Design Document
version 1.0

## Core Principle
Production traffic = probe. Zero additional token cost.

## Architecture
- AdaptiveContextTracker: runtime window probing per (provider, model)
- ContextPressureMonitor: signal collection + weak dependency
- DynamicBudgetAllocator: priority-based budget across components
- ModelWindowState: COLD -> PROBING -> BINARY_SEARCH -> STABLE state machine
- GatewayDistribution: auto-detect multi-backend patterns
- ContextProfile: model type classification
- ResourcePressure: NORMAL -> ELEVATED -> HIGH -> CRITICAL auto-degradation

## Single Integration Point
ReasoningNode.loopContextWindowTokens():
  1. tracker.getEffectiveWindow(provider, model)  -- dynamic
  2. conversationWindowManager.getDefaultMaxInputTokens()  -- yml fallback
  3. DEFAULT_LOOP_CONTEXT_WINDOW_TOKENS  -- hardcoded fallback

## Compatibility
- All new components @Autowired(required=false) + try-catch
- Total switch mateclaw.context.adaptive.enabled=false restores 100% legacy behavior
- ConversationWindowManager: 0 line changes
