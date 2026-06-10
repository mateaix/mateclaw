<template>
  <div class="settings-section">
    <div class="section-header">
      <h2 class="section-title">{{ t('settings.proxyTitle') }}</h2>
      <p class="section-desc">{{ t('settings.proxyDesc') }}</p>
    </div>

    <div class="settings-card">
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.proxy.enableLabel') }}</div>
          <div class="setting-hint">{{ t('settings.proxy.enableHint') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="form.enabled" type="checkbox" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <div class="setting-item column">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.proxy.urlLabel') }}</div>
          <div class="setting-hint" v-html="t('settings.proxy.urlHint')"></div>
        </div>
        <input
          v-model.trim="form.url"
          class="form-input mono full"
          :disabled="!form.enabled"
          placeholder="http://127.0.0.1:7890"
        />
      </div>

      <div class="setting-item column">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.proxy.bypassLabel') }}</div>
          <div class="setting-hint">{{ t('settings.proxy.bypassHint') }}</div>
        </div>
        <input
          v-model.trim="form.nonProxyHosts"
          class="form-input mono full"
          :disabled="!form.enabled"
          :placeholder="defaultBypass"
        />
      </div>

      <!-- SOCKS caveat: java.net.http (LLM/streaming) ignores SOCKS proxies. -->
      <div v-if="isSocks" class="proxy-warn">{{ t('settings.proxy.socksWarn') }}</div>
    </div>

    <!-- Coverage transparency, replaces fake per-tool toggles: the single
         switch routes every JVM-level egress through the proxy. -->
    <div class="coverage-card">
      <div class="coverage-title">{{ t('settings.proxy.coverageTitle') }}</div>
      <div class="coverage-list">
        <span v-for="item in coverage" :key="item" class="coverage-chip">{{ item }}</span>
      </div>
    </div>

    <div class="save-bar">
      <button class="btn-secondary" :disabled="testing || !form.url" @click="onTest">
        {{ testing ? t('settings.proxy.testing') : t('settings.proxy.test') }}
      </button>
      <button class="btn-secondary" @click="load">{{ t('common.reset') }}</button>
      <button class="btn-primary" :disabled="saving" @click="onSave">{{ t('common.save') }}</button>
    </div>

    <div v-if="testResult" class="test-result" :class="{ ok: testResult.ok }">
      <span class="dot"></span>
      <template v-if="testResult.ok">{{ t('settings.proxy.testOk', { ms: testResult.latencyMs }) }}</template>
      <template v-else>{{ t('settings.proxy.testFail') }}: {{ testResult.error }}</template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { proxyApi } from '@/api'
import { mcToast } from '@/composables/useMcToast'

const { t } = useI18n()

const defaultBypass = 'localhost|127.*|10.*|192.168.*|*.local'

const form = reactive({
  enabled: false,
  url: '',
  nonProxyHosts: '',
})

const saving = ref(false)
const testing = ref(false)
const testResult = ref<{ ok: boolean; latencyMs: number; error?: string } | null>(null)

const isSocks = computed(() => /^socks/i.test(form.url.trim()))

const coverage = computed(() => [
  t('settings.proxy.coverage.llm'),
  t('settings.proxy.coverage.search'),
  t('settings.proxy.coverage.media'),
  t('settings.proxy.coverage.channels'),
  t('settings.proxy.coverage.mcp'),
  t('settings.proxy.coverage.browser'),
])

onMounted(load)

async function load() {
  testResult.value = null
  try {
    const res: any = await proxyApi.get()
    const d = res?.data || {}
    form.enabled = d.enabled ?? false
    form.url = d.url ?? ''
    form.nonProxyHosts = d.nonProxyHosts ?? ''
  } catch (e: any) {
    mcToast.error(e?.response?.data?.msg || t('settings.proxy.saveFail'))
  }
}

async function onSave() {
  saving.value = true
  try {
    await proxyApi.update({
      enabled: form.enabled,
      url: form.url,
      nonProxyHosts: form.nonProxyHosts,
    })
    await load()
    mcToast.success(t('settings.messages.saveSuccess'))
  } catch (e: any) {
    mcToast.error(e?.response?.data?.msg || t('settings.proxy.saveFail'))
  } finally {
    saving.value = false
  }
}

async function onTest() {
  testing.value = true
  testResult.value = null
  try {
    const res: any = await proxyApi.test(form.url)
    testResult.value = res?.data || { ok: false, latencyMs: 0, error: 'no response' }
  } catch (e: any) {
    testResult.value = { ok: false, latencyMs: 0, error: e?.message || 'request failed' }
  } finally {
    testing.value = false
  }
}
</script>

<style scoped>
.settings-section { width: 100%; }
.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.settings-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124,63,30,0.04); width: 100%; }
.setting-item { display: flex; justify-content: space-between; gap: 20px; padding: 16px 0; border-bottom: 1px solid var(--mc-border-light); }
.setting-item:last-child { border-bottom: none; }
.setting-item.column { flex-direction: column; gap: 12px; }
.setting-info { flex: 1; }
.setting-label { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 4px; }
.setting-hint { font-size: 13px; color: var(--mc-text-secondary); line-height: 1.6; }
.setting-hint :deep(code) { font-family: var(--mc-font-mono); background: var(--mc-inline-code-bg); color: var(--mc-inline-code-color); padding: 1px 6px; border-radius: 5px; font-size: 0.92em; }
.setting-control { width: 220px; display: flex; align-items: center; justify-content: flex-end; }

.form-input { border: 1px solid var(--mc-border); border-radius: 10px; padding: 10px 12px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); outline: none; transition: border-color .15s, box-shadow .15s; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 3px var(--mc-primary-bg); }
.form-input:disabled { opacity: 0.5; cursor: not-allowed; }
.form-input.full { width: 100%; }
.form-input.mono { font-family: var(--mc-font-mono); }

.proxy-warn {
  margin-top: 4px; padding: 10px 14px; border-radius: 10px;
  background: var(--mc-tool-call-bg); border: 1px solid var(--mc-tool-call-border);
  color: var(--mc-tool-call-color); font-size: 13px; line-height: 1.6;
}

.coverage-card { margin-top: 16px; background: var(--mc-bg-muted); border: 1px solid var(--mc-border-light); border-radius: 14px; padding: 16px 18px; }
.coverage-title { font-size: 13px; font-weight: 600; color: var(--mc-text-secondary); margin-bottom: 10px; }
.coverage-list { display: flex; flex-wrap: wrap; gap: 8px; }
.coverage-chip { font-size: 12px; padding: 4px 10px; border-radius: 999px; background: var(--mc-accent-soft); color: var(--mc-accent); font-weight: 500; }

.toggle-switch { position: relative; display: inline-flex; width: 44px; height: 24px; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; cursor: pointer; background: var(--mc-border); border-radius: 999px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 18px; height: 18px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(20px); }

.save-bar { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.btn-primary, .btn-secondary { border: none; border-radius: 10px; padding: 9px 16px; font-size: 14px; cursor: pointer; transition: all 0.15s; }
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled, .btn-secondary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-secondary { background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); }

.test-result { display: flex; align-items: center; gap: 8px; justify-content: flex-end; margin-top: 12px; font-size: 13px; color: var(--mc-danger); }
.test-result.ok { color: var(--mc-success); }
.test-result .dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
</style>
