<template>
  <div class="mc-skeleton">
    <div v-for="i in count" :key="i" class="mc-skeleton__card">
      <div class="mc-skeleton__line" />
      <div class="mc-skeleton__line mc-skeleton__line--short" />
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  /** Number of placeholder cards to render. */
  count?: number
}>(), {
  count: 3,
})
</script>

<style scoped>
.mc-skeleton { padding: 8px 0; }
.mc-skeleton__card { padding: 14px; margin-bottom: 8px; }
.mc-skeleton__line {
  height: 10px; border-radius: 4px;
  background: var(--mc-bg-sunken); margin-bottom: 8px;
}
.mc-skeleton__line--short { width: 60%; }
</style>
