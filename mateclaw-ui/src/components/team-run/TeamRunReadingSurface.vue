<script setup lang="ts">defineProps<{ as?: string }>()</script>
<template><component :is="as || 'section'" class="team-run-reading"><slot /></component></template>
<style scoped>
.team-run-reading { min-width: 0; max-width: 100%; padding: 12px 14px; background: var(--mc-team-run-reading-bg, #fff); color: var(--mc-text-primary, #1f2937); overflow-wrap: anywhere; }
.team-run-reading :deep(.markdown-body) { min-width: 0; max-width: 100%; font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; }
.team-run-reading :deep(.markdown-body > *:first-child) { margin-top: 0; }
.team-run-reading :deep(.markdown-body > *:last-child) { margin-bottom: 0; }
.team-run-reading :deep(.markdown-body p), .team-run-reading :deep(.markdown-body ul), .team-run-reading :deep(.markdown-body ol) { margin-block: 6px; }
.team-run-reading :deep(.markdown-body h1), .team-run-reading :deep(.markdown-body h2), .team-run-reading :deep(.markdown-body h3) { margin: 12px 0 6px; line-height: 1.3; }
.team-run-reading :deep(.markdown-body table) { display: block; width: 100%; max-width: 100%; overflow-x: auto; }
.team-run-reading :deep(.markdown-body thead), .team-run-reading :deep(.markdown-body tbody) { display: table; width: max-content; min-width: 100%; table-layout: auto; }
.team-run-reading :deep(.markdown-body pre) { display: block; max-width: 100%; overflow-x: auto; }
.team-run-reading :deep(.markdown-body th), .team-run-reading :deep(.markdown-body td) { overflow-wrap: normal; word-break: normal; }
.team-run-reading :deep(.markdown-body th code), .team-run-reading :deep(.markdown-body td code) { white-space: nowrap; overflow-wrap: normal; word-break: normal; }
.team-run-reading :deep(.markdown-body a), .team-run-reading :deep(.markdown-body code) { overflow-wrap: anywhere; word-break: break-word; }
@media (max-width: 640px) {
  .team-run-reading :deep(.markdown-body th), .team-run-reading :deep(.markdown-body td) { min-width: 6.5rem; }
}
</style>
