<template>
  <div class="settings-section dsh-page">
    <div class="section-header">
      <div>
        <div class="mc-page-kicker">运行时管理</div>
        <h2 class="section-title">DeepSeek Harness</h2>
        <p class="section-desc">使用 SDK profile 接入。分别检查文件、SDK 握手与真实模型任务，按固定版本升级。</p>
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
          <label><span>可执行文件</span><input v-model="form.executable_path" placeholder="/absolute/path/to/dsh" /></label>
          <label><span>SDK Profile</span><input v-model="form.profile" readonly /></label>
          <label><span>独立数据根目录</span><input v-model="form.home_root" placeholder="留空使用托管目录" /></label>
          <label><span>可选 Patch 绝对路径（JSON 数组）</span><input v-model="form.patch_paths" placeholder='["/path/to/patch.yml"]' /></label>
          <label v-if="form.cordis_config_path"><span>旧 Cordis 配置（迁移时清空，不会自动转为 Patch）</span><input v-model="form.cordis_config_path" /></label>
          <label><span>工作目录</span><input v-model="form.working_directory" placeholder="/path/to/workspace" /></label>
          <label><span>DeepSeek Base URL</span><input v-model="form.base_url" placeholder="https://api.deepseek.com/anthropic" /></label>
          <label><span>模型</span><input v-model="form.model_name" placeholder="deepseek-v4-flash" /></label>
          <label><span>API Key</span><input v-model="form.api_key" type="password" autocomplete="new-password" placeholder="留空表示保持当前值" /></label>
        </div>
        <div class="actions"><button class="btn-primary" :disabled="busy || upgrading" @click="save">保存配置</button><button class="btn-secondary" :disabled="busy || upgrading" @click="verify">验证配置</button></div>
      </div>

      <div class="settings-card install-card">
        <div class="card-heading"><div><h3>版本升级与连接</h3><p>版本与依赖使用固定清单。升级会排空活动任务，在副本中验证后切换，并保留旧数据。</p></div></div>
        <label>目标版本 <select v-model="targetVersion" :disabled="busy || upgrading"><option v-for="release in status.catalog || []" :key="release.version" :value="release.version">{{ release.version }} · {{ release.version.includes('-') ? '预发布' : '正式版' }}</option></select></label>
        <p class="muted-note">当前版本：{{ status.activeVersion || '未知 / 外部安装' }}。候选版本需完成本机检测，不代表已通过所有平台验证。</p>
        <div class="actions">
          <button class="btn-secondary" :disabled="busy || upgrading || !targetVersion" @click="install">安装 / 升级所选版本</button>
          <button class="btn-secondary" :disabled="busy || upgrading || !status.installed" @click="testConnection">测试 SDK 握手</button>
          <button class="btn-secondary" :disabled="busy || upgrading || !status.installed" @click="testTask">测试真实模型</button>
          <button v-if="status.enabled" class="btn-danger" :disabled="busy || upgrading" @click="disable">停用</button>
          <button v-else class="btn-primary" :disabled="busy || !canEnable" @click="enable">启用 DSH</button>
        </div>
        <p class="muted-note">自动安装候选支持 Linux x64 / macOS arm64，旧版本支持 Node.js 22 / 24；0.2.2-rc.1 要求 Node.js 24.9+（24.x）和 npm。自定义地址必须支持 Messages API；官方根地址会转换为 /anthropic。真实模型测试和升级验证会调用已配置的模型。</p>
        <div v-if="operation.id" class="muted-note" role="status">
          <strong>{{ operation.state }}</strong> · {{ operation.message }}
          <p v-if="operation.homesChecked">已检查 {{ operation.homesChecked }} 个数据目录 · SDK {{ operation.handshakeStatus }} · 模型 {{ operation.taskStatus }}</p>
          <button v-if="operation.state === 'COMPLETED' && operation.previousGeneration" class="btn-secondary" :disabled="busy || upgrading" @click="rollback">回滚版本和数据</button>
        </div>
        <p class="muted-note">回滚会恢复旧 DSH 数据快照，保留新版本数据供核对；不会撤销工具写入工作目录的文件，也不会删除 MateClaw 聊天记录。</p>
        <p class="muted-note">DSH 自带工具由其 profile 管理，不提供 MateClaw 宿主审批；启用成员文件隔离时禁止运行。</p>
      </div>

      <div class="settings-card diagnostics-card">
        <div class="card-heading"><div><h3>检测结果</h3><p>敏感信息只显示是否已配置。</p></div></div>
        <dl><div><dt>文件检查</dt><dd>{{ checkLabel(status.fileCheck) }}</dd></div><div><dt>SDK 握手</dt><dd>{{ checkLabel(status.handshake) }}</dd></div><div><dt>模型任务</dt><dd>{{ checkLabel(status.taskCheck) }}</dd></div><div><dt>状态</dt><dd>{{ stateLabel }}</dd></div><div><dt>可执行文件</dt><dd>{{ status.config?.executablePath || '未配置' }}</dd></div><div><dt>工作目录</dt><dd>{{ status.config?.workingDirectory || '未配置' }}</dd></div><div><dt>API Key</dt><dd>{{ status.config?.apiKeyConfigured ? '已配置' : '未配置' }}</dd></div></dl>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { dshApi } from '@/api'
import { mcToast } from '@/composables/useMcToast'
import {
  createEmptyDshConfigForm,
  formToManagedConfig,
  managedConfigToForm,
} from './configMapping'

