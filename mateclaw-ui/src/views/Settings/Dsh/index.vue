<template>
  <div class="settings-section dsh-page">
    <div class="section-header">
      <div>
        <div class="mc-page-kicker">运行时管理</div>
        <h2 class="section-title">DeepSeek Harness</h2>
        <p class="section-desc">在页面完成安装、配置、检测和启用，不再依赖 IDEA 的环境变量。</p>
      </div>
      <span class="state-pill" :class="`state-${state.toLowerCase()}`">{{ stateLabel }}</span>
    </div>

    <div class="dsh-steps mc-surface-card">
      <div v-for="step in steps" :key="step.id" class="dsh-step" :class="{ done: step.done, active: step.active }">
        <span class="step-index">{{ step.done ? '✓' : step.id }}</span>
        <div><strong>{{ step.title }}</strong><small>{{ step.description }}</small></div>
      </div>
    </div>

    <div v-if="error" class="settings-card error-card">{{ error }}</div>
    <div v-if="loading" class="settings-card loading-card">正在读取 DSH 运行时状态...</div>

    <template v-else>
      <div class="settings-card">
        <div class="card-heading"><div><h3>运行时配置</h3><p>托管配置优先于 application.yml 和旧环境变量。</p></div><span v-if="status.config?.apiKeyConfigured" class="configured-badge">API Key 已配置</span></div>
        <div class="form-grid">
          <label><span>可执行文件</span><input v-model="form.executable_path" placeholder="/path/to/dsh-jsonrpc-agent" /></label>
          <label><span>Cordis 配置</span><input v-model="form.cordis_config_path" placeholder="/path/to/cordis.yml" /></label>
          <label><span>工作目录</span><input v-model="form.working_directory" placeholder="/path/to/workspace" /></label>
          <label><span>DeepSeek Base URL</span><input v-model="form.base_url" placeholder="https://api.deepseek.com" /></label>
          <label><span>模型</span><input v-model="form.model_name" placeholder="deepseek-v4-flash" /></label>
          <label><span>API Key</span><input v-model="form.api_key" type="password" autocomplete="new-password" placeholder="留空表示保持当前值" /></label>
        </div>
        <div class="actions"><button class="btn-primary" :disabled="busy" @click="save">保存配置</button><button class="btn-secondary" :disabled="busy" @click="verify">验证配置</button></div>
      </div>

      <div class="settings-card install-card">
        <div class="card-heading"><div><h3>安装与连接</h3><p>安装包由服务端私有制品清单提供，前端不能注入任意下载地址。</p></div></div>
        <div class="actions">
          <button class="btn-secondary" :disabled="busy || !status.artifactManifestConfigured" @click="install">安装 / 更新 DSH</button>
          <button class="btn-secondary" :disabled="busy || !status.installed" @click="testConnection">测试进程</button>
          <button v-if="status.enabled" class="btn-danger" :disabled="busy" @click="disable">停用</button>
          <button v-else class="btn-primary" :disabled="busy || !canEnable" @click="enable">启用 DSH</button>
        </div>
        <p v-if="!status.artifactManifestConfigured" class="muted-note">当前未配置私有制品清单。可以继续使用已有安装路径或旧环境变量；要启用页面安装，请设置 DSH_MANIFEST_URL。</p>
      </div>

      <div class="settings-card diagnostics-card">
        <div class="card-heading"><div><h3>检测结果</h3><p>敏感信息只显示是否已配置。</p></div></div>
        <dl><div><dt>状态</dt><dd>{{ stateLabel }}</dd></div><div><dt>可执行文件</dt><dd>{{ status.config?.executablePath || '未配置' }}</dd></div><div><dt>工作目录</dt><dd>{{ status.config?.workingDirectory || '未配置' }}</dd></div><div><dt>API Key</dt><dd>{{ status.config?.apiKeyConfigured ? '已配置' : '未配置' }}</dd></div></dl>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { dshApi } from '@/api'
import { mcToast } from '@/composables/useMcToast'

const loading = ref(true)
const busy = ref(false)
const error = ref('')
const status = reactive<any>({ state: 'NOT_INSTALLED', installed: false, enabled: false, config: {}, artifactManifestConfigured: false })
const form = reactive<Record<string, string>>({ executable_path: '', cordis_config_path: '', working_directory: '', base_url: '', model_name: '', api_key: '' })
const state = computed(() => String(status.state || 'NOT_INSTALLED'))
const stateLabel = computed(() => ({ NOT_INSTALLED: '未安装', INSTALLING: '安装中', INSTALLED_UNCONFIGURED: '已安装待验证', CONFIG_INVALID: '配置不完整', CHECKING: '检测中', CHECK_FAILED: '检测失败', READY: '已就绪', ENABLED: '已启用' } as Record<string, string>)[state.value] || state.value)
const canEnable = computed(() => status.installed && status.config?.workingDirectory && state.value !== 'CONFIG_INVALID')
const steps = computed(() => [
  { id: 1, title: '安装', description: status.installed ? '运行时已发现' : '从私有制品源安装', done: status.installed, active: !status.installed },
  { id: 2, title: '配置', description: status.config?.workingDirectory ? '连接参数已解析' : '填写运行目录，可复用已有 Provider Key', done: !!status.config?.workingDirectory && state.value !== 'CONFIG_INVALID', active: status.installed && state.value === 'CONFIG_INVALID' },
  { id: 3, title: '验证', description: canEnable.value ? '可进行进程测试' : '先完成前两步', done: canEnable.value, active: false },
  { id: 4, title: '启用', description: status.enabled ? 'DSH 已接管运行时' : '启用后新任务使用托管配置', done: status.enabled, active: canEnable.value && !status.enabled },
])

