<template>
  <Teleport to="body">
    <div v-if="visible" class="modal-overlay" @click.self="close">
      <div class="modal">
        <div class="modal-header">
          <h3>{{ t('auth.changePassword') }}</h3>
          <button class="modal-close" @click="close">&times;</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label>{{ t('auth.oldPassword') }}</label>
            <div class="password-wrapper">
              <input
                v-model="form.oldPassword"
                :type="showOld ? 'text' : 'password'"
                class="form-input"
                autocomplete="current-password"
              />
              <button type="button" class="toggle-eye" @click="showOld = !showOld">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path v-if="showOld" d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
                  <line v-if="showOld" x1="1" y1="1" x2="23" y2="23"/>
                  <path v-else d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle v-if="!showOld" cx="12" cy="12" r="3"/>
                </svg>
              </button>
            </div>
          </div>
          <div class="form-group">
            <label>{{ t('auth.newPassword') }}</label>
            <div class="password-wrapper">
              <input
                v-model="form.newPassword"
                :type="showNew ? 'text' : 'password'"
                class="form-input"
                autocomplete="new-password"
              />
              <button type="button" class="toggle-eye" @click="showNew = !showNew">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path v-if="showNew" d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
                  <line v-if="showNew" x1="1" y1="1" x2="23" y2="23"/>
                  <path v-else d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle v-if="!showNew" cx="12" cy="12" r="3"/>
                </svg>
              </button>
            </div>
          </div>
          <div class="form-group">
            <label>{{ t('auth.confirmPassword') }}</label>
            <input
              v-model="form.confirmPassword"
              type="password"
              class="form-input"
              autocomplete="new-password"
              @keyup.enter="handleSubmit"
            />
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="close">{{ t('common.cancel') }}</button>
          <button class="btn-primary" :disabled="submitting" @click="handleSubmit">
            {{ submitting ? t('common.loading') : t('common.confirm') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { authApi } from '@/api/index'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [value: boolean] }>()

const { t } = useI18n()
const submitting = ref(false)
const showOld = ref(false)
const showNew = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

watch(() => props.visible, (open) => {
  if (open) {
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    showOld.value = false
    showNew.value = false
  }
})

function close() {
  emit('update:visible', false)
}

async function handleSubmit() {
  if (!form.oldPassword || !form.newPassword) {
    mcToast.warning(t('auth.fieldsRequired'))
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    mcToast.warning(t('auth.passwordMismatch'))
    return
  }
  submitting.value = true
  try {
    const userId = Number(localStorage.getItem('userId') || '1')
    await authApi.changePassword(userId, form.oldPassword, form.newPassword)
    mcToast.success(t('auth.passwordChanged'))
    close()
  } catch (error: any) {
    mcToast.error(error?.msg || error?.message || t('auth.passwordChangeFailed'))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(124, 63, 30, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 1000;
  animation: fadeIn 0.15s ease;
}

.modal {
  width: 420px;
  max-width: 100%;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 16px;
  box-shadow: 0 16px 48px rgba(25, 14, 8, 0.18);
  overflow: hidden;
  animation: slideUp 0.2s ease;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--mc-border-light);
}

.modal-header h3 {
  font-size: 17px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0;
}

.modal-close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  color: var(--mc-text-tertiary);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  transition: background 0.15s;
}

.modal-close:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
}

.modal-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-group label {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-secondary);
}

.form-input {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  font-size: 14px;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-sizing: border-box;
}

.form-input:focus {
  outline: none;
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.12);
}

.password-wrapper {
  position: relative;
}

.password-wrapper .form-input {
  padding-right: 36px;
}

.toggle-eye {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  width: 26px;
  height: 26px;
  border: none;
  background: none;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  padding: 0;
}

.toggle-eye:hover {
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px 16px;
  border-top: 1px solid var(--mc-border-light);
}

.btn-primary,
.btn-secondary {
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
}

.btn-primary {
  background: var(--mc-primary);
  color: white;
  border-color: var(--mc-primary);
}

.btn-primary:hover:not(:disabled) {
  background: var(--mc-primary-hover);
  border-color: var(--mc-primary-hover);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-secondary {
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}

.btn-secondary:hover {
  background: var(--mc-bg-muted);
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
