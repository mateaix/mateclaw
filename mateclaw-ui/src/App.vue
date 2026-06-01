<template>
  <el-config-provider :locale="elementLocale">
    <router-view />
    <!-- Mounted once at the app root so mcConfirm() can pop a dialog
         from anywhere without each caller wiring its own host. -->
    <McConfirmHost />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import en from 'element-plus/es/locale/lang/en'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { currentLocale } from '@/i18n'
import { useThemeStore } from '@/stores/useThemeStore'
import { useGlobalWikilinkClick } from '@/composables/useGlobalWikilinkClick'
import { useGlobalFileDownloadClick } from '@/composables/useGlobalFileDownloadClick'
import McConfirmHost from '@/components/common/McConfirmHost.vue'

// Initialize theme — applies .dark class to <html> immediately
useThemeStore()

// Global click delegator for [[wikilinks]] rendered into chat / docs /
// memory surfaces. WikiPageViewer's own postprocess handles in-wiki
// clicks (those carry data-slug); this catches everything else.
useGlobalWikilinkClick()

// Global click delegator for tool-generated file download links
// (`/api/v1/files/...`). Downloads via authenticated fetch → blob so an
// expired/missing file degrades to a toast instead of a full-page navigation
// to the backend's 404 JSON, which would otherwise replace the whole SPA.
useGlobalFileDownloadClick()

const { t } = useI18n()

watchEffect(() => {
  document.title = t('app.title')
})

const elementLocale = computed(() => (currentLocale.value === 'en-US' ? en : zhCn))
</script>
