<template>
  <div v-if="modelValue" class="modal-overlay" @click.self="close">
    <div class="wizard">
      <!-- Header -->
      <div class="wizard-header">
        <div class="wizard-title-row">
          <div class="wizard-icon-wrap">
            <img class="wizard-icon-img" :src="iconPath" :alt="channelType" />
          </div>
          <div class="wizard-title-text">
            <h2 class="wizard-title">{{ serviceName }}</h2>
            <p class="wizard-subtitle">{{ subtitle }}</p>
          </div>
          <button class="wizard-close" @click="close" :title="t('common.cancel')">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>

        <!-- Stepper -->
        <div class="stepper">
          <div v-for="(label, i) in stepLabels" :key="i" class="step" :class="stepClass(i)">
            <div class="step-circle">
              <svg v-if="i < currentStep" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <polyline points="20 6 9 17 4 12"/>
              </svg>
              <span v-else>{{ i + 1 }}</span>
            </div>
            <span class="step-label">{{ label }}</span>
            <div v-if="i < stepLabels.length - 1" class="step-connector" :class="{ done: i < currentStep }" />
          </div>
        </div>
      </div>

      <!-- Body: switch on currentStep -->
      <div class="wizard-body">
        <!-- ============ Step 1 · Configure ============ -->
        <div v-if="currentStep === 0" class="step-pane">
          <!-- Optional: name (only when not auto-derived) -->
          <div class="form-group">
            <label class="form-label">{{ t('channels.fields.name') }} <span class="required">*</span></label>
            <input v-model="form.name" class="form-input" :placeholder="t('channels.placeholders.name')" />
          </div>

          <!-- OAuth-style: scan card replaces the credential paste form.
               Two flavors:
                 1. Popup-SDK (wecom): button triggers an external window, we
                    only show the button + loading state.
                 2. QR-display (weixin/dingtalk/feishu): button fetches a QR
                    image from our backend, then we render it inline plus
                    a live polling-status line.
               In both flavors, the credential callback auto-advances to
               Step 2 — no extra confirmation click. -->
          <div v-if="isOAuthStyle" class="oauth-card">
            <p class="oauth-headline">{{ oauthHeadline }}</p>
            <p class="oauth-hint">{{ oauthHint }}</p>
            <button
              v-if="!oauthQrImg"
              type="button"
              class="oauth-btn"
              :disabled="oauthLoading"
              @click="onOAuthStart"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="3" height="3"/>
                <line x1="21" y1="14" x2="21" y2="17"/><line x1="14" y1="21" x2="17" y2="21"/>
              </svg>
              {{ oauthLoading ? t('common.loading') : oauthButtonLabel }}
            </button>
            <!-- QR placeholder while loading -->
            <div v-if="oauthLoading && !oauthQrImg && needsQrDisplay" class="oauth-qr-loading">
              <div class="spinner" />
              <p class="oauth-qr-status">{{ t('channels.dingtalkRegister.qrcodeLoading') }}…</p>
            </div>
            <!-- QR image + scan status -->
            <div v-if="oauthQrImg" class="oauth-qr">
              <img :src="oauthQrImg" alt="QR Code" class="oauth-qr-img" />
              <p class="oauth-qr-status" :class="oauthStatus">{{ oauthStatusText }}</p>
            </div>
          </div>

          <!-- QQ scan-to-bind (hybrid: shown alongside the manual fields so
               users can fall back to copy-paste if the portal blocks the
               vendor tag or they prefer the developer console). -->
          <div v-if="channelType === 'qq'" class="oauth-card oauth-card--hybrid">
            <p class="oauth-headline">{{ t('channels.qqRegister.hint') }}</p>
            <button
              v-if="!qqAuth.qrcodeUrl.value"
              type="button"
              class="oauth-btn"
              :disabled="qqAuth.loading.value"
              @click="qqAuth.start()"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="3" height="3"/>
                <line x1="21" y1="14" x2="21" y2="17"/><line x1="14" y1="21" x2="17" y2="21"/>
              </svg>
              {{ qqAuth.loading.value ? t('channels.qqRegister.buttonLoading') : t('channels.qqRegister.button') }}
            </button>
            <div v-if="qqAuth.loading.value && !qqAuth.qrcodeUrl.value" class="oauth-qr-loading">
              <div class="spinner" />
              <p class="oauth-qr-status">{{ t('channels.qqRegister.qrcodeLoading') }}…</p>
            </div>
            <div v-if="qqAuth.qrcodeUrl.value" class="oauth-qr">
              <img :src="qqAuth.qrcodeUrl.value" alt="QR Code" class="oauth-qr-img" />
              <p class="oauth-qr-status" :class="qqAuth.status.value">
                <template v-if="qqAuth.status.value === 'confirmed'">{{ t('channels.qqRegister.confirmed') }}</template>
                <template v-else-if="qqAuth.status.value === 'expired'">{{ t('channels.qqRegister.expired') }}</template>
                <template v-else-if="qqAuth.status.value === 'denied'">{{ t('channels.qqRegister.denied') }}</template>
                <template v-else>{{ t('channels.qqRegister.scanHint') }}</template>
              </p>
            </div>
          </div>

          <!-- How to get credentials (collapsed by default) -->
          <details v-if="!isOAuthStyle && webhookGuide" class="how-to">
            <summary class="how-to-summary">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9 18 15 12 9 6"/>
              </svg>
              {{ t('channels.wizard.howToGet') }}
            </summary>
            <ol class="how-to-steps">
              <li v-for="(step, i) in webhookGuide.steps" :key="i" v-html="step"></li>
            </ol>
          </details>

          <!-- Required credential fields (hidden for OAuth-style; the scan
               above fills them under the hood). -->
          <div v-if="!isOAuthStyle && requiredFields.length > 0" class="form-grid">
            <div v-for="field in requiredFields" :key="field.key" class="form-group full-width">
              <label class="form-label">
                {{ field.label }} <span v-if="field.required" class="required">*</span>
              </label>
              <div v-if="field.sensitive || field.type === 'password'" class="password-wrap">
                <input
                  v-model="channelConfig[field.key]"
                  :type="visibleFields[field.key] ? 'text' : 'password'"
                  class="form-input"
                  :class="{ 'field-error': invalidField === field.key }"
                  :placeholder="field.placeholder"
                  autocomplete="off"
                />
                <button type="button" class="eye-btn" @click="visibleFields[field.key] = !visibleFields[field.key]">
                  <svg v-if="visibleFields[field.key]" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>
                  </svg>
                  <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
                    <line x1="1" y1="1" x2="23" y2="23"/>
                  </svg>
                </button>
              </div>
              <input
                v-else
                v-model="channelConfig[field.key]"
                :type="field.type === 'number' ? 'number' : 'text'"
                class="form-input"
                :class="{ 'field-error': invalidField === field.key }"
                :placeholder="field.placeholder"
              />
              <span v-if="field.tooltip" class="form-hint">{{ field.tooltip }}</span>
            </div>
          </div>

          <!-- Empty-config channel types (web, webchat, webhook) -->
          <div v-else-if="!isOAuthStyle" class="empty-config">
            <p class="empty-text">{{ emptyConfigText }}</p>
          </div>

          <!-- Show advanced (collapses optional fields) — hidden for OAuth-style
               channels until the user reaches Step 3, where they can re-edit
               via the legacy modal if needed. -->
          <button v-if="!isOAuthStyle && optionalFields.length > 0" class="advanced-toggle" @click="showAdvanced = !showAdvanced">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
              :style="{ transform: showAdvanced ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.2s' }">
              <polyline points="9 18 15 12 9 6"/>
            </svg>
            {{ showAdvanced ? t('channels.wizard.back') : `${t('channels.advanced')} (${optionalFields.length})` }}
          </button>

          <div v-if="!isOAuthStyle && showAdvanced" class="advanced-body">
            <div v-for="field in optionalFields" :key="field.key" class="form-group full-width">
              <label class="form-label">
                {{ field.label }}
                <span v-if="field.tooltip" class="tooltip-icon" :title="field.tooltip">?</span>
              </label>
              <select v-if="field.type === 'select'" v-model="channelConfig[field.key]" class="form-input">
                <option v-for="opt in field.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
              <div v-else-if="field.type === 'switch'" class="switch-wrap">
                <label class="switch">
                  <input type="checkbox" v-model="channelConfig[field.key]" />
                  <span class="switch-slider"></span>
                </label>
                <span class="switch-label">{{ channelConfig[field.key] ? t('common.on') : t('common.off') }}</span>
              </div>
              <input v-else v-model="channelConfig[field.key]"
                     :type="field.type === 'number' ? 'number' : 'text'"
                     class="form-input" :placeholder="field.placeholder" />
            </div>
          </div>
        </div>

        <!-- ============ Step 2 · Verify ============ -->
        <div v-if="currentStep === 1" class="step-pane verify-pane">
          <div v-if="verifying" class="verify-card pending">
            <div class="spinner" />
            <p class="verify-headline">{{ t('channels.wizard.verifying', { service: serviceName }) }}</p>
          </div>
          <div v-else-if="verifyResult?.skipped" class="verify-card skipped">
            <div class="verify-icon">⏭</div>
            <p class="verify-headline">{{ t('channels.wizard.verifySkipped') }}</p>
            <p class="verify-detail">{{ t('channels.wizard.verifySkippedDetail') }}</p>
          </div>
          <div v-else-if="verifyResult?.ok" class="verify-card success">
            <div class="verify-icon">✓</div>
            <!-- Frontend-localized success headline: backend's English
                 `verifyResult.headline` is intentionally not used here
                 (would leak English into Chinese builds). The failed
                 branch below still uses it, because diagnostic messages
                 from upstream APIs don't translate well. -->
            <p class="verify-headline">
              {{ t('channels.wizard.verifySuccessHeadline', { service: serviceName }) }}
            </p>
            <p v-if="verifyResult.durationMs" class="verify-detail">
              {{ t('channels.wizard.durationMs', { n: verifyResult.durationMs }) }}
            </p>
          </div>
          <div v-else-if="verifyResult" class="verify-card failed">
            <div class="verify-icon">✗</div>
            <p class="verify-headline">{{ verifyResult.headline }}</p>
            <p v-if="verifyResult.hint" class="verify-detail">{{ verifyResult.hint }}</p>
          </div>
        </div>

        <!-- ============ Step 3 · Ready ============ -->
        <div v-if="currentStep === 2" class="step-pane ready-pane">
          <div class="ready-hero">
            <div class="ready-check">🎉</div>
            <h3 class="ready-title">{{ readyHeadline }}</h3>
            <p class="ready-subtitle">{{ t('channels.wizard.readySubtitle') }}</p>
          </div>

          <!-- Identity card (account/team/etc. from VerificationResult) -->
          <div v-if="hasIdentity" class="identity-card">
            <div v-for="(value, key) in identityDisplay" :key="key" class="identity-row">
              <span class="identity-key">{{ formatIdentityKey(String(key)) }}</span>
              <span class="identity-value">{{ value }}</span>
            </div>
          </div>

          <!-- Bind agent -->
          <div class="form-group">
            <label class="form-label">{{ t('channels.wizard.bindAgentLabel') }}</label>
            <AgentPickerDialog
              block
              :model-value="form.agentId ?? null"
              :agents="props.agents"
              :placeholder="t('channels.placeholders.selectAgent')"
              @update:model-value="(v) => (form.agentId = v ?? undefined)"
            />
          </div>

          <p class="ready-hint">{{ t('channels.wizard.sendTestHint', { service: serviceName }) }}</p>
        </div>
      </div>

      <!-- Footer actions -->
      <div class="wizard-footer">
        <button v-if="currentStep > 0" class="btn-secondary" @click="goBack">
          {{ t('channels.wizard.back') }}
        </button>
        <div class="footer-spacer" />
        <button
          v-if="currentStep === 0 && !isOAuthStyle"
          class="btn-primary"
          :disabled="!canSubmitConfig"
          @click="onSaveAndTest"
        >
          {{ hasVerifier ? t('channels.wizard.saveAndTest') : t('channels.wizard.continue') }}
        </button>
        <template v-else-if="currentStep === 1">
          <button v-if="verifyResult && !verifyResult.ok && !verifyResult.skipped" class="btn-primary" @click="onFixIt">
            {{ t('channels.wizard.fixIt') }}
          </button>
          <button v-else-if="verifyResult" class="btn-primary" @click="goNext">
            {{ t('channels.wizard.continue') }}
          </button>
        </template>
        <button v-else-if="currentStep === 2" class="btn-primary" @click="onDone" :disabled="saving">
          {{ saving ? t('common.loading') : t('channels.wizard.done') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { CHANNEL_FIELD_DEFS } from '@/types'
import type { Agent, Channel, ChannelFieldDef } from '@/types'
import { channelApi } from '@/api'
import {
  buildConfigJson,
  defaultAccessControl,
  defaultRenderConfig,
} from '@/utils/channelConfigJson'
import { useWecomBotAuth } from '@/composables/channels/useWecomBotAuth'
import { useWeixinQrcodePoll } from '@/composables/channels/useWeixinQrcodePoll'
import { useDingTalkAppRegister } from '@/composables/channels/useDingTalkAppRegister'
import { useQqAppRegister } from '@/composables/channels/useQqAppRegister'
import { useFeishuAppRegister } from '@/composables/channels/useFeishuAppRegister'
import AgentPickerDialog from '@/components/common/AgentPickerDialog.vue'

interface Props {
  modelValue: boolean
  channelType: string
  agents: Agent[]
  defaultName?: string
}

const props = withDefaults(defineProps<Props>(), { defaultName: '' })
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** Channel was created successfully — parent should refresh the list. */
  created: [channel: Channel]
}>()

const { t } = useI18n()

// Channel types whose verifiers exist on the backend right now. The list
// matches RFC-084 §4.3 — extending it requires no UI change, just register
// a new ChannelVerifier bean.
const VERIFIABLE_TYPES = new Set(['telegram', 'discord', 'slack', 'wecom', 'weixin', 'dingtalk', 'feishu'])

// Channel types whose Step 1 is an OAuth/QR scan instead of a credential
// paste form. Once the scan callback delivers credentials, Step 1 hands
// off to Step 2 automatically — there is no "Save & Test" button to press.
const OAUTH_STYLE_TYPES = new Set(['wecom', 'weixin', 'dingtalk', 'feishu'])

// QR-display flavor (button → fetch QR → render image inline → poll). WeCom
// uses an external SDK popup window instead, so it is excluded.
const QR_DISPLAY_TYPES = new Set(['weixin', 'dingtalk', 'feishu'])

// ==================== State ====================

const currentStep = ref(0)
const stepLabels = computed(() => [
  t('channels.wizard.step1'),
  t('channels.wizard.step2'),
  t('channels.wizard.step3'),
])

const form = ref<Partial<Channel>>({
  name: props.defaultName || translateServiceName(props.channelType),
  channelType: props.channelType,
  description: '',
  agentId: null as any,
  enabled: true,
})
const channelConfig = ref<Record<string, any>>({})
const visibleFields = ref<Record<string, boolean>>({})
const showAdvanced = ref(false)

const verifying = ref(false)
const verifyResult = ref<VerifyResult | null>(null)
const invalidField = ref<string | null>(null)
const saving = ref(false)

interface VerifyResult {
  ok: boolean
  skipped: boolean
  durationMs: number
  headline: string
  identity: Record<string, any>
  invalidField?: string | null
  hint?: string | null
}

// ==================== Derived ====================

const channelType = computed(() => props.channelType)
const iconPath = computed(() => `/icons/channels/${channelType.value}.svg`)
const serviceName = computed(() => translateServiceName(channelType.value))
const subtitle = computed(() =>
  t('channels.wizard.configureSubtitle', { service: serviceName.value })
)

const allFields = computed<ChannelFieldDef[]>(() => CHANNEL_FIELD_DEFS[channelType.value] || [])
const requiredFields = computed(() => allFields.value.filter((f) => f.required))
const optionalFields = computed(() => allFields.value.filter((f) => !f.required))

const hasVerifier = computed(() => VERIFIABLE_TYPES.has(channelType.value))
const isOAuthStyle = computed(() => OAUTH_STYLE_TYPES.has(channelType.value))

// ==================== OAuth-style Step 1 ====================
//
// For channels like WeCom, Step 1 is a single "Scan to Connect" button.
// The scan composable populates channelConfig under the hood and we
// auto-advance to Step 2, which calls preflight to actually verify the
// credentials work end-to-end.

// All four OAuth-style composables get instantiated up-front so reactive
// state is available regardless of channel type. Each one's onConfirmed
// fills the appropriate channelConfig fields then triggers onSaveAndTest()
// — the wizard auto-advances to Step 2 the moment credentials arrive,
// which is the magic that makes "scan once and watch the verify" work.

const wecomAuth = useWecomBotAuth((bot) => {
  channelConfig.value.bot_id = bot.botid
  channelConfig.value.secret = bot.secret
  void onSaveAndTest()
})

const weixinAuth = useWeixinQrcodePoll(({ botToken, baseUrl }) => {
  channelConfig.value.bot_token = botToken
  if (baseUrl) channelConfig.value.base_url = baseUrl
  void onSaveAndTest()
})

const dingtalkAuth = useDingTalkAppRegister(({ clientId, clientSecret }) => {
  channelConfig.value.client_id = clientId
  channelConfig.value.client_secret = clientSecret
  void onSaveAndTest()
})

const feishuAuth = useFeishuAppRegister(({ appId, appSecret }) => {
  channelConfig.value.app_id = appId
  channelConfig.value.app_secret = appSecret
  void onSaveAndTest()
})

// QQ scan-to-bind is hybrid (kept alongside manual fields), so we don't
// auto-advance — let the user see fields populated and click Next when ready.
const qqAuth = useQqAppRegister(({ appId, clientSecret }) => {
  channelConfig.value.app_id = appId
  channelConfig.value.client_secret = clientSecret
})

const needsQrDisplay = computed(() => QR_DISPLAY_TYPES.has(channelType.value))

const oauthLoading = computed(() => {
  switch (channelType.value) {
    case 'wecom': return wecomAuth.loading.value
    case 'weixin': return weixinAuth.loading.value
    case 'dingtalk': return dingtalkAuth.loading.value
    case 'feishu': return feishuAuth.loading.value
    default: return false
  }
})

const oauthQrImg = computed(() => {
  switch (channelType.value) {
    case 'weixin': return weixinAuth.qrcodeImg.value
    case 'dingtalk': return dingtalkAuth.qrcodeUrl.value
    case 'feishu': return feishuAuth.qrcodeUrl.value
    default: return ''
  }
})

const oauthStatus = computed(() => {
  switch (channelType.value) {
    case 'weixin': return weixinAuth.pollStatus.value
    case 'dingtalk': return dingtalkAuth.status.value
    case 'feishu': return feishuAuth.status.value
    default: return ''
  }
})

const oauthStatusText = computed(() => {
  switch (channelType.value) {
    case 'weixin': {
      switch (weixinAuth.pollStatus.value) {
        case 'scanned': return t('channels.weixin.scanned')
        case 'confirmed': return t('channels.weixin.loginSuccess')
        case 'expired': return t('channels.weixin.qrcodeExpired')
        default: return t('channels.weixin.scanHint')
      }
    }
    case 'dingtalk': {
      switch (dingtalkAuth.status.value) {
        case 'confirmed': return t('channels.dingtalkRegister.confirmed')
        case 'expired': return t('channels.dingtalkRegister.expired')
        case 'denied': return t('channels.dingtalkRegister.denied')
        default: return t('channels.dingtalkRegister.scanHint')
      }
    }
    case 'feishu': {
      switch (feishuAuth.status.value) {
        case 'confirmed': return t('channels.feishuRegister.confirmed')
        case 'expired': return t('channels.feishuRegister.expired')
        case 'denied': return t('channels.feishuRegister.denied')
        case 'error': return t('channels.feishuRegister.error')
        default: return t('channels.feishuRegister.scanHint')
      }
    }
    default: return ''
  }
})

const oauthHeadline = computed(() => {
  switch (channelType.value) {
    case 'wecom': return t('channels.wecom.authHint')
    case 'weixin': return t('channels.weixin.authHint')
    case 'dingtalk': return t('channels.dingtalkRegister.hint')
    case 'feishu': return t('channels.feishuRegister.hint')
    default: return ''
  }
})

const oauthHint = computed(() => {
  if (!isOAuthStyle.value) return ''
  return t('channels.wizard.oauthScanHint', { service: serviceName.value })
})

const oauthButtonLabel = computed(() => {
  switch (channelType.value) {
    case 'wecom': return t('channels.wecom.authButton')
    case 'weixin': return t('channels.weixin.qrcodeButton')
    case 'dingtalk': return t('channels.dingtalkRegister.button')
    case 'feishu': return t('channels.feishuRegister.button')
    default: return t('channels.wizard.saveAndTest')
  }
})

function onOAuthStart() {
  switch (channelType.value) {
    case 'wecom': wecomAuth.start(); break
    case 'weixin': weixinAuth.start(); break
    case 'dingtalk': dingtalkAuth.start(); break
    case 'feishu': feishuAuth.start(getDomainOrDefault()); break
  }
}

function getDomainOrDefault(): string {
  // useFeishuAppRegister.start(domain) takes the region selector. We default
  // to 'feishu' (China) since that's what the field def defaults to; users
  // who need Lark international can still pick it post-Step-3 in advanced
  // edit. Adding a region toggle to Step 1 would defeat the "one button"
  // simplicity that justifies this redesign.
  return (channelConfig.value.domain as string) || 'feishu'
}

const emptyConfigText = computed(() => {
  switch (channelType.value) {
    case 'web': return t('channels.webHint')
    case 'webchat': return t('channels.webchatHint')
    case 'webhook': return t('channels.webhookHint')
    default: return ''
  }
})

const canSubmitConfig = computed(() => {
  if (!form.value.name) return false
  return requiredFields.value.every((f) => {
    const v = channelConfig.value[f.key]
    return v !== undefined && v !== null && String(v).length > 0
  })
})

const webhookGuide = computed(() => {
  const guides: Record<string, string[]> = {
    telegram: [
      t('channels.guide.telegram.step1'),
      t('channels.guide.telegram.step2'),
      t('channels.guide.telegram.step3'),
      t('channels.guide.telegram.step4'),
    ],
    discord: [
      t('channels.guide.discord.step1'),
      t('channels.guide.discord.step2'),
      t('channels.guide.discord.step3'),
      t('channels.guide.discord.step4'),
      t('channels.guide.discord.step5'),
    ],
    qq: [
      t('channels.guide.qq.step1'),
      t('channels.guide.qq.step2'),
      t('channels.guide.qq.step3'),
      t('channels.guide.qq.step4'),
    ],
  }
  const steps = guides[channelType.value]
  return steps ? { steps } : null
})

const hasIdentity = computed(() => verifyResult.value && Object.keys(verifyResult.value.identity || {}).length > 0)
const identityDisplay = computed(() => verifyResult.value?.identity || {})
const readyHeadline = computed(() => {
  const id = verifyResult.value?.identity || {}
  if (id.accountName) return `${t('channels.wizard.readyHeadline')} — ${id.accountName}`
  return t('channels.wizard.readyHeadline')
})

// ==================== Lifecycle ====================

onMounted(() => {
  // Seed default values for optional fields with defaultValue, so the
  // submitted configJson reflects the same shape that the old modal builds.
  for (const f of allFields.value) {
    if (f.defaultValue !== undefined && channelConfig.value[f.key] === undefined) {
      channelConfig.value[f.key] = f.defaultValue
    }
  }
})

// ==================== Step navigation ====================

function stepClass(i: number) {
  if (i < currentStep.value) return 'done'
  if (i === currentStep.value) return 'active'
  return 'pending'
}

function goBack() {
  if (currentStep.value > 0) currentStep.value -= 1
}
function goNext() {
  if (currentStep.value < 2) currentStep.value += 1
}

async function onSaveAndTest() {
  invalidField.value = null
  if (!hasVerifier.value) {
    // No verifier — skip Step 2 with a synthetic skipped result, jump to Ready.
    verifyResult.value = {
      ok: true,
      skipped: true,
      durationMs: 0,
      headline: t('channels.wizard.verifySkipped'),
      identity: {},
    }
    currentStep.value = 2
    return
  }
  currentStep.value = 1
  verifying.value = true
  verifyResult.value = null
  try {
    const configJson = buildConfigJson({
      channelType: channelType.value,
      channelConfig: channelConfig.value,
      accessControl: defaultAccessControl(),
      renderConfig: defaultRenderConfig(),
    })
    const res: any = await channelApi.preflight(channelType.value, configJson)
    verifyResult.value = res.data as VerifyResult
    if (!verifyResult.value.ok && !verifyResult.value.skipped) {
      invalidField.value = verifyResult.value.invalidField || null
    }
  } catch (e: any) {
    verifyResult.value = {
      ok: false,
      skipped: false,
      durationMs: 0,
      headline: t('channels.wizard.verifyFailed'),
      identity: {},
      hint: e?.message || '',
    }
  } finally {
    verifying.value = false
  }
}

function onFixIt() {
  // Return to Step 1 with the bad field highlighted.
  currentStep.value = 0
}

async function onDone() {
  saving.value = true
  try {
    const configJson = buildConfigJson({
      channelType: channelType.value,
      channelConfig: channelConfig.value,
      accessControl: defaultAccessControl(),
      renderConfig: defaultRenderConfig(),
    })
    // Persist identity from the verifyResult so the list page can show
    // "Connected as @MyBot" without waiting for the next adapter probe.
    // Skipped channels (web/webchat/webhook) carry an empty identity, so
    // the field stays null in the DB and the legacy description path is
    // still available as a fallback.
    const identityMap = verifyResult.value?.identity || {}
    const identityJson = Object.keys(identityMap).length > 0
      ? JSON.stringify(identityMap)
      : undefined
    const payload: Partial<Channel> = {
      ...form.value,
      configJson,
      identityJson,
      enabled: true,
    }
    const res: any = await channelApi.create(payload)
    mcToast.success(t('channels.messages.saveSuccess'))
    emit('created', res.data as Channel)
    close()
  } catch (e: any) {
    mcToast.error(e?.message || t('channels.wizard.saveFailed'))
  } finally {
    saving.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}

// ==================== Helpers ====================

function translateServiceName(type: string): string {
  const k = `channels.types.${type}`
  const translated = t(k)
  return translated === k ? type : translated
}

function formatIdentityKey(key: string): string {
  // Look up the key's localized label in channels.wizard.identity.* first;
  // when missing (e.g. a brand-new identity field a verifier just started
  // returning, before i18n catches up), fall back to a camelCase-to-spaces
  // conversion so the row still renders something readable.
  const i18nKey = `channels.wizard.identity.${key}`
  const translated = t(i18nKey)
  if (translated !== i18nKey) return translated
  return key
    .replace(/([A-Z])/g, ' $1')
    .replace(/^./, (s) => s.toUpperCase())
    .trim()
}
</script>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.wizard { background: var(--mc-bg-elevated); border-radius: 18px; width: 100%; max-width: 640px; max-height: 92vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.18); overflow: hidden; }

/* ===== Header ===== */
.wizard-header { padding: 22px 26px 18px; border-bottom: 1px solid var(--mc-border-light); }
.wizard-title-row { display: flex; align-items: center; gap: 14px; }
.wizard-icon-wrap { width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center; background: var(--mc-bg-sunken); flex-shrink: 0; overflow: hidden; }
.wizard-icon-img { width: 38px; height: 38px; object-fit: cover; }
.wizard-title-text { flex: 1; min-width: 0; }
.wizard-title { font-size: 19px; font-weight: 700; color: var(--mc-text-primary); margin: 0; }
.wizard-subtitle { font-size: 13px; color: var(--mc-text-secondary); margin: 2px 0 0; }
.wizard-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 8px; flex-shrink: 0; }
.wizard-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }

