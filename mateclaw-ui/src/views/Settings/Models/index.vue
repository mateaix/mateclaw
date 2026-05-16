<template>
  <div class="settings-section model-section">
    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('settings.model.title') }}</h2>
        <p class="section-desc">{{ t('settings.model.desc') }}</p>
      </div>
      <div class="section-header__actions">
        <!-- RFC-074 PR-2: primary entry to enable a built-in provider. -->
        <button class="btn-primary" @click="openDrawer">
          {{ t('settings.model.enableProviderCta') }}
        </button>
        <!-- Secondary: create a fully custom provider (your own base URL etc.). -->
        <button class="btn-secondary" @click="openCreateProviderModal">
          {{ t('settings.model.addCustomProvider') }}
        </button>
      </div>
    </div>

    <!-- RFC-074 PR-1: skeleton placeholder so the page paints something
         immediately on first load instead of blank-then-pop. -->
    <div v-if="loading" class="provider-group">
      <el-skeleton :rows="4" animated />
    </div>

    <!-- RFC-074 PR-2: empty state shown when the user has no enabled providers.
         The drawer auto-opens via onMounted, so this CTA is the recovery path
         for users who closed it. -->
    <div
      v-if="!loading && !localProviders.length && !cloudProviders.length"
      class="provider-empty"
    >
      <h3>{{ t('settings.model.emptyTitle') }}</h3>
      <p>{{ t('settings.model.emptyDesc') }}</p>
      <button class="btn-primary" @click="openDrawer">
        {{ t('settings.model.enableProviderCta') }}
      </button>
    </div>

    <!-- 本地模型 -->
    <div v-if="!loading && localProviders.length" class="provider-group">
      <h3 class="group-title">
        <svg class="group-title__icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>
        </svg>
        {{ t('settings.model.localProviders') }}
      </h3>
      <div class="provider-grid">
        <div
          v-for="provider in localProviders"
          :key="provider.id"
          class="provider-card"
          :class="{ 'provider-card--active': isProviderActive(provider) }"
        >
          <ProviderCard
            :provider="provider"
            :connection-testing-id="connectionTestingId"
            :connection-results="connectionResults"
            :reprobing="reprobingId === provider.id"
            :saving-api-key-id="savingApiKeyId"
            :is-provider-active="isProviderActive"
            :provider-status="providerStatus"
            :get-provider-icon="getProviderIcon"
            :on-icon-error="onIconError"
            @manage-models="openManageModelsModal"
            @provider-settings="openProviderConfigModal"
            @test-connection="handleTestConnection"
            @delete-provider="onDeleteProvider"
            @disable-provider="onDisableProvider"
            @reprobe="reprobeProvider"
            @oauth-login="onCardOAuthLogin"
            @save-api-key="onSaveApiKey"
          />
        </div>
      </div>
    </div>

    <!-- 云端模型 -->
    <div v-if="!loading && cloudProviders.length" class="provider-group">
      <h3 class="group-title">
        <svg class="group-title__icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/>
        </svg>
        {{ t('settings.model.cloudProviders') }}
      </h3>
      <div class="provider-grid">
        <div
          v-for="provider in cloudProviders"
          :key="provider.id"
          class="provider-card"
          :class="{ 'provider-card--active': isProviderActive(provider) }"
        >
          <ProviderCard
            :provider="provider"
            :connection-testing-id="connectionTestingId"
            :connection-results="connectionResults"
            :reprobing="reprobingId === provider.id"
            :saving-api-key-id="savingApiKeyId"
            :is-provider-active="isProviderActive"
            :provider-status="providerStatus"
            :get-provider-icon="getProviderIcon"
            :on-icon-error="onIconError"
            @manage-models="openManageModelsModal"
            @provider-settings="openProviderConfigModal"
            @test-connection="handleTestConnection"
            @delete-provider="onDeleteProvider"
            @disable-provider="onDisableProvider"
            @reprobe="reprobeProvider"
            @oauth-login="onCardOAuthLogin"
            @save-api-key="onSaveApiKey"
          />
        </div>
      </div>
    </div>

    <!-- Embedding 模型（RFC Embedding UI） -->
    <EmbeddingModelsSection />

    <!-- Multimodal sidecar routing: text-only primary models can delegate
         image/video understanding to a vision/video model configured here. -->
    <MultimodalSidecarSection />

    <div v-if="savedTip" class="save-tip">{{ savedTip }}</div>

    <!-- Provider Config Modal -->
    <ProviderConfigModal
      :show="showProviderModal"
      :editing-provider="editingProvider"
      :form="providerForm"
      :advanced-open="advancedOpen"
      :protocol-options="protocolOptions"
      :base-url-placeholder="providerBaseUrlPlaceholder"
      :base-url-hint="providerBaseUrlHint"
      :api-key-placeholder="providerApiKeyPlaceholder"
      @close="closeProviderModal"
      @save="onSaveProvider"
      @toggle-advanced="advancedOpen = !advancedOpen"
      @oauth-login="handleOAuthLogin"
      @oauth-revoke="handleOAuthRevoke"
    />

    <!-- Manage Models Modal -->
    <ManageModelsModal
      :show="showManageModelsModal"
      :provider="currentProvider"
      :model-form="providerModelForm"
      :discovering="discovering"
      :discover-result="discoverResult"
      :selected-new-model-ids="selectedNewModelIds"
      :applying-models="applyingModels"
      :all-new-selected="allNewSelected"
      :testing-model-id="testingModelId"
      :model-test-results="modelTestResults"
      :is-extra-model="isExtraModel"
      :is-active-model="isActiveModel"
      :get-provider-icon="getProviderIcon"
      :on-icon-error="onIconError"
      @close="closeManageModelsModal"
      @discover="handleDiscoverModels"
      @toggle-select-all="toggleSelectAll"
      @toggle-model="onToggleModel"
      @apply-models="onApplyModels"
      @test-model="handleTestModel"
      @set-active="onSetActiveModel"
      @remove-model="onRemoveProviderModel"
      @add-model="onAddProviderModel"
    />

    <!-- RFC-074 PR-2: Add Provider Drawer (catalog of opt-in built-ins). -->
    <AddProviderDrawer
      :visible="drawerOpen"
      :catalog="catalog"
      :toggling-id="togglingId"
      :get-provider-icon="getProviderIcon"
      :on-icon-error="onIconError"
      :enable-provider="enableProvider"
      @close="closeDrawer"
    />

    <DeviceCodeDialog
      :visible="deviceCodeDialog.visible"
      :user-code="deviceCodeDialog.userCode"
      :verification-url="deviceCodeDialog.verificationUrl"
      :verification-url-complete="deviceCodeDialog.verificationUrlComplete"
      :expires-at="deviceCodeDialog.expiresAt"
      @close="closeDeviceCodeDialog"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { useRoute, useRouter } from 'vue-router'
