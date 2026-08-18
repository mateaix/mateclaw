<script setup lang="ts">import type { TeamRun } from '@/api'; import TeamRunOutcome from './TeamRunOutcome.vue'; import TeamRunDeliverables from './TeamRunDeliverables.vue'; import TeamRunAttention from './TeamRunAttention.vue'; defineProps<{run:TeamRun}>()</script>
<template><div class="run-delivery-summary"><TeamRunOutcome :run="run"/><TeamRunDeliverables :run="run"/><TeamRunAttention :run="run"/></div></template>
<style scoped>.run-delivery-summary{display:grid;gap:1px;background:var(--mc-border-light,#e7ebef)}</style>
