<template>
  <svg
    :width="size" :height="size" viewBox="0 0 24 24"
    fill="none" stroke="currentColor" stroke-width="2"
    stroke-linecap="round" stroke-linejoin="round"
    aria-hidden="true" v-html="path"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { memoryIcons, type MemoryIconName } from './icons'

const props = withDefaults(defineProps<{
  /** Icon key from the memory icon registry. */
  name: MemoryIconName
  /** Rendered width/height in pixels. */
  size?: number | string
}>(), {
  size: 16,
})

const path = computed(() => memoryIcons[props.name] ?? '')
</script>

<style scoped>
svg { display: block; flex-shrink: 0; }
</style>
