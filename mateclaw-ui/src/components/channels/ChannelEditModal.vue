<template>
  <div v-if="modelValue" class="modal-overlay">
    <div class="modal">
      <div class="modal-header">
        <h2>{{ editingChannel ? t('channels.modal.editTitle') : t('channels.modal.newTitle') }}</h2>
        <button class="modal-close" @click="close">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <!-- 基础信息 -->
        <div class="form-grid">
          <div class="form-group">
            <label class="form-label">{{ t('channels.fields.name') }} <span class="required">*</span></label>
            <input v-model="form.name" class="form-input" :placeholder="t('channels.placeholders.name')" />
          </div>
          <div class="form-group">
            <label class="form-label">{{ t('channels.fields.type') }}</label>
            <select v-model="form.channelType" class="form-input" @change="onChannelTypeChange">
              <option value="web">{{ t('channels.types.web') }}</option>
              <option value="dingtalk">{{ t('channels.types.dingtalk') }}</option>
              <option value="feishu">{{ t('channels.types.feishu') }}</option>
              <option value="telegram">{{ t('channels.types.telegram') }}</option>
              <option value="discord">{{ t('channels.types.discord') }}</option>
              <option value="wecom">{{ t('channels.types.wecom') }}</option>
              <option value="weixin">{{ t('channels.types.weixin') }}</option>
              <option value="qq">{{ t('channels.types.qq') }}</option>
              <option value="slack">{{ t('channels.types.slack') }}</option>
              <option value="webchat">{{ t('channels.types.webchat') }}</option>
              <option value="webhook">{{ t('channels.types.webhook') }}</option>
            </select>
          </div>
          <div class="form-group full-width">
            <label class="form-label">{{ t('channels.fields.description') }}</label>
            <input v-model="form.description" class="form-input" :placeholder="t('channels.placeholders.description')" />
          </div>
          <div class="form-group">
            <label class="form-label">{{ t('channels.fields.bindAgent') }}</label>
            <AgentPickerDialog
              block
              :model-value="form.agentId ?? null"
              :agents="props.agents"
              :placeholder="t('channels.placeholders.selectAgent')"
              @update:model-value="(v) => (form.agentId = v ?? undefined)"
            />
          </div>
        </div>

        <div class="config-section">
          <div class="tab-bar">
            <button class="tab-btn" :class="{ active: configTab === 'form' }" @click="switchTab('form')">{{ t('channels.tabs.form') }}</button>
            <button class="tab-btn" :class="{ active: configTab === 'json' }" @click="switchTab('json')">{{ t('channels.tabs.json') }}</button>
          </div>

          <!-- 表单配置 -->
          <div v-if="configTab === 'form'" class="tab-content">
            <!-- 接入引导 -->
            <div v-if="webhookGuide" class="guide-card">
              <div v-if="needsWebhookUrl" class="guide-webhook-row">
                <span class="guide-label">Webhook URL</span>
                <code class="guide-url">{{ webhookUrl }}</code>
                <button type="button" class="copy-btn" @click="copyWebhookUrl" :title="t('common.copy')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                  </svg>
                  {{ copyLabel }}
                </button>
              </div>
              <div v-if="needsWebhookUrl && isLocalhost" class="guide-warn">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                  <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
                </svg>
                {{ t('channels.webhook.localhostWarn') }}
              </div>
              <ol class="guide-steps">
                <li v-for="(step, i) in webhookGuide.steps" :key="i" v-html="step"></li>
              </ol>
            </div>

            <!-- 微信扫码登录 -->
            <div v-if="form.channelType === 'weixin'" class="weixin-auth-card">
              <p class="weixin-auth-hint">{{ t('channels.weixin.authHint') }}</p>
              <div v-if="editingChannel && channelConfig.bot_token" class="weixin-multi-account-hint">
                <p class="hint-warning">⚠️ {{ t('channels.weixin.replaceWarning') }}</p>
                <button type="button" class="weixin-add-account-btn" @click="emit('add-new-weixin')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="16"/><line x1="8" y1="12" x2="16" y2="12"/>
                  </svg>
                  {{ t('channels.weixin.addNewAccount') }}
                </button>
              </div>
              <button type="button" class="weixin-auth-btn" @click="weixin.start" :disabled="weixin.loading.value">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                  <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="3" height="3"/>
                  <line x1="21" y1="14" x2="21" y2="17"/><line x1="14" y1="21" x2="17" y2="21"/>
                  <line x1="21" y1="21" x2="21" y2="21"/>
                </svg>
                {{ editingChannel && channelConfig.bot_token
                  ? t('channels.weixin.rescanButton')
                  : (weixin.loading.value ? t('channels.weixin.qrcodeLoading') : t('channels.weixin.qrcodeButton'))
                }}
              </button>
              <div v-if="weixin.qrcodeImg.value || weixin.pollStatus.value === 'confirmed'" class="weixin-qrcode-area">
                <img v-if="weixin.qrcodeImg.value" :src="weixin.qrcodeImg.value" alt="WeChat QR Code" class="weixin-qrcode-img" />
                <p class="weixin-scan-hint" :class="weixin.pollStatus.value">
                  <template v-if="weixin.pollStatus.value === 'scanned'">{{ t('channels.weixin.scanned') }}</template>
                  <template v-else-if="weixin.pollStatus.value === 'confirmed'">{{ t('channels.weixin.loginSuccess') }}</template>
                  <template v-else-if="weixin.pollStatus.value === 'expired'">{{ t('channels.weixin.qrcodeExpired') }}</template>
                  <template v-else>{{ t('channels.weixin.scanHint') }}</template>
                </p>
              </div>
            </div>

            <!-- 钉钉一键机器人注册（OAuth Device Flow） -->
            <div v-if="form.channelType === 'dingtalk'" class="dingtalk-register-card">
              <div class="dingtalk-register-header">
                <strong>{{ t('channels.dingtalkRegister.title') }}</strong>
              </div>
              <p class="dingtalk-register-hint">{{ t('channels.dingtalkRegister.hint') }}</p>
              <button
                type="button"
                class="dingtalk-register-btn"
                @click="dingtalkRegister.start()"
                :disabled="dingtalkRegister.loading.value || dingtalkRegister.status.value === 'waiting'"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                  <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="3" height="3"/>
                  <line x1="21" y1="14" x2="21" y2="17"/><line x1="14" y1="21" x2="17" y2="21"/>
                  <line x1="21" y1="21" x2="21" y2="21"/>
                </svg>
                {{ dingtalkRegister.loading.value
                  ? t('channels.dingtalkRegister.buttonLoading')
                  : t('channels.dingtalkRegister.button') }}
              </button>
              <!-- Loading placeholder (same dimensions as the QR area to avoid layout shift) -->
              <div v-if="dingtalkRegister.loading.value && !dingtalkRegister.qrcodeUrl.value" class="dingtalk-register-qrcode dingtalk-register-qrcode--loading">
                <div class="dingtalk-register-qrcode-spinner"></div>
                <p class="dingtalk-register-status">{{ t('channels.dingtalkRegister.qrcodeLoading') }}…</p>
              </div>
              <div v-else-if="dingtalkRegister.qrcodeUrl.value" class="dingtalk-register-qrcode">
                <img :src="dingtalkRegister.qrcodeUrl.value" :alt="t('channels.dingtalkRegister.button')" class="dingtalk-register-qrcode-img" />
                <p class="dingtalk-register-status" :class="dingtalkRegister.status.value">
                  <template v-if="dingtalkRegister.status.value === 'confirmed'">{{ t('channels.dingtalkRegister.confirmed') }}</template>
                  <template v-else-if="dingtalkRegister.status.value === 'expired'">{{ t('channels.dingtalkRegister.expired') }}</template>
                  <template v-else-if="dingtalkRegister.status.value === 'denied'">{{ t('channels.dingtalkRegister.denied') }}</template>
                  <template v-else>{{ t('channels.dingtalkRegister.scanHint') }}</template>
                </p>
              </div>
            </div>

            <!-- 飞书一键应用注册（oapi-sdk 2.6+） -->
            <div v-if="form.channelType === 'feishu'" class="feishu-register-card">
              <div class="feishu-register-header">
                <strong>{{ t('channels.feishuRegister.title') }}</strong>
              </div>
              <p class="feishu-register-hint">{{ t('channels.feishuRegister.hint') }}</p>
              <button
                type="button"
                class="feishu-register-btn"
                @click="feishuRegister.start(channelConfig.domain || 'feishu')"
                :disabled="feishuRegister.loading.value || feishuRegister.status.value === 'waiting'"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                  <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="3" height="3"/>
                  <line x1="21" y1="14" x2="21" y2="17"/><line x1="14" y1="21" x2="17" y2="21"/>
                  <line x1="21" y1="21" x2="21" y2="21"/>
                </svg>
                {{ feishuRegister.loading.value
                  ? t('channels.feishuRegister.buttonLoading')
                  : t('channels.feishuRegister.button') }}
              </button>
              <!-- Loading placeholder (same dimensions as the QR area to avoid layout shift) -->
              <div v-if="feishuRegister.loading.value && !feishuRegister.qrcodeUrl.value" class="feishu-register-qrcode feishu-register-qrcode--loading">
                <div class="feishu-register-qrcode-spinner"></div>
                <p class="feishu-register-status">{{ t('channels.feishuRegister.qrcodeLoading') }}…</p>
              </div>
              <div v-else-if="feishuRegister.qrcodeUrl.value" class="feishu-register-qrcode">
                <img :src="feishuRegister.qrcodeUrl.value" :alt="t('channels.feishuRegister.button')" class="feishu-register-qrcode-img" />
                <p class="feishu-register-status" :class="feishuRegister.status.value">
                  <template v-if="feishuRegister.status.value === 'confirmed'">{{ t('channels.feishuRegister.confirmed') }}</template>
                  <template v-else-if="feishuRegister.status.value === 'expired'">{{ t('channels.feishuRegister.expired') }}</template>
                  <template v-else-if="feishuRegister.status.value === 'denied'">{{ t('channels.feishuRegister.denied') }}</template>
                  <template v-else-if="feishuRegister.status.value === 'error'">{{ t('channels.feishuRegister.error') }}</template>
                  <template v-else>{{ t('channels.feishuRegister.scanHint') }}</template>
                </p>
              </div>
            </div>

            <!-- QQ 扫码绑定（QQ 开放平台 Lite portal） -->
            <div v-if="form.channelType === 'qq'" class="qq-register-card">
              <div class="qq-register-header">
                <strong>{{ t('channels.qqRegister.title') }}</strong>
              </div>
              <p class="qq-register-hint">{{ t('channels.qqRegister.hint') }}</p>
              <button
                type="button"
                class="qq-register-btn"
                @click="qqRegister.start()"
                :disabled="qqRegister.loading.value || qqRegister.status.value === 'waiting'"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                  <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="3" height="3"/>
                  <line x1="21" y1="14" x2="21" y2="17"/><line x1="14" y1="21" x2="17" y2="21"/>
                  <line x1="21" y1="21" x2="21" y2="21"/>
                </svg>
                {{ qqRegister.loading.value
                  ? t('channels.qqRegister.buttonLoading')
                  : t('channels.qqRegister.button') }}
              </button>
              <div v-if="qqRegister.loading.value && !qqRegister.qrcodeUrl.value" class="qq-register-qrcode qq-register-qrcode--loading">
                <div class="qq-register-qrcode-spinner"></div>
                <p class="qq-register-status">{{ t('channels.qqRegister.qrcodeLoading') }}…</p>
              </div>
              <div v-else-if="qqRegister.qrcodeUrl.value" class="qq-register-qrcode">
                <img :src="qqRegister.qrcodeUrl.value" :alt="t('channels.qqRegister.button')" class="qq-register-qrcode-img" />
                <p class="qq-register-status" :class="qqRegister.status.value">
                  <template v-if="qqRegister.status.value === 'confirmed'">{{ t('channels.qqRegister.confirmed') }}</template>
                  <template v-else-if="qqRegister.status.value === 'expired'">{{ t('channels.qqRegister.expired') }}</template>
                  <template v-else-if="qqRegister.status.value === 'denied'">{{ t('channels.qqRegister.denied') }}</template>
                  <template v-else>{{ t('channels.qqRegister.scanHint') }}</template>
                </p>
              </div>
            </div>

            <!-- 企业微信扫码授权 -->
            <div v-if="form.channelType === 'wecom'" class="wecom-auth-card">
              <p class="wecom-auth-hint">{{ t('channels.wecom.authHint') }}</p>
              <button type="button" class="wecom-auth-btn" @click="wecom.start" :disabled="wecom.loading.value">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                  <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="3" height="3"/>
                  <line x1="21" y1="14" x2="21" y2="17"/><line x1="14" y1="21" x2="17" y2="21"/>
                  <line x1="21" y1="21" x2="21" y2="21"/>
                </svg>
                {{ wecom.loading.value ? t('channels.wecom.authLoading') : t('channels.wecom.authButton') }}
              </button>
            </div>

            <!-- 渠道专属字段 -->
            <template v-if="currentFieldDefs.length > 0">
              <div class="form-grid">
                <div
                  v-for="field in currentFieldDefs" :key="field.key"
                  class="form-group"
                  :class="{ 'full-width': field.type === 'text' && (field.placeholder?.length || 0) > 30 }"
                >
                  <label class="form-label">
                    {{ field.label }}
                    <span v-if="field.required" class="required">*</span>
                    <span v-if="field.tooltip" class="tooltip-icon" :title="field.tooltip">?</span>
                  </label>

                  <!-- 密码字段 -->
                  <div v-if="field.sensitive || field.type === 'password'" class="password-wrap">
                    <input
                      v-model="channelConfig[field.key]"
                      :type="visibleFields[field.key] ? 'text' : 'password'"
                      class="form-input" :placeholder="field.placeholder" :readonly="field.readOnly"
                      autocomplete="off"
                    />
                    <button v-if="!field.readOnly" type="button" class="eye-btn"
                      @click="visibleFields[field.key] = !visibleFields[field.key]"
                      :title="visibleFields[field.key] ? t('common.hide') : t('common.show')">
                      <svg v-if="visibleFields[field.key]" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>
                      </svg>
                      <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
                        <line x1="1" y1="1" x2="23" y2="23"/>
                      </svg>
                    </button>
                    <button v-else-if="channelConfig[field.key]" type="button" class="copy-inline-btn"
                      @click="copyText(String(channelConfig[field.key]))" :title="t('common.copy')">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                      </svg>
                    </button>
                  </div>

                  <select v-else-if="field.type === 'select'" v-model="channelConfig[field.key]" class="form-input">
                    <option v-for="opt in field.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                  </select>

                  <div v-else-if="field.type === 'switch'" class="switch-wrap">
                    <label class="switch">
                      <input type="checkbox" v-model="channelConfig[field.key]" />
                      <span class="switch-slider"></span>
                    </label>
                    <span class="switch-label">{{ channelConfig[field.key] ? t('common.on') : t('common.off') }}</span>
                  </div>

                  <input v-else-if="field.type === 'number'" v-model.number="channelConfig[field.key]" type="number"
                    class="form-input" :placeholder="field.placeholder" :readonly="field.readOnly" />

                  <input v-else v-model="channelConfig[field.key]" type="text"
                    class="form-input" :placeholder="field.placeholder" :readonly="field.readOnly" />

                  <span v-if="field.readOnly && field.key === 'api_key'" class="form-hint">
                    {{ editingChannel ? t('channels.webchatApiKeyReadOnly') : t('channels.webchatApiKeyGenerated') }}
                  </span>
                </div>
              </div>
            </template>

            <!-- 飞书所需权限 -->
            <div v-if="form.channelType === 'feishu'" class="permission-hints">
              <div class="permission-header">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                </svg>
                <span>{{ t('channels.feishuPermissions.title') }}</span>
                <a :href="feishuPermissionUrl" target="_blank" rel="noopener" class="permission-link">{{ t('channels.feishuPermissions.goToPermissions') }}</a>
              </div>
              <div class="permission-list">
                <div v-for="perm in feishuRequiredPermissions" :key="perm.scope" class="permission-item">
                  <code class="permission-scope">{{ perm.scope }}</code>
                  <span class="permission-desc">{{ perm.desc }}</span>
                  <span class="permission-reason">{{ perm.reason }}</span>
                </div>
              </div>
            </div>

            <div v-else-if="form.channelType === 'web'" class="empty-config">
              <p class="empty-text">{{ t('channels.webHint') }}</p>
            </div>
            <div v-else-if="form.channelType === 'webchat'" class="empty-config">
              <p class="empty-text">{{ t('channels.webchatHint') }}</p>
            </div>
            <div v-else-if="form.channelType === 'webhook'" class="empty-config">
              <p class="empty-text">{{ t('channels.webhookHint') }}</p>
            </div>

            <!-- 高级配置 -->
            <div class="advanced-section">
              <button class="advanced-toggle" @click="showAdvanced = !showAdvanced">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                  :style="{ transform: showAdvanced ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.2s' }">
                  <polyline points="9 18 15 12 9 6"/>
                </svg>
                {{ t('channels.advanced') }}
              </button>
              <div v-if="showAdvanced" class="advanced-body">
                <div class="form-group full-width section-divider">
                  <label class="section-label">{{ t('channels.accessControl.title') }}</label>
                </div>
                <div class="form-grid">
                  <div class="form-group">
                    <label class="form-label">
                      {{ t('channels.accessControl.dmPolicy') }}
                      <span class="tooltip-icon" :title="t('channels.accessControl.dmPolicyTooltip')">?</span>
                    </label>
                    <select v-model="accessControl.dm_policy" class="form-input">
                      <option value="open">{{ t('channels.accessControl.policyOpen') }}</option>
                      <option value="closed">{{ t('channels.accessControl.policyClosed') }}</option>
                    </select>
                  </div>
                  <div class="form-group">
                    <label class="form-label">
                      {{ t('channels.accessControl.groupPolicy') }}
                      <span class="tooltip-icon" :title="t('channels.accessControl.groupPolicyTooltip')">?</span>
                    </label>
                    <select v-model="accessControl.group_policy" class="form-input">
                      <option value="open">{{ t('channels.accessControl.policyOpen') }}</option>
                      <option value="closed">{{ t('channels.accessControl.policyClosed') }}</option>
                    </select>
                  </div>
                  <div class="form-group full-width">
                    <label class="form-label">
                      {{ t('channels.accessControl.allowFrom') }}
                      <span class="tooltip-icon" :title="t('channels.accessControl.allowFromTooltip')">?</span>
                    </label>
                    <input v-model="accessControl.allow_from" class="form-input" :placeholder="t('channels.accessControl.allowFromPlaceholder')" />
                  </div>
                  <div class="form-group">
                    <label class="form-label">{{ t('channels.accessControl.denyMessage') }}</label>
                    <input v-model="accessControl.deny_message" class="form-input" :placeholder="t('channels.accessControl.denyMessagePlaceholder')" />
                  </div>
                  <div class="form-group">
                    <label class="form-label">
                      {{ t('channels.accessControl.requireMention') }}
                      <span class="tooltip-icon" :title="t('channels.accessControl.requireMentionTooltip')">?</span>
                    </label>
                    <select v-model="accessControl.require_mention" class="form-input">
                      <option :value="true">{{ t('common.yes') }}</option>
                      <option :value="false">{{ t('common.no') }}</option>
                    </select>
                  </div>
                </div>

                <div class="form-group full-width section-divider">
                  <label class="section-label">{{ t('channels.messageFilter.title') }}</label>
                </div>
                <div class="form-grid">
                  <div class="form-group">
                    <label class="form-label">
                      {{ t('channels.messageFilter.filterThinking') }}
                      <span class="tooltip-icon" :title="t('channels.messageFilter.filterThinkingTooltip')">?</span>
                    </label>
                    <select v-model="renderConfig.filter_thinking" class="form-input">
                      <option :value="true">{{ t('common.yes') }}</option>
                      <option :value="false">{{ t('common.no') }}</option>
                    </select>
                  </div>
                  <div class="form-group">
                    <label class="form-label">
                      {{ t('channels.messageFilter.filterToolMessages') }}
                      <span class="tooltip-icon" :title="t('channels.messageFilter.filterToolMessagesTooltip')">?</span>
                    </label>
                    <select v-model="renderConfig.filter_tool_messages" class="form-input">
                      <option :value="true">{{ t('common.yes') }}</option>
                      <option :value="false">{{ t('common.no') }}</option>
                    </select>
                  </div>
                  <div class="form-group">
                    <label class="form-label">{{ t('channels.messageFilter.messageFormat') }}</label>
                    <select v-model="renderConfig.message_format" class="form-input">
                      <option value="auto">{{ t('channels.messageFilter.formatAuto') }}</option>
                      <option value="markdown">{{ t('channels.messageFilter.formatMarkdown') }}</option>
                      <option value="text">{{ t('channels.messageFilter.formatText') }}</option>
                      <option value="html">{{ t('channels.messageFilter.formatHtml') }}</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- JSON Tab -->
          <div v-if="configTab === 'json'" class="tab-content">
            <p class="json-hint">{{ t('channels.jsonHint') }}</p>
            <textarea
              v-model="rawConfigJson"
              class="form-textarea json-editor"
              rows="14"
              placeholder='{"client_id": "...", "client_secret": "..."}'
              spellcheck="false"
            ></textarea>
          </div>
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn-secondary" @click="close">{{ t('common.cancel') }}</button>
        <button class="btn-primary" @click="save" :disabled="!form.name">{{ t('common.save') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { CHANNEL_FIELD_DEFS } from '@/types'
import { copyToClipboard } from '@/utils/clipboard'
import type { Agent, Channel, ChannelFieldDef } from '@/types'
import {
  buildConfigJson,
  defaultAccessControl,
  defaultRenderConfig,
  extractAccessControl,
  extractChannelFields,
  extractRenderConfig,
  parseConfigJson,
  type AccessControlValue,
  type RenderConfigValue,
} from '@/utils/channelConfigJson'
import { useWeixinQrcodePoll } from '@/composables/channels/useWeixinQrcodePoll'
import { useWecomBotAuth } from '@/composables/channels/useWecomBotAuth'
import { useFeishuAppRegister } from '@/composables/channels/useFeishuAppRegister'
import { useDingTalkAppRegister } from '@/composables/channels/useDingTalkAppRegister'
import { useQqAppRegister } from '@/composables/channels/useQqAppRegister'
import AgentPickerDialog from '@/components/common/AgentPickerDialog.vue'

interface Props {
  modelValue: boolean
  editingChannel: Channel | null
  agents: Agent[]
  /** When opening in create mode, optionally preset the channel type. */
  defaultType?: string
  /** When opening in create mode, optionally preset the name. */
  defaultName?: string
}

const props = withDefaults(defineProps<Props>(), {
  defaultType: 'web',
  defaultName: '',
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  save: [payload: Partial<Channel>]
  /**
   * User clicked "Add new WeChat account" while editing an existing one.
   * Parent should close this modal and reopen in create mode with weixin
   * pre-selected. Parent owns the existing-channel count needed for naming.
   */
  'add-new-weixin': []
}>()

const { t } = useI18n()

// ========== Form state (owned by modal; parent only opens/closes) ==========

const defaultForm = (): Partial<Channel> => ({
  name: props.defaultName,
  channelType: props.defaultType,
  description: '',
  configJson: '',
  agentId: null as any,
  enabled: true,
})

const form = ref<Partial<Channel>>(defaultForm())
const channelConfig = ref<Record<string, any>>({})
const visibleFields = ref<Record<string, boolean>>({})
const configTab = ref<'form' | 'json'>('form')
const rawConfigJson = ref('')
const showAdvanced = ref(false)
const accessControl = ref<AccessControlValue>(defaultAccessControl())
const renderConfig = ref<RenderConfigValue>(defaultRenderConfig())

// ========== Auth composables ==========

const weixin = useWeixinQrcodePoll(({ botToken, baseUrl }) => {
  channelConfig.value.bot_token = botToken
  if (baseUrl) channelConfig.value.base_url = baseUrl
  // Auto-suffix the channel name with the last 6 chars of the token in create
  // mode, so multiple weixin accounts are visually distinguishable in the list.
  if (!props.editingChannel) {
    const suffix = botToken.slice(-6)
    const cur = form.value.name || ''
    if (!cur || cur === t('channels.weixin.newAccountName') || cur === t('channels.types.weixin')) {
      form.value.name = t('channels.weixin.newAccountName') + ' (' + suffix + ')'
    }
  }
  form.value.enabled = true
})

const wecom = useWecomBotAuth((bot) => {
  channelConfig.value.bot_id = bot.botid
  channelConfig.value.secret = bot.secret
  form.value.enabled = true
})

// Feishu one-click app registration: scan-to-create flow that returns
// app_id/app_secret without the user ever touching the developer console.
const feishuRegister = useFeishuAppRegister(({ appId, appSecret }) => {
  channelConfig.value.app_id = appId
  channelConfig.value.app_secret = appSecret
  // Auto-enable on confirmed scan: skipping this trapped users into thinking
  // the channel was ready to go after scanning, when in fact the toggle was
  // still off and the adapter never started.
  form.value.enabled = true
})

// DingTalk one-click app registration via Device Flow — same UX shape as
// feishu's flow, returns client_id/client_secret instead.
const dingtalkRegister = useDingTalkAppRegister(({ clientId, clientSecret }) => {
  channelConfig.value.client_id = clientId
  channelConfig.value.client_secret = clientSecret
  form.value.enabled = true
})

// QQ Bot scan-to-bind via the Lite portal — fills app_id/client_secret.
// The user must have created the bot on q.qq.com beforehand; this flow only
// skips the manual copy-paste of credentials.
const qqRegister = useQqAppRegister(({ appId, clientSecret }) => {
  channelConfig.value.app_id = appId
  channelConfig.value.client_secret = clientSecret
  form.value.enabled = true
})

// ========== Field defs (derived) ==========

const currentFieldDefs = computed<ChannelFieldDef[]>(() => {
  const all = CHANNEL_FIELD_DEFS[form.value.channelType || ''] || []
  return all.filter((field) => {
    if (!field.showIf) return true
    return channelConfig.value[field.showIf.field] === field.showIf.value
  })
})

// ========== Webhook guide ==========

const WEBHOOK_GUIDES = computed<Record<string, { steps: string[] }>>(() => ({
  dingtalk: { steps: [t('channels.guide.dingtalk.step1'), t('channels.guide.dingtalk.step2'), t('channels.guide.dingtalk.step3')] },
  telegram: { steps: [t('channels.guide.telegram.step1'), t('channels.guide.telegram.step2'), t('channels.guide.telegram.step3'), t('channels.guide.telegram.step4')] },
  discord: { steps: [t('channels.guide.discord.step1'), t('channels.guide.discord.step2'), t('channels.guide.discord.step3'), t('channels.guide.discord.step4'), t('channels.guide.discord.step5')] },
  wecom: { steps: [t('channels.guide.wecom.step1'), t('channels.guide.wecom.step2'), t('channels.guide.wecom.step3'), t('channels.guide.wecom.step4')] },
  weixin: { steps: [t('channels.guide.weixin.step1'), t('channels.guide.weixin.step2'), t('channels.guide.weixin.step3'), t('channels.guide.weixin.step4')] },
  qq: { steps: [t('channels.guide.qq.step1'), t('channels.guide.qq.step2'), t('channels.guide.qq.step3'), t('channels.guide.qq.step4')] },
}))

// Feishu's guide is mode-aware: showing both webhook and websocket setup
// instructions side-by-side (the original behavior) was confusing — the user
// only ever uses one mode, so we filter the mode-specific step accordingly.
// Webhook + websocket steps live as separate i18n keys; we assemble at render time.
const webhookGuide = computed(() => {
  const type = form.value.channelType
  if (!type) return null
  if (type === 'feishu') {
    const mode = channelConfig.value.connection_mode === 'webhook' ? 'webhook' : 'websocket'
    return {
      steps: [
        t('channels.guide.feishu.step1'),
        t('channels.guide.feishu.step2'),
        t(`channels.guide.feishu.${mode}Step`),
        t('channels.guide.feishu.permissionStep'),
      ],
    }
  }
  return WEBHOOK_GUIDES.value[type] || null
})

const needsWebhookUrl = computed(() => {
  const type = form.value.channelType
  if (type === 'wecom' || type === 'qq' || type === 'weixin') return false
  if (type === 'discord') return false
  if (type === 'dingtalk' && channelConfig.value.connection_mode !== 'webhook') return false
  if (type === 'feishu' && channelConfig.value.connection_mode === 'websocket') return false
  if (type === 'telegram' && channelConfig.value.connection_mode !== 'webhook') return false
  return true
})

const isLocalhost = computed(() => {
  const host = window.location.hostname
  return host === 'localhost' || host === '127.0.0.1' || host === '0.0.0.0'
})

const webhookUrl = computed(() => {
  const proto = window.location.protocol
  const host = window.location.hostname
  const port = window.location.port
  const portSuffix = port ? `:${port}` : ''
  return `${proto}//${host}${portSuffix}/api/v1/channels/webhook/${form.value.channelType}`
})

const feishuPermissionUrl = computed(() => {
  const appId = channelConfig.value?.app_id || ''
  const domain = channelConfig.value?.domain === 'lark' ? 'open.larksuite.com' : 'open.feishu.cn'
  return appId ? `https://${domain}/app/${appId}/permission` : `https://${domain}/`
})

const feishuRequiredPermissions = computed(() => {
  const perms: { scope: string; desc: string; reason: string }[] = [
    { scope: 'im:message', desc: t('channels.feishu.perm.message'), reason: t('channels.feishu.perm.messageReason') },
    { scope: 'im:message.receive_v1', desc: t('channels.feishu.perm.receive'), reason: t('channels.feishu.perm.receiveReason') },
  ]
  if (channelConfig.value?.connection_mode === 'websocket') {
    perms.push({ scope: 'im:resource', desc: t('channels.feishu.perm.resource'), reason: t('channels.feishu.perm.resourceReason') })
  }
  if (channelConfig.value?.enable_reaction !== false) {
    perms.push({ scope: 'im:message.reactions', desc: t('channels.feishu.perm.reactions'), reason: t('channels.feishu.perm.reactionsReason') })
  }
  if (channelConfig.value?.enable_nickname_cache !== false) {
    perms.push({ scope: 'contact:user.base:readonly', desc: t('channels.feishu.perm.contact'), reason: t('channels.feishu.perm.contactReason') })
  }
  if (channelConfig.value?.media_download_enabled) {
    perms.push({ scope: 'im:message.resource', desc: t('channels.feishu.perm.media'), reason: t('channels.feishu.perm.mediaReason') })
  }
  return perms
})

// ========== Copy webhook URL ==========

const copyLabel = ref(t('channels.webhook.copy'))

async function copyWebhookUrl() {
  try {
    await copyToClipboard(webhookUrl.value)
    copyLabel.value = t('channels.webhook.copied')
    setTimeout(() => { copyLabel.value = t('channels.webhook.copy') }, 2000)
  } catch {
    mcToast.warning(t('channels.webhook.copyFailed'))
  }
}

async function copyText(text: string) {
  try {
    await copyToClipboard(text)
    mcToast.success(t('common.copied'))
  } catch {
    mcToast.warning(t('channels.webhook.copyFailed'))
  }
}

// ========== Initialization (the modal is v-if'd, so each open is a fresh mount) ==========

function initFromEditing(channel: Channel) {
  form.value = { ...channel }
  configTab.value = 'form'
  showAdvanced.value = false
  visibleFields.value = {}
  const cfg = parseConfigJson(channel.configJson)
  channelConfig.value = extractChannelFields(cfg, channel.channelType)
  accessControl.value = extractAccessControl(cfg)
  renderConfig.value = extractRenderConfig(cfg)
  rawConfigJson.value = channel.configJson || ''
}

function initForCreate() {
  form.value = defaultForm()
  channelConfig.value = {}
  visibleFields.value = {}
  accessControl.value = defaultAccessControl()
  renderConfig.value = defaultRenderConfig()
  rawConfigJson.value = ''
  configTab.value = 'form'
  showAdvanced.value = false
  initDefaultFieldValues()
  // Pre-fill description with the i18n type-level fallback so the new
  // channel card never lands on the list page with an empty middle area.
  // The user can keep, edit, or clear it before saving.
  applyTypeDescriptionFallback(true)
}

/**
 * Set {@code form.description} to the type-level i18n fallback when
 * appropriate. Called on mount and whenever {@code channelType} changes
 * (create flow only — never overrides an editing row).
 *
 * @param force when {@code true}, overwrite an empty / auto-filled
 *              description regardless. We treat ANY description that
 *              matches one of the known type fallbacks as "auto", so
 *              switching from dingtalk → wecom in the wizard updates
 *              the placeholder cleanly. A description the user typed
 *              themselves never matches and is preserved.
 */
function applyTypeDescriptionFallback(force = false) {
  if (props.editingChannel) return  // never touch user data on edit
  const type = form.value.channelType
  if (!type) return
  const candidate = t(`channels.cardDesc.typeFallback.${type}` as any)
  // vue-i18n returns the key itself when missing → leave description alone.
  const haveTranslation = candidate && candidate !== `channels.cardDesc.typeFallback.${type}`
  if (!haveTranslation) return
  const current = (form.value.description || '').trim()
  if (force && !current) {
    form.value.description = candidate
    return
  }
  // Switching channel types — update only if the description is still one
  // of the auto-filled fallbacks (user hasn't typed their own).
  if (current && isAutoTypeFallback(current)) {
    form.value.description = candidate
  } else if (!current) {
    form.value.description = candidate
  }
}

/** True when the given string equals any of the known channel-type
 *  fallback i18n strings — used to detect "user hasn't customized this". */
function isAutoTypeFallback(text: string): boolean {
  const trimmed = text.trim()
  // Iterate the known channel types we ship i18n for. List mirrors
  // CHANNEL_TYPE_OPTIONS but we keep a local copy to avoid coupling.
  const known = ['web', 'dingtalk', 'wecom', 'weixin', 'feishu', 'telegram',
                 'slack', 'discord', 'qq', 'matrix', 'qqbot', 'yuanbao']
  for (const k of known) {
    const s = t(`channels.cardDesc.typeFallback.${k}` as any)
    if (s && s === trimmed) return true
  }
  return false
}

function initDefaultFieldValues() {
  const fields = CHANNEL_FIELD_DEFS[form.value.channelType || ''] || []
  for (const f of fields) {
    if (f.defaultValue !== undefined && channelConfig.value[f.key] === undefined) {
      channelConfig.value[f.key] = f.defaultValue
    }
  }
}

onMounted(() => {
  if (props.editingChannel) initFromEditing(props.editingChannel)
  else initForCreate()
})

// ========== Form actions ==========

function onChannelTypeChange() {
  channelConfig.value = {}
  visibleFields.value = {}
  weixin.reset()
  initDefaultFieldValues()
  // Refresh the auto-filled description if the user hasn't customized it.
  applyTypeDescriptionFallback(false)
}

function switchTab(tab: 'form' | 'json') {
  if (tab === 'json' && configTab.value === 'form') {
    rawConfigJson.value = buildConfigJson({
      channelType: form.value.channelType || '',
      channelConfig: channelConfig.value,
      accessControl: accessControl.value,
      renderConfig: renderConfig.value,
    })
  } else if (tab === 'form' && configTab.value === 'json') {
    const cfg = parseConfigJson(rawConfigJson.value)
    channelConfig.value = extractChannelFields(cfg, form.value.channelType || '')
    accessControl.value = extractAccessControl(cfg)
    renderConfig.value = extractRenderConfig(cfg)
  }
  configTab.value = tab
}

function close() {
  emit('update:modelValue', false)
}

function save() {
  const payload: Partial<Channel> = { ...form.value }

  if (configTab.value === 'json') {
    if (rawConfigJson.value.trim()) {
      try { JSON.parse(rawConfigJson.value) }
      catch {
        mcToast.error(t('channels.messages.invalidJson'))
        return
      }
    }
    payload.configJson = rawConfigJson.value
  } else {
    payload.configJson = buildConfigJson({
      channelType: form.value.channelType || '',
      channelConfig: channelConfig.value,
      accessControl: accessControl.value,
      renderConfig: renderConfig.value,
    })
  }

  emit('save', payload)
}
</script>

<style scoped>
.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border-radius: 16px; width: 100%; max-width: 620px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; border-top: 1px solid var(--mc-border-light); }

/* Form */
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-primary); display: flex; align-items: center; gap: 4px; }
.required { color: var(--mc-danger, #ef4444); font-size: 13px; }
.form-input, .form-textarea { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-elevated); width: 100%; box-sizing: border-box; }
.form-input:focus, .form-textarea:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.form-textarea { resize: vertical; font-family: monospace; }

.tooltip-icon { display: inline-flex; align-items: center; justify-content: center; width: 15px; height: 15px; border-radius: 50%; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); font-size: 10px; font-weight: 700; cursor: help; flex-shrink: 0; }

