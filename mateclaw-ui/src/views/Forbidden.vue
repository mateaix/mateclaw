<template>
  <div class="forbidden-page">
    <div class="forbidden-card">
      <div class="forbidden-icon">🚫</div>
      <h1 class="forbidden-title">{{ t('forbidden.title') }}</h1>
      <p class="forbidden-message">{{ t('forbidden.message') }}</p>
      <p v-if="role" class="forbidden-meta">
        {{ t('forbidden.currentRole', { role }) }}
      </p>
      <div class="forbidden-actions">
        <button class="btn-primary" @click="goHome">{{ t('forbidden.goChat') }}</button>
        <button class="btn-secondary" @click="goBack">{{ t('forbidden.goBack') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

const router = useRouter()
const { t } = useI18n()
const store = useWorkspaceStore()

const role = computed(() => store.currentRole || '')

function goHome() {
  router.replace('/chat')
}
function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.replace('/chat')
  }
}
</script>

<style scoped>
.forbidden-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2rem;
}
.forbidden-card {
  max-width: 480px;
  text-align: center;
  padding: 3rem 2rem;
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color, #e5e7eb);
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
}
.forbidden-icon {
  font-size: 3rem;
  margin-bottom: 1rem;
}
.forbidden-title {
  font-size: 1.5rem;
  font-weight: 600;
  margin: 0 0 0.5rem;
  color: var(--el-text-color-primary);
}
.forbidden-message {
  color: var(--el-text-color-regular);
  margin: 0 0 0.5rem;
  line-height: 1.6;
}
.forbidden-meta {
  color: var(--el-text-color-secondary);
  font-size: 0.875rem;
  margin: 0 0 1.5rem;
}
.forbidden-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: center;
  margin-top: 1.5rem;
}
.btn-primary,
.btn-secondary {
  padding: 0.5rem 1.25rem;
  border-radius: 6px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
  transition: opacity 0.15s;
}
.btn-primary {
  background: var(--el-color-primary, #3b82f6);
  color: #fff;
}
.btn-secondary {
  background: transparent;
  border-color: var(--el-border-color, #e5e7eb);
  color: var(--el-text-color-primary);
}
.btn-primary:hover,
.btn-secondary:hover {
  opacity: 0.85;
}
</style>