import type { ProviderInfo, ProviderModelInfo } from '@/types'
import { useProviders } from './useProviders'
import ProviderCard from './ProviderCard.vue'
import EmbeddingModelsSection from './EmbeddingModelsSection.vue'
import MultimodalSidecarSection from './MultimodalSidecarSection.vue'
// RFC-074 PR-1: defer modal JS until the user actually opens one — same
// pattern as ChannelEditModal in commit 9300559b. Drops ~30KB from the
// initial Settings/Models route chunk.
const ProviderConfigModal = defineAsyncComponent(() => import('./modals/ProviderConfigModal.vue'))
const ManageModelsModal = defineAsyncComponent(() => import('./modals/ManageModelsModal.vue'))
// RFC-074 PR-2: drawer for browsing the catalog and opting into hidden built-ins.
const AddProviderDrawer = defineAsyncComponent(() => import('./AddProviderDrawer.vue'))
const DeviceCodeDialog = defineAsyncComponent(() => import('./modals/DeviceCodeDialog.vue'))

const { t } = useI18n()
const savedTip = ref('')
// Skeleton gate. Driven by onMounted only — fine because /settings/models is
// NOT a keepAlive route. If anyone re-adds keepAlive in router/index.ts,
// switch to onActivated (or reset loading there) so cached re-entries don't
// skip the load + leave loading=false stale.
const loading = ref(true)

