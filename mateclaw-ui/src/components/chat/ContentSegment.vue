<script setup lang="ts">
import { computed } from 'vue'
import { useStreamingMarkdown } from '@/composables/useStreamingMarkdown'
import { linkifyGeneratedFileUrls } from '@/utils/generatedFileLinks'
import TypingCursor from './TypingCursor.vue'
import type { MessageSegment } from '@/types'

const props = withDefaults(defineProps<{
  segment: MessageSegment
  showCursor?: boolean
  /** id → filename map for rewriting bare generated-file URLs into [name](url). */
  generatedFileNames?: Map<string, string>
}>(), {
  showCursor: false,
  generatedFileNames: undefined,
})

const isRunning = computed(() => props.segment.status === 'running')

// Throttle markdown rendering while the segment streams; render once at full
// fidelity the moment it completes.
const { html: renderedContent } = useStreamingMarkdown(
  () => {
    const text = props.segment.text || ''
    return props.generatedFileNames?.size
      ? linkifyGeneratedFileUrls(text, props.generatedFileNames)
      : text
  },
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
