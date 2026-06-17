<script setup lang="ts">
import { computed } from 'vue'
import { useStreamingMarkdown } from '@/composables/useStreamingMarkdown'
import TypingCursor from './TypingCursor.vue'
import type { MessageSegment } from '@/types'

const props = withDefaults(defineProps<{
  segment: MessageSegment
  showCursor?: boolean
}>(), {
  showCursor: false,
})

const isRunning = computed(() => props.segment.status === 'running')

// Throttle markdown rendering while the segment streams; render once at full
// fidelity the moment it completes.
const { html: renderedContent } = useStreamingMarkdown(
  () => props.segment.text || '',
  () => isRunning.value,
)
</script>

<template>
  <div class="seg-content">
    <div class="markdown-body" v-html="renderedContent"></div>
    <TypingCursor v-if="isRunning && showCursor" />
  </div>
</template>

<style scoped>
.seg-content {
  padding: 4px 0;
  margin-top: 4px;
}
</style>
