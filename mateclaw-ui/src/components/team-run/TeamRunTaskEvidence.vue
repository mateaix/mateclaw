<script setup lang="ts">import type { TeamRunTask } from '@/api'; import TeamRunTaskList from './TeamRunTaskList.vue'; defineProps<{tasks:TeamRunTask[];selectedTaskId?:string|null}>(); defineEmits<{'select-task':[task:TeamRunTask]}>()</script>
<template><section data-team-run-task-evidence class="run-task-evidence"><TeamRunTaskList :tasks="tasks" :selected-task-id="selectedTaskId" @select-task="$emit('select-task',$event)"/></section></template>
<style scoped>.run-task-evidence{min-width:0;max-width:100%;background:var(--mc-team-run-reading-bg,#fff)}</style>