const {
  providers,
  editingProvider,
  currentProvider,
  showProviderModal,
  showManageModelsModal,
  advancedOpen,
  discovering,
  discoverResult,
  selectedNewModelIds,
  applyingModels,
  connectionTestingId,
  connectionResults,
  testingModelId,
  modelTestResults,
  providerForm,
  providerModelForm,
  protocolOptions,
  allNewSelected,
  providerBaseUrlPlaceholder,
  providerBaseUrlHint,
  providerApiKeyPlaceholder,
  reprobingId,
  reprobeProvider,
  loadProviders,
  loadActiveModel,
  openCreateProviderModal,
  openProviderConfigModal,
  closeProviderModal,
  saveProvider,
  saveProviderApiKey,
  deleteProvider,
  openManageModelsModal,
  closeManageModelsModal,
  isExtraModel,
  addProviderModel,
  removeProviderModel,
  isProviderActive,
  isActiveModel,
  setActiveModel,
  toggleSelectAll,
  handleDiscoverModels,
  handleApplyModels,
  handleTestConnection,
  handleTestModel,
  providerStatus,
  getProviderIcon,
  onIconError,
  handleOAuthLogin,
  handleOAuthRevoke,
  deviceCodeDialog,
  closeDeviceCodeDialog,
  // RFC-074 PR-2 — enablement / drawer
  catalog,
  drawerOpen,
  togglingId,
  openDrawer,
  closeDrawer,
  enableProvider,
  disableProvider,
} = useProviders()

const localProviders = computed(() => providers.value.filter(p => p.isLocal))
const cloudProviders = computed(() => providers.value.filter(p => !p.isLocal))

/**
 * Inline-save in-flight tracker — child cards reflect this to disable
 * their input while the request is on the wire. Single concurrent save
 * (one user, one card) so a scalar id is enough.
 */
const savingApiKeyId = ref<string | null>(null)

const route = useRoute()
const router = useRouter()
/** sessionStorage guard so the drawer auto-opens at most once per session per workspace. */
const AUTO_OPEN_KEY = 'rfc074-add-provider-auto-opened'

onMounted(async () => {
  try {
    await Promise.all([loadProviders(), loadActiveModel()])
  } finally {
    loading.value = false
  }
  // Deep-link: ?addProvider=1 forces the drawer open (used by ModelSelector empty link).
  const forceOpen = route.query.addProvider === '1'
  // Empty state auto-open: when the user has zero enabled providers, fling the
  // drawer open immediately. Guard with sessionStorage so closing it once
  // doesn't bring it back on the next route visit in the same session.
  const noProviders = providers.value.length === 0
  const alreadyAutoOpened = sessionStorage.getItem(AUTO_OPEN_KEY) === '1'
  if (forceOpen || (noProviders && !alreadyAutoOpened)) {
    sessionStorage.setItem(AUTO_OPEN_KEY, '1')
    openDrawer()
    if (forceOpen) {
      // Strip the query so a manual close + back doesn't re-fire the open.
      router.replace({ query: { ...route.query, addProvider: undefined } })
    }
  }
})

async function onDisableProvider(provider: ProviderInfo) {
  const ok = await mcConfirm({
    title: t('common.confirm'),
    message: t('settings.model.disableConfirm', { name: provider.name }),
    confirmText: t('settings.model.disable'),
    tone: 'danger',
  })
  if (!ok) return
  await disableProvider(provider.id)
}

async function onSaveApiKey({ provider, apiKey }: { provider: ProviderInfo; apiKey: string }) {
  savingApiKeyId.value = provider.id
  try {
    await saveProviderApiKey(provider, apiKey)
    showSavedTip(t('settings.model.inlineApiKeySaved'))
  } catch (error) {
    mcToast.error(error instanceof Error ? error.message : t('settings.model.inlineApiKeySaveFailed'))
  } finally {
    savingApiKeyId.value = null
  }
}

// Card emits oauth-login with the provider; the OAuth composable already
// accepts a providerId, so this is a thin pass-through.
function onCardOAuthLogin(provider: ProviderInfo) {
  handleOAuthLogin(provider.id)
}