function applyResponse(response: any) {
  const data = response?.data ?? response
  Object.assign(status, data)
  const managed = data?.managed || {}
  for (const key of Object.keys(form)) form[key] = managed[key] || ''
  if (form.api_key.startsWith('****')) form.api_key = ''
}

async function load() { loading.value = true; error.value = ''; try { applyResponse(await dshApi.status()) } catch (e: any) { error.value = e?.message || '读取 DSH 状态失败' } finally { loading.value = false } }
async function run(action: () => Promise<any>, message: string) { busy.value = true; error.value = ''; try { applyResponse(await action()); mcToast.success(message) } catch (e: any) { error.value = e?.message || '操作失败'; mcToast.error(error.value) } finally { busy.value = false } }
function save() { return run(() => dshApi.saveConfig(form), 'DSH 配置已保存') }
function verify() { return run(dshApi.verify, 'DSH 配置验证完成') }
function install() { return run(dshApi.install, 'DSH 安装完成') }
function testConnection() { return run(async () => { const response: any = await dshApi.testConnection(); const data = response?.data ?? response; if (data?.success === false) throw new Error(data.message || 'DSH 进程测试失败'); return response }, 'DSH 进程测试通过') }
function enable() { return run(dshApi.enable, 'DSH 已启用') }
function disable() { return run(dshApi.disable, 'DSH 已停用') }
onMounted(load)
</script>

<style scoped>
.dsh-page { width: 100%; }
.section-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; margin-bottom: 18px; }
.section-title { margin: 3px 0 6px; font-size: 24px; color: var(--mc-text-primary); }
.section-desc, .card-heading p { margin: 0; color: var(--mc-text-secondary); font-size: 13px; line-height: 1.55; }
.state-pill, .configured-badge { display: inline-flex; border: 1px solid var(--mc-border); border-radius: 999px; padding: 6px 10px; color: var(--mc-text-secondary); font-size: 12px; white-space: nowrap; background: rgba(255,255,255,.35); }
.state-enabled, .configured-badge { color: var(--mc-success, #287a52); border-color: rgba(40,122,82,.25); }
.dsh-steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; padding: 14px; margin-bottom: 14px; }
.dsh-step { display: flex; align-items: center; gap: 9px; padding: 10px; color: var(--mc-text-tertiary); border-radius: 10px; }
.dsh-step.active { background: rgba(255,255,255,.42); color: var(--mc-text-primary); }
.dsh-step.done { color: var(--mc-success, #287a52); }
.step-index { display: grid; place-items: center; width: 24px; height: 24px; border: 1px solid currentColor; border-radius: 50%; font-size: 11px; flex: 0 0 auto; }
.dsh-step strong, .dsh-step small { display: block; }.dsh-step strong { font-size: 13px; }.dsh-step small { margin-top: 2px; font-size: 11px; opacity: .8; }
.settings-card { padding: 18px; margin-bottom: 14px; border: 1px solid var(--mc-border); border-radius: 14px; background: rgba(255,255,255,.25); box-shadow: 0 8px 24px rgba(124,63,30,.04); }
.card-heading { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; margin-bottom: 16px; }.card-heading h3 { margin: 0 0 4px; font-size: 16px; color: var(--mc-text-primary); }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }.form-grid label { display: flex; flex-direction: column; gap: 6px; min-width: 0; }.form-grid label span { color: var(--mc-text-secondary); font-size: 12px; }.form-grid input { width: 100%; min-height: 36px; padding: 8px 10px; border: 1px solid var(--mc-border); border-radius: 9px; background: rgba(255,255,255,.42); color: var(--mc-text-primary); outline: none; }.form-grid input:focus { border-color: var(--mc-primary); }
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; }.btn-primary, .btn-secondary, .btn-danger { border: 1px solid var(--mc-border); border-radius: 9px; padding: 8px 13px; font-size: 13px; cursor: pointer; }.btn-primary { color: #fff; background: var(--mc-primary); border-color: var(--mc-primary); }.btn-secondary { color: var(--mc-text-primary); background: rgba(255,255,255,.45); }.btn-danger { color: #a33b32; background: rgba(255,235,230,.65); border-color: rgba(163,59,50,.25); }.btn-primary:disabled, .btn-secondary:disabled, .btn-danger:disabled { opacity: .5; cursor: not-allowed; }.error-card { color: #a33b32; background: rgba(255,235,230,.62); }.loading-card { color: var(--mc-text-secondary); }.muted-note { margin: 12px 0 0; color: var(--mc-text-tertiary); font-size: 12px; line-height: 1.5; }
dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 0; }dt { color: var(--mc-text-tertiary); font-size: 12px; }dd { margin: 3px 0 0; color: var(--mc-text-primary); font-size: 13px; word-break: break-all; }
@media (max-width: 760px) { .dsh-steps, .form-grid, dl { grid-template-columns: 1fr; }.section-header { flex-direction: column; } }
</style>