/* Tabs */
.config-section { margin-top: 20px; }
.tab-bar { display: flex; gap: 0; border-bottom: 1px solid var(--mc-border-light); margin-bottom: 16px; }
.tab-btn { padding: 8px 16px; background: none; border: none; border-bottom: 2px solid transparent; font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; }
.tab-btn:hover { color: var(--mc-text-primary); }
.tab-btn.active { color: var(--mc-primary); border-bottom-color: var(--mc-primary); }
.tab-content { min-height: 60px; }

/* Webhook guide */
.guide-card { background: var(--mc-primary-bg, rgba(217,119,87,0.06)); border: 1px solid var(--mc-primary-light, rgba(217,119,87,0.2)); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.guide-webhook-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.guide-label { font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.5px; flex-shrink: 0; }
.guide-url { font-size: 12px; font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', monospace; background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 5px; padding: 3px 8px; color: var(--mc-text-primary); word-break: break-all; flex: 1; min-width: 0; }
.copy-btn { display: inline-flex; align-items: center; gap: 4px; padding: 3px 10px; background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 5px; font-size: 11px; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; white-space: nowrap; flex-shrink: 0; }
.copy-btn:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.guide-warn { display: flex; align-items: flex-start; gap: 6px; padding: 8px 10px; background: rgba(234, 179, 8, 0.08); border: 1px solid rgba(234, 179, 8, 0.2); border-radius: 6px; font-size: 12px; color: var(--mc-text-secondary); margin-bottom: 8px; line-height: 1.5; }
.guide-warn svg { flex-shrink: 0; color: #eab308; margin-top: 1px; }
.guide-steps { margin: 0; padding-left: 20px; font-size: 13px; color: var(--mc-text-secondary); line-height: 1.7; }
.guide-steps li { margin-bottom: 2px; }
.guide-steps :deep(a) { color: var(--mc-primary); text-decoration: none; }
.guide-steps :deep(a:hover) { text-decoration: underline; }
.guide-steps :deep(code) { font-size: 12px; background: var(--mc-bg-sunken); padding: 1px 5px; border-radius: 3px; }
.guide-steps :deep(b) { color: var(--mc-text-primary); font-weight: 600; }

/* DingTalk one-click register */
.dingtalk-register-card { background: linear-gradient(135deg, rgba(31,121,255,0.05), rgba(0,144,255,0.05)); border: 1px solid rgba(31,121,255,0.2); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.dingtalk-register-header { font-size: 13px; color: var(--mc-text-primary); margin-bottom: 6px; }
.dingtalk-register-hint { font-size: 12px; color: var(--mc-text-secondary); margin: 0 0 10px 0; line-height: 1.6; }
.dingtalk-register-btn { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; padding: 10px 16px; background: #1f79ff; color: #fff; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.2s; }
.dingtalk-register-btn:hover:not(:disabled) { background: #1668e3; transform: translateY(-1px); box-shadow: 0 2px 8px rgba(31,121,255,0.3); }
.dingtalk-register-btn:active:not(:disabled) { transform: translateY(0); }
.dingtalk-register-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.dingtalk-register-qrcode { display: flex; flex-direction: column; align-items: center; margin-top: 16px; padding: 16px; background: #fff; border-radius: 8px; border: 1px solid var(--mc-border); }
.dingtalk-register-qrcode-img { width: 200px; height: 200px; border-radius: 4px; }
.dingtalk-register-qrcode--loading { min-height: 240px; justify-content: center; }
.dingtalk-register-qrcode-spinner { width: 40px; height: 40px; border: 3px solid rgba(31,121,255,0.2); border-top-color: #1f79ff; border-radius: 50%; animation: dingtalk-register-spin 0.8s linear infinite; }
@keyframes dingtalk-register-spin { to { transform: rotate(360deg); } }
.dingtalk-register-status { font-size: 13px; color: var(--mc-text-secondary); margin-top: 10px; transition: color 0.2s; text-align: center; }
.dingtalk-register-status.confirmed { color: #10b981; font-weight: 500; }
.dingtalk-register-status.expired { color: #f56c6c; }
.dingtalk-register-status.denied { color: #f56c6c; }

/* QQ scan-to-bind (QQ Open Platform Lite portal) */
.qq-register-card { background: linear-gradient(135deg, rgba(20,134,255,0.05), rgba(96,165,250,0.05)); border: 1px solid rgba(20,134,255,0.2); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.qq-register-header { font-size: 13px; color: var(--mc-text-primary); margin-bottom: 6px; }
.qq-register-hint { font-size: 12px; color: var(--mc-text-secondary); margin: 0 0 10px 0; line-height: 1.6; }
.qq-register-btn { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; padding: 10px 16px; background: #1486ff; color: #fff; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.2s; }
.qq-register-btn:hover:not(:disabled) { background: #0d6fd9; transform: translateY(-1px); box-shadow: 0 2px 8px rgba(20,134,255,0.3); }
.qq-register-btn:active:not(:disabled) { transform: translateY(0); }
.qq-register-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.qq-register-qrcode { display: flex; flex-direction: column; align-items: center; margin-top: 16px; padding: 16px; background: #fff; border-radius: 8px; border: 1px solid var(--mc-border); }
.qq-register-qrcode-img { width: 200px; height: 200px; border-radius: 4px; }
.qq-register-qrcode--loading { min-height: 240px; justify-content: center; }
.qq-register-qrcode-spinner { width: 40px; height: 40px; border: 3px solid rgba(20,134,255,0.2); border-top-color: #1486ff; border-radius: 50%; animation: qq-register-spin 0.8s linear infinite; }
@keyframes qq-register-spin { to { transform: rotate(360deg); } }
.qq-register-status { font-size: 13px; color: var(--mc-text-secondary); margin-top: 10px; transition: color 0.2s; text-align: center; }
.qq-register-status.confirmed { color: #10b981; font-weight: 500; }
.qq-register-status.expired { color: #f56c6c; }
.qq-register-status.denied { color: #f56c6c; }

/* Feishu one-click register */
.feishu-register-card { background: linear-gradient(135deg, rgba(0,128,255,0.05), rgba(99,102,241,0.05)); border: 1px solid rgba(99,102,241,0.2); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.feishu-register-header { font-size: 13px; color: var(--mc-text-primary); margin-bottom: 6px; }
.feishu-register-hint { font-size: 12px; color: var(--mc-text-secondary); margin: 0 0 10px 0; line-height: 1.6; }
.feishu-register-btn { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; padding: 10px 16px; background: #2563eb; color: #fff; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.2s; }
.feishu-register-btn:hover:not(:disabled) { background: #1d4ed8; transform: translateY(-1px); box-shadow: 0 2px 8px rgba(37,99,235,0.3); }
.feishu-register-btn:active:not(:disabled) { transform: translateY(0); }
.feishu-register-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.feishu-register-qrcode { display: flex; flex-direction: column; align-items: center; margin-top: 16px; padding: 16px; background: #fff; border-radius: 8px; border: 1px solid var(--mc-border); }
.feishu-register-qrcode-img { width: 200px; height: 200px; border-radius: 4px; }
.feishu-register-qrcode--loading { min-height: 240px; justify-content: center; }
.feishu-register-qrcode-spinner { width: 40px; height: 40px; border: 3px solid rgba(99,102,241,0.2); border-top-color: #6366f1; border-radius: 50%; animation: feishu-register-spin 0.8s linear infinite; }
@keyframes feishu-register-spin { to { transform: rotate(360deg); } }
.feishu-register-status { font-size: 13px; color: var(--mc-text-secondary); margin-top: 10px; transition: color 0.2s; text-align: center; }
.feishu-register-status.confirmed { color: #10b981; font-weight: 500; }
.feishu-register-status.expired { color: #f56c6c; }
.feishu-register-status.denied { color: #f56c6c; }
.feishu-register-status.error { color: #f56c6c; }

/* WeCom auth */
.wecom-auth-card { background: var(--mc-primary-bg, rgba(217,119,87,0.06)); border: 1px solid var(--mc-primary-light, rgba(217,119,87,0.2)); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.wecom-auth-hint { font-size: 13px; color: var(--mc-text-secondary); margin: 0 0 10px 0; line-height: 1.6; }
.wecom-auth-btn { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; padding: 10px 16px; background: var(--mc-primary, #D97757); color: #fff; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.2s; }
.wecom-auth-btn:hover:not(:disabled) { background: var(--mc-primary-hover, #C1572B); transform: translateY(-1px); box-shadow: 0 2px 8px rgba(217,119,87,0.3); }
.wecom-auth-btn:active:not(:disabled) { transform: translateY(0); }
.wecom-auth-btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* Weixin QR */
.weixin-auth-card { background: var(--mc-primary-bg, rgba(217,119,87,0.06)); border: 1px solid var(--mc-primary-light, rgba(217,119,87,0.2)); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.weixin-auth-hint { font-size: 13px; color: var(--mc-text-secondary); margin: 0 0 10px 0; line-height: 1.6; }
.weixin-auth-btn { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; padding: 10px 16px; background: #07C160; color: #fff; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.2s; }
.weixin-auth-btn:hover:not(:disabled) { background: #06AD56; transform: translateY(-1px); box-shadow: 0 2px 8px rgba(7,193,96,0.3); }
.weixin-auth-btn:active:not(:disabled) { transform: translateY(0); }
.weixin-auth-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.weixin-multi-account-hint { margin-bottom: 12px; padding: 12px; background: #FFF7E6; border: 1px solid #FFD666; border-radius: 8px; }
.weixin-multi-account-hint .hint-warning { font-size: 13px; color: #D48806; margin: 0 0 10px 0; line-height: 1.5; }
.weixin-add-account-btn { display: flex; align-items: center; justify-content: center; gap: 6px; width: 100%; padding: 8px 14px; background: var(--mc-primary); color: #fff; border: none; border-radius: 6px; font-size: 13px; font-weight: 500; cursor: pointer; transition: all 0.2s; }
.weixin-add-account-btn:hover { opacity: 0.9; transform: translateY(-1px); }
.weixin-qrcode-area { display: flex; flex-direction: column; align-items: center; margin-top: 16px; padding: 16px; background: #fff; border-radius: 8px; border: 1px solid var(--mc-border); }
.weixin-qrcode-img { width: 200px; height: 200px; border-radius: 4px; }
.weixin-scan-hint { font-size: 13px; color: var(--mc-text-secondary); margin-top: 10px; transition: color 0.2s; }
.weixin-scan-hint.scanned { color: #E6A23C; }
.weixin-scan-hint.confirmed { color: #07C160; font-weight: 500; }
.weixin-scan-hint.expired { color: #F56C6C; }

/* Feishu permissions */
.permission-hints { background: var(--mc-primary-bg, rgba(217,119,87,0.06)); border: 1px solid var(--mc-primary-light, rgba(217,119,87,0.15)); border-radius: 10px; padding: 12px 16px; margin-top: 12px; margin-bottom: 8px; }
.permission-header { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 10px; }
.permission-header svg { color: var(--mc-primary); flex-shrink: 0; }
.permission-link { margin-left: auto; font-size: 12px; font-weight: 500; color: var(--mc-primary); text-decoration: none; white-space: nowrap; }
.permission-link:hover { text-decoration: underline; }
.permission-list { display: flex; flex-direction: column; gap: 6px; }
.permission-item { display: flex; align-items: baseline; gap: 8px; font-size: 12px; line-height: 1.5; }
.permission-scope { font-size: 11px; font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', monospace; background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 4px; padding: 1px 6px; color: var(--mc-primary); white-space: nowrap; flex-shrink: 0; }
.permission-desc { color: var(--mc-text-primary); flex-shrink: 0; }
.permission-reason { color: var(--mc-text-tertiary, var(--mc-text-secondary)); font-size: 11px; opacity: 0.7; }

/* Password / inline icons */
.password-wrap { position: relative; display: flex; align-items: center; }
.password-wrap .form-input { padding-right: 36px; }
.eye-btn { position: absolute; right: 8px; background: none; border: none; cursor: pointer; color: var(--mc-text-tertiary); padding: 2px; display: flex; align-items: center; }
.eye-btn:hover { color: var(--mc-text-primary); }
.copy-inline-btn { position: absolute; right: 8px; background: none; border: none; cursor: pointer; color: var(--mc-text-tertiary); padding: 2px; display: flex; align-items: center; }
.copy-inline-btn:hover { color: var(--mc-text-primary); }
.form-hint { font-size: 12px; color: var(--mc-text-tertiary); line-height: 1.5; }

/* Switch */
.switch-wrap { display: flex; align-items: center; gap: 8px; height: 36px; }
.switch { position: relative; display: inline-block; width: 36px; height: 20px; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch-slider { position: absolute; cursor: pointer; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.switch-slider::before { content: ""; position: absolute; height: 14px; width: 14px; left: 3px; bottom: 3px; background: white; border-radius: 50%; transition: 0.2s; }
.switch input:checked + .switch-slider { background: var(--mc-primary); }
.switch input:checked + .switch-slider::before { transform: translateX(16px); }
.switch-label { font-size: 13px; color: var(--mc-text-secondary); }

.empty-config { padding: 24px 16px; text-align: center; }
.empty-text { font-size: 13px; color: var(--mc-text-tertiary); margin: 0; }

/* Advanced */
.advanced-section { margin-top: 20px; border-top: 1px solid var(--mc-border-light); padding-top: 12px; }
.advanced-toggle { display: flex; align-items: center; gap: 6px; background: none; border: none; cursor: pointer; font-size: 13px; font-weight: 600; color: var(--mc-text-secondary); padding: 4px 0; }
.advanced-toggle:hover { color: var(--mc-text-primary); }
.advanced-body { margin-top: 12px; }
.section-divider { margin-top: 8px; padding-top: 12px; border-top: 1px solid var(--mc-border-light); }
.section-label { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }

.json-hint { font-size: 12px; color: var(--mc-text-tertiary); margin: 0 0 8px; }
.json-editor { font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', monospace; font-size: 13px; line-height: 1.5; tab-size: 2; }

.fade-enter-active, .fade-leave-active { transition: opacity 0.15s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
