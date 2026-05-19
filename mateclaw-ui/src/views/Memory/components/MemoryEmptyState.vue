<template>
  <div class="mc-empty" :class="{ 'mc-empty--lg': large }">
    <MemoryIcon :name="icon" :size="large ? 40 : 30" class="mc-empty__icon" />
    <p class="mc-empty__text">{{ text }}</p>
    <!-- Optional action (e.g. a retry button). -->
    <slot />
  </div>
</template>

<script setup lang="ts">
import MemoryIcon from './MemoryIcon.vue'
import type { MemoryIconName } from './icons'

withDefaults(defineProps<{
  icon: MemoryIconName
  text: string
  /** Large centered variant for full-panel empty states. */
  large?: boolean
}>(), {
  large: false,
})
</script>

<style scoped>
.mc-empty {
  display: flex; flex-direction: column; align-items: center;
  padding: 40px 0; color: var(--mc-text-tertiary); text-align: center;
}
.mc-empty--lg { justify-content: center; height: 100%; padding: 0; }
.mc-empty__icon { margin-bottom: 10px; opacity: 0.7; }
.mc-empty--lg .mc-empty__icon { margin-bottom: 14px; opacity: 0.6; }
.mc-empty__text { margin: 0; font-size: 13px; }
.mc-empty--lg .mc-empty__text { font-size: 14px; max-width: 260px; line-height: 1.5; }
</style>