async function onSaveProvider() {
  try {
    const saved = await saveProvider()
    // Issue #39: saveProvider() returns false when client-side validation
    // (e.g. provider id format) blocks the request — it has already shown
    // its own mcToast.error, so don't follow up with a "saved" toast.
    if (saved) showSavedTip(t('settings.model.providerSaved'))
  } catch (error) {
    mcToast.error(error instanceof Error ? error.message : t('settings.messages.saveFailed'))
  }
}

async function onDeleteProvider(provider: ProviderInfo) {
  const deleted = await deleteProvider(provider)
  if (deleted) showSavedTip(t('settings.model.providerDeleted'))
}

async function onAddProviderModel() {
  try {
    await addProviderModel()
    showSavedTip(t('settings.model.modelAdded'))
  } catch (error) {
    mcToast.error(error instanceof Error ? error.message : t('settings.model.modelAddFailed'))
  }
}

async function onRemoveProviderModel(model: ProviderModelInfo) {
  try {
    await removeProviderModel(model)
    showSavedTip(t('settings.model.modelRemoved'))
  } catch (error) {
    mcToast.error(error instanceof Error ? error.message : t('settings.model.modelRemoveFailed'))
  }
}

async function onSetActiveModel(model: ProviderModelInfo) {
  try {
    await setActiveModel(model)
    showSavedTip(t('settings.model.activeChanged'))
  } catch (error) {
    mcToast.error(error instanceof Error ? error.message : t('settings.model.activeChangeFailed'))
  }
}

async function onApplyModels() {
  const added = await handleApplyModels()
  if (added) showSavedTip(t('settings.model.discovery.addedCount', { count: added }))
}

function onToggleModel(modelId: string) {
  const idx = selectedNewModelIds.value.indexOf(modelId)
  if (idx >= 0) {
    selectedNewModelIds.value.splice(idx, 1)
  } else {
    selectedNewModelIds.value.push(modelId)
  }
}

function showSavedTip(message: string) {
  savedTip.value = message
  window.setTimeout(() => { savedTip.value = '' }, 2500)
}
</script>

<style scoped>
.settings-section { width: 100%; }
.settings-section.model-section { max-width: none; }
.section-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 20px; }
.section-title { margin: 0 0 6px; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.provider-group {
  margin-bottom: 28px;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px;
  font-size: 16px;
  font-weight: 600;
  color: var(--mc-text-primary);
}

.group-title__icon {
  flex-shrink: 0;
  color: var(--mc-text-secondary);
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
}
.provider-card {
  background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124, 63, 30, 0.04);
  /* Inset shadow technique: layout doesn't shift between active/inactive
     because we're not using border-left. Default rail is transparent so
     the same rule paints on both states — only the color changes. */
  box-shadow: inset 4px 0 0 transparent, 0 8px 24px rgba(124, 63, 30, 0.04);
  transition: box-shadow 0.18s ease;
}
.provider-card--active {
  box-shadow: inset 4px 0 0 var(--mc-primary), 0 8px 24px rgba(124, 63, 30, 0.06);
}
.btn-primary { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
/* RFC-074 PR-2: section-header now has two CTAs (enable + create custom). */
.section-header__actions { display: flex; gap: 8px; flex-shrink: 0; }
.btn-secondary {
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  padding: 9px 14px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
}
.btn-secondary:hover { background: var(--mc-bg-sunken); }
/* RFC-074 PR-2: empty state when no providers are enabled. */
.provider-empty {
  margin: 32px auto;
  max-width: 480px;
  padding: 36px 28px;
  text-align: center;
  border: 1px dashed var(--mc-border);
  border-radius: 16px;
  background: var(--mc-bg-elevated);
}
.provider-empty h3 { margin: 0 0 8px; font-size: 16px; color: var(--mc-text-primary); }
.provider-empty p { margin: 0 0 18px; font-size: 13px; color: var(--mc-text-tertiary); }

.save-tip { position: fixed; right: 24px; bottom: 24px; background: var(--mc-text-primary); color: var(--mc-text-inverse); padding: 10px 14px; border-radius: 10px; box-shadow: 0 10px 30px rgba(124, 63, 30, 0.22); }

@media (max-width: 900px) {
  .section-header { flex-direction: column; }
}
@media (max-width: 640px) {
  .provider-grid { grid-template-columns: 1fr; }
}
</style>
