<script setup lang="ts">
import { ref, computed, watch, onUnmounted, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { Opportunity, ArrowDown } from '@element-plus/icons-vue'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import type { MessageSegment } from '@/types'

const { t } = useI18n()

const props = defineProps<{
  segment: MessageSegment
}>()

// Expand while streaming so the user can watch the model think in real time;
// auto-collapse the moment streaming ends so the assistant's final answer
// stays the focal point without a long reasoning block above it. The user
// can click the header to re-expand at any time.
const expanded = ref(props.segment.status === 'running')
const { renderMarkdown } = useMarkdownRenderer()

const renderedThinking = computed(() => renderMarkdown(props.segment.thinkingText || ''))
const isRunning = computed(() => props.segment.status === 'running')

// Live thinking stopwatch. While running it ticks every second off the
// segment's start timestamp; the moment the segment completes we freeze the
// clock locally (frozenEnd) — the stream path never writes endTimestamp, so
// the freeze IS the end signal. History replays start out completed and use
// the backend-persisted timestamp/endTimestamp pair instead. No estimation
// fallback: without real bounds we show no duration at all.
const now = ref(Date.now())
const frozenEnd = ref<number | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

function stopTimer() {
  if (timer != null) {
    clearInterval(timer)
    timer = null
  }
}

watch(isRunning, (running, wasRunning) => {
  if (running) {
    frozenEnd.value = null
    now.value = Date.now()
    if (timer == null) timer = setInterval(() => { now.value = Date.now() }, 1000)
  } else {
    stopTimer()
    // Freeze only on a live running→completed transition. A segment that
    // mounts already completed (history replay) must not fake an end time —
    // its duration comes from persisted endTimestamp or not at all.
    if (wasRunning === true && props.segment.timestamp && !props.segment.endTimestamp) {
      frozenEnd.value = Date.now()
    }
  }
}, { immediate: true })

watch(() => props.segment.status, (val) => {
  if (val === 'completed') expanded.value = false
})

onUnmounted(stopTimer)

const durationText = computed(() => {
  // Persisted metadata serializes longs as strings; live segments carry
  // numbers. Coerce both (epoch millis are safely below 2^53).
  const start = Number(props.segment.timestamp) || 0
  if (!start) return ''
  const end = isRunning.value
    ? now.value
    : (Number(props.segment.endTimestamp) || frozenEnd.value || 0)
  if (!end || end < start) return ''
  const sec = Math.max(1, Math.round((end - start) / 1000))
  return sec >= 60 ? `${Math.floor(sec / 60)}m ${sec % 60}s` : `${sec}s`
})

// Header label: while running the label + a live ticking duration; once
// completed the duration folds into the label ("Thought for 12s"). The char
// count only shows when no real duration is available (old history).
const headerLabel = computed(() => {
  if (isRunning.value) return t('chat.thinkingInProgress')
  return durationText.value
    ? t('chat.thinkingDoneFor', { duration: durationText.value })
    : t('chat.thinking')
})

const lengthHint = computed(() => {
  if (durationText.value) return ''
  const len = props.segment.thinkingText?.length || 0
  if (len < 100) return ''
  return len < 1000 ? `${len} chars` : `${(len / 1000).toFixed(1)}k chars`
})

// While streaming with the body expanded, keep the newest thinking line in
// view — the body is height-capped so without this the visible text freezes
// at the top while new deltas pile up below the fold.
const bodyEl = ref<HTMLElement | null>(null)
watch(() => props.segment.thinkingText, async () => {
  if (!isRunning.value || !expanded.value) return
  await nextTick()
  if (bodyEl.value) bodyEl.value.scrollTop = bodyEl.value.scrollHeight
})
</script>

<template>
  <div class="seg-thinking" :class="{ 'is-active': isRunning }">
    <div class="seg-thinking__header" @click="expanded = !expanded">
      <span class="seg-thinking__icon">
        <el-icon :class="{ 'is-loading': isRunning }" :size="14"><Opportunity /></el-icon>
      </span>
      <span class="seg-thinking__label">{{ headerLabel }}</span>
      <span v-if="isRunning && durationText" class="seg-thinking__duration">{{ durationText }}</span>
      <span v-if="lengthHint" class="seg-thinking__hint">{{ lengthHint }}</span>
      <el-icon class="seg-thinking__arrow" :class="{ 'is-open': expanded }" :size="12"><ArrowDown /></el-icon>
    </div>
    <Transition name="seg-slide">
      <div v-if="expanded" ref="bodyEl" class="seg-thinking__body markdown-body" v-html="renderedThinking"></div>
    </Transition>
  </div>
</template>

<style scoped>
.seg-thinking {
  margin: 3px 0;
  border-radius: 8px;
  background: var(--mc-thinking-bg);
  border: 1px solid var(--mc-thinking-border);
  overflow: hidden;
  transition: all 0.2s;
}
.seg-thinking:hover {
  background: var(--mc-thinking-hover);
}
.seg-thinking.is-active {
  border-color: var(--mc-primary-light);
}
.seg-thinking__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  font-size: 13px;
  cursor: pointer;
  color: var(--mc-thinking-text);
  user-select: none;
}
.seg-thinking__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 5px;
  background: var(--mc-thinking-icon-bg);
  color: var(--mc-primary);
  flex-shrink: 0;
}
.seg-thinking__label {
  font-weight: 500;
  flex: 1;
}
.seg-thinking__duration {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  font-variant-numeric: tabular-nums;
}
.seg-thinking__hint {
  font-size: 11px;
  color: var(--mc-text-tertiary);
}
.seg-thinking__arrow {
  color: var(--mc-text-tertiary);
  transition: transform 0.2s;
}
.seg-thinking__arrow.is-open {
  transform: rotate(180deg);
}
.seg-thinking__body {
  font-size: 12px;
  line-height: 1.7;
  color: var(--mc-thinking-text);
  opacity: 0.88;
  max-height: 260px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  border-left: 2px solid var(--mc-thinking-border);
  margin: 0 12px 10px;
  padding: 2px 0 2px 10px;
}

.seg-slide-enter-active, .seg-slide-leave-active {
  transition: all 0.2s ease;
}
.seg-slide-enter-from, .seg-slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