/* ===== Stepper ===== */
.stepper { display: flex; align-items: center; gap: 0; margin-top: 18px; padding: 0 6px; }
.step { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.step-circle { width: 26px; height: 26px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700; transition: all 0.18s; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); border: 1.5px solid var(--mc-border); }
.step.active .step-circle { background: var(--mc-primary); color: #fff; border-color: var(--mc-primary); }
.step.done .step-circle { background: var(--mc-primary); color: #fff; border-color: var(--mc-primary); }
.step-label { font-size: 13px; color: var(--mc-text-tertiary); font-weight: 500; }
.step.active .step-label { color: var(--mc-primary); font-weight: 700; }
.step.done .step-label { color: var(--mc-text-primary); }
.step-connector { flex: 1; min-width: 36px; height: 1.5px; background: var(--mc-border); margin: 0 12px; align-self: center; transition: background 0.18s; }
.step-connector.done { background: var(--mc-primary); }

/* ===== Body ===== */
.wizard-body { flex: 1; overflow-y: auto; padding: 22px 26px; }
.step-pane { display: flex; flex-direction: column; gap: 18px; }

/* ===== Forms ===== */
.form-grid { display: grid; grid-template-columns: 1fr; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); display: flex; align-items: center; gap: 5px; }
.required { color: var(--mc-danger, #ef4444); }
.form-input { padding: 10px 12px; border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-elevated); width: 100%; box-sizing: border-box; transition: border-color 0.15s; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 3px rgba(217,119,87,0.12); }
.form-input.field-error { border-color: var(--mc-danger, #ef4444); box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.12); }
.form-hint { font-size: 12px; color: var(--mc-text-tertiary); line-height: 1.5; }
.password-wrap { position: relative; display: flex; align-items: center; }
.password-wrap .form-input { padding-right: 36px; }
.eye-btn { position: absolute; right: 8px; background: none; border: none; cursor: pointer; color: var(--mc-text-tertiary); padding: 2px; display: flex; align-items: center; }
.eye-btn:hover { color: var(--mc-text-primary); }

.tooltip-icon { display: inline-flex; align-items: center; justify-content: center; width: 14px; height: 14px; border-radius: 50%; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); font-size: 10px; font-weight: 700; cursor: help; }

/* ===== OAuth-style scan card (WeCom etc.) ===== */
.oauth-card { background: linear-gradient(135deg, rgba(7, 193, 96, 0.06), rgba(7, 193, 96, 0.02)); border: 1px solid rgba(7, 193, 96, 0.18); border-radius: 12px; padding: 18px 18px 16px; display: flex; flex-direction: column; gap: 8px; }
.oauth-headline { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); margin: 0; line-height: 1.5; }
.oauth-hint { font-size: 12px; color: var(--mc-text-secondary); margin: 0; line-height: 1.6; }
.oauth-btn { display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 6px; padding: 11px 18px; background: #07C160; color: #fff; border: none; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.18s; }
.oauth-btn:hover:not(:disabled) { background: #06AD56; transform: translateY(-1px); box-shadow: 0 4px 12px rgba(7,193,96,0.3); }
.oauth-btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* QR display (weixin/dingtalk/feishu) */
.oauth-qr { display: flex; flex-direction: column; align-items: center; gap: 10px; margin-top: 8px; padding: 14px; background: #fff; border-radius: 10px; border: 1px solid var(--mc-border); }
.oauth-qr-img { width: 200px; height: 200px; border-radius: 4px; }
.oauth-qr-loading { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; min-height: 220px; padding: 14px; background: #fff; border-radius: 10px; border: 1px dashed var(--mc-border); margin-top: 8px; }
.oauth-qr-status { font-size: 13px; color: var(--mc-text-secondary); margin: 0; text-align: center; }
.oauth-qr-status.scanned { color: #E6A23C; }
.oauth-qr-status.confirmed { color: #10b981; font-weight: 600; }
.oauth-qr-status.expired, .oauth-qr-status.denied, .oauth-qr-status.error { color: #f56c6c; }

/* ===== How-to-get details ===== */
.how-to { background: var(--mc-bg-sunken); border-radius: 10px; padding: 10px 14px; }
.how-to-summary { font-size: 13px; font-weight: 600; color: var(--mc-text-secondary); cursor: pointer; display: flex; align-items: center; gap: 6px; list-style: none; user-select: none; }
.how-to-summary::-webkit-details-marker { display: none; }
.how-to-summary svg { transition: transform 0.18s; }
.how-to[open] .how-to-summary svg { transform: rotate(90deg); }
.how-to-steps { margin: 10px 0 0; padding-left: 22px; font-size: 13px; color: var(--mc-text-secondary); line-height: 1.7; }
.how-to-steps :deep(a) { color: var(--mc-primary); text-decoration: none; }
.how-to-steps :deep(a:hover) { text-decoration: underline; }
.how-to-steps :deep(code) { font-size: 12px; background: var(--mc-bg-elevated); padding: 1px 5px; border-radius: 3px; }
.how-to-steps :deep(b) { color: var(--mc-text-primary); }

/* ===== Advanced ===== */
.advanced-toggle { display: flex; align-items: center; gap: 6px; background: none; border: none; cursor: pointer; font-size: 13px; font-weight: 600; color: var(--mc-text-secondary); padding: 4px 0; align-self: flex-start; }
.advanced-toggle:hover { color: var(--mc-text-primary); }
.advanced-body { display: flex; flex-direction: column; gap: 14px; padding: 4px 0 0; }
.switch-wrap { display: flex; align-items: center; gap: 8px; height: 36px; }
.switch { position: relative; display: inline-block; width: 36px; height: 20px; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch-slider { position: absolute; cursor: pointer; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.switch-slider::before { content: ""; position: absolute; height: 14px; width: 14px; left: 3px; bottom: 3px; background: white; border-radius: 50%; transition: 0.2s; }
.switch input:checked + .switch-slider { background: var(--mc-primary); }
.switch input:checked + .switch-slider::before { transform: translateX(16px); }
.switch-label { font-size: 13px; color: var(--mc-text-secondary); }

.empty-config { padding: 24px 16px; text-align: center; background: var(--mc-bg-sunken); border-radius: 10px; }
.empty-text { font-size: 13px; color: var(--mc-text-tertiary); margin: 0; line-height: 1.6; }

/* ===== Verify pane ===== */
.verify-pane { min-height: 220px; align-items: center; justify-content: center; }
.verify-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 36px 20px; border-radius: 14px; text-align: center; width: 100%; max-width: 420px; }
.verify-card.pending { background: var(--mc-bg-sunken); }
.verify-card.success { background: rgba(16, 185, 129, 0.06); border: 1px solid rgba(16, 185, 129, 0.2); }
.verify-card.failed { background: rgba(239, 68, 68, 0.06); border: 1px solid rgba(239, 68, 68, 0.2); }
.verify-card.skipped { background: var(--mc-bg-sunken); }
.verify-icon { font-size: 36px; line-height: 1; }
.verify-card.success .verify-icon { color: #10b981; }
.verify-card.failed .verify-icon { color: #ef4444; }
.verify-headline { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.verify-detail { font-size: 13px; color: var(--mc-text-secondary); margin: 0; line-height: 1.6; }
.spinner { width: 36px; height: 36px; border: 3px solid var(--mc-border); border-top-color: var(--mc-primary); border-radius: 50%; animation: wizard-spin 0.8s linear infinite; }
@keyframes wizard-spin { to { transform: rotate(360deg); } }

/* ===== Ready pane ===== */
.ready-pane { gap: 20px; }
.ready-hero { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 16px 0 8px; }
.ready-check { font-size: 44px; line-height: 1; }
.ready-title { font-size: 18px; font-weight: 700; color: var(--mc-text-primary); margin: 10px 0 4px; }
.ready-subtitle { font-size: 13px; color: var(--mc-text-secondary); margin: 0; }
.identity-card { background: var(--mc-bg-sunken); border-radius: 10px; padding: 12px 16px; display: flex; flex-direction: column; gap: 8px; }
.identity-row { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; }
.identity-key { color: var(--mc-text-tertiary); font-weight: 500; }
.identity-value { color: var(--mc-text-primary); font-weight: 600; word-break: break-all; text-align: right; }
.ready-hint { font-size: 12px; color: var(--mc-text-tertiary); margin: 0; line-height: 1.5; text-align: center; }

/* ===== Footer ===== */
.wizard-footer { display: flex; align-items: center; gap: 10px; padding: 16px 26px; border-top: 1px solid var(--mc-border-light); background: var(--mc-bg-elevated); }
.footer-spacer { flex: 1; }
.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 22px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 12px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); transition: all 0.15s; }
.btn-primary:hover:not(:disabled) { transform: translateY(-1px); box-shadow: var(--mc-shadow-medium); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; box-shadow: none; }
.btn-secondary { padding: 10px 18px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; font-weight: 600; cursor: pointer; transition: background 0.15s; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

.fade-enter-active, .fade-leave-active { transition: opacity 0.15s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