const loading = ref(true)
const busy = ref(false)
const error = ref('')
const status = reactive<any>({ state: 'NOT_INSTALLED', installed: false, enabled: false, config: {}, artifactManifestConfigured: false })
const form = reactive(createEmptyDshConfigForm())
const targetVersion = ref('')
const operation = reactive<Record<string, string>>({})
const upgrading = computed(() => !!operation.id && !['COMPLETED', 'FAILED', 'INTERRUPTED'].includes(operation.state || ''))
let pollTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
function checkLabel(check: any) { return typeof check?.success !== 'boolean' ? '尚未检测' : check.success ? '通过' : '未通过' }
const state = computed(() => String(status.state || 'NOT_INSTALLED'))
const stateLabel = computed(() => ({ MIGRATION_REQUIRED: '需要迁移旧配置', UNVERIFIED_VERSION: '版本待验证', NOT_INSTALLED: '未安装', INSTALLING: '安装中', INSTALLED_UNCONFIGURED: '已安装待验证', CONFIG_INVALID: '配置不完整', CHECKING: '检测中', CHECK_FAILED: '检测失败', READY: '已就绪', ENABLED: '已启用' } as Record<string, string>)[state.value] || state.value)
const canEnable = computed(() => status.handshake?.success === true && !['CONFIG_INVALID', 'MIGRATION_REQUIRED'].includes(state.value) && !upgrading.value)
const steps = computed(() => [
  { id: 1, title: '安装', description: status.installed ? '运行时已发现' : '安装固定 SDK 版本', done: status.installed, active: !status.installed },
  { id: 2, title: '配置', description: status.config?.workingDirectory ? '连接参数已解析' : '填写运行目录，可复用已有 Provider Key', done: !!status.config?.workingDirectory && state.value !== 'CONFIG_INVALID', active: status.installed && state.value === 'CONFIG_INVALID' },
  { id: 3, title: '验证', description: canEnable.value ? 'SDK 握手已通过' : '完成 SDK 握手测试', done: canEnable.value, active: false },
  { id: 4, title: '启用', description: status.enabled ? 'DSH 已接管运行时' : '启用后新任务使用托管配置', done: status.enabled, active: canEnable.value && !status.enabled },
])

function applyResponse(response: any) {
  const data = response?.data ?? response
  Object.assign(status, data)
  const versions: string[] = (data?.catalog || []).map((release: { version: string }) => release.version)
  if (!versions.includes(targetVersion.value)) {
    targetVersion.value = versions.includes(data?.activeVersion) ? data.activeVersion : versions[0] || ''
  }
  if (data?.managed) Object.assign(form, managedConfigToForm(data.managed))
}

async function load() { loading.value = true; error.value = ''; try { applyResponse(await dshApi.status()) } catch (e: any) { error.value = e?.message || '读取 DSH 状态失败' } finally { loading.value = false } }
async function run(action: () => Promise<any>, message: string) {
  busy.value = true; error.value = ''
  try {
    const response: any = await action()
    const data = response?.data ?? response
    if (data?.success === false) throw new Error(data.message || '检测未通过')
    await load()
    mcToast.success(message)
  } catch (e: any) {
    // A failed recheck must invalidate the previously displayed health evidence.
    status.handshake = {}; status.taskCheck = {}
    await load()
    error.value = e?.message || '操作失败'
    mcToast.error(error.value)
  } finally { busy.value = false }
}
function save() { return run(() => dshApi.saveConfig(formToManagedConfig(form)), 'DSH 配置已保存') }
function verify() { return run(dshApi.verify, 'DSH 配置验证完成') }
async function install() {
  return startOperation(() => dshApi.upgrade({ targetVersion: targetVersion.value, expectedRevision: status.configRevision, idempotencyKey: crypto.randomUUID() }))
}
async function rollback() {
  if (!window.confirm('恢复旧 DSH 数据快照？升级后的内部会话状态不会合并回旧版本；工作目录文件保持现状，新数据将保留。')) return
  return startOperation(() => dshApi.rollback(operation.id, { expectedRevision: status.configRevision, idempotencyKey: crypto.randomUUID() }))
}
async function startOperation(action: () => Promise<any>) {
  busy.value = true; error.value = ''
  try {
    const response: any = await action()
    Object.assign(operation, response?.data ?? response)
    sessionStorage.setItem('dsh-upgrade-operation', operation.id)
    await pollOperation()
  } catch (e: any) { error.value = e?.message || '升级操作失败' }
  finally { busy.value = false }
}
async function pollOperation() {
  if (disposed || !operation.id) return
  try {
    const response: any = await dshApi.upgradeStatus(operation.id)
    Object.assign(operation, response?.data ?? response)
    if (upgrading.value) pollTimer = setTimeout(pollOperation, 2000)
    else { await load(); if (operation.state !== 'COMPLETED') error.value = operation.message || '升级未完成' }
  } catch (e: any) { error.value = e?.message || '读取升级状态失败'; if (!disposed) pollTimer = setTimeout(pollOperation, 5000) }
}
function testConnection() { return run(async () => { const response: any = await dshApi.testConnection(); const data = response?.data ?? response; if (data?.success === false) throw new Error(data.message || 'DSH SDK 握手失败'); return response }, 'DSH SDK 握手通过') }
function testTask() { return run(dshApi.testTask, 'DSH 模型任务通过') }
function enable() { return run(dshApi.enable, 'DSH 已启用') }
function disable() { return run(dshApi.disable, 'DSH 已停用') }
onMounted(async () => { await load(); const id = sessionStorage.getItem('dsh-upgrade-operation'); if (id) { operation.id = id; await pollOperation() } })
onUnmounted(() => { disposed = true; if (pollTimer) clearTimeout(pollTimer) })
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
