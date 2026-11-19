<template>
  <div v-if="hasIncompleteSkillScan(findings)" class="scan-incomplete-notice" role="status">
    {{ t('skills.security.scanIncompleteHint') }}
  </div>
</template>
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { hasIncompleteSkillScan } from '@/utils/skillScanState'
defineProps<{ findings: unknown }>()
const { t } = useI18n()
</script>
<style scoped>
.scan-incomplete-notice { padding: 10px 12px; margin: 10px 0; border-radius: 8px; background: var(--mc-bg-muted); color: var(--mc-danger); font-size: 13px; line-height: 1.6; }
</style>
