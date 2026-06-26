<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { version } from '../package.json'

const appVersion = version

const status = ref<'starting' | 'ready' | 'language-select' | 'initializing' | 'timeout' | 'crashed' | 'restarting' | 'connection-select'>('starting')
const errorMessage = ref('')
const isDark = ref(true)
const selectedLanguage = ref<'zh-CN' | 'en-US' | null>(null)

// ─── Connection chooser state ──────────────────────────
const connectionMode = ref<'local' | 'remote' | null>(null)
const connView = ref<'choose' | 'remote-form'>('choose')
const remoteUrlInput = ref('')
const testing = ref(false)
const testResult = ref<{ ok: boolean; msg: string } | null>(null)
const recentServers = ref<RemoteServer[]>([])
// Build variant: 'local' = full (bundles JRE+JAR), 'remote' = lite (connect to remote server only)
const buildMode = ref<'local' | 'remote'>('local')

let BACKEND_URL = ''

let unsubStatus: (() => void) | null = null
let unsubCrashed: (() => void) | null = null
let unsubUpdater: (() => void) | null = null

// ─── Updater state ─────────────────────────────────────
const updater = ref<UpdaterState>({ status: 'idle' })

const showUpdateBanner = computed(() =>
  ['available', 'downloading', 'downloaded', 'error'].includes(updater.value.status)
)

const downloadPercent = computed(() =>
  Math.round(updater.value.progress?.percent ?? 0)
)

function handleDownloadUpdate() {
  window.mateClawAPI?.downloadUpdate()
}

function handleInstallUpdate() {
  window.mateClawAPI?.installUpdate()
}

// Step progress
const currentStep = computed(() => {
  switch (status.value) {
    case 'starting':        return 1
    case 'restarting':      return 0
    case 'language-select': return 2
    case 'initializing':    return 2
    case 'ready':           return 4
    case 'timeout':
    case 'crashed':         return -1
    default:                return 0
  }
})

const progressWidth = computed(() => {
  if (status.value === 'crashed' || status.value === 'timeout') return '100%'
  if (status.value === 'ready') return '100%'
  if (status.value === 'restarting') return '15%'
  if (status.value === 'language-select') return '60%'
  if (status.value === 'initializing') return '80%'
  return '45%'
})

const steps = [
  { label: 'Environment' },
  { label: 'Starting' },
  { label: 'Language' },
  { label: 'Ready' },
]

function stepClass(index: number) {
  if (status.value === 'crashed' || status.value === 'timeout') return index === 0 ? 'done' : ''
  if (currentStep.value > index) return 'done'
  if (currentStep.value === index) return 'active'
  return ''
}

function toggleTheme() {
  isDark.value = !isDark.value
}

async function checkSetupStatus() {
  try {
    const res = await fetch(`${BACKEND_URL}/api/v1/setup/status`)
    const data = await res.json()
    if (data.data?.initialized) {
      // Already initialized — skip language selection, go directly to app
      status.value = 'ready'
      navigateToApp()
    } else {
      // First run — show language selection
      status.value = 'language-select'
    }
  } catch (e) {
    console.error('Failed to check setup status:', e)
    // If API fails, default to language selection
    status.value = 'language-select'
  }
}

async function selectLanguage(lang: 'zh-CN' | 'en-US') {
  selectedLanguage.value = lang
  status.value = 'initializing'

  try {
    const res = await fetch(`${BACKEND_URL}/api/v1/setup/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ language: lang }),
    })

    if (res.ok || res.status === 409) {
      // 409 means already initialized — that's fine too
      status.value = 'ready'
      setTimeout(() => navigateToApp(), 600)
    } else {
      const err = await res.text()
      throw new Error(err)
    }
  } catch (e: any) {
    console.error('Failed to initialize:', e)
    errorMessage.value = e.message || 'Initialization failed'
    status.value = 'crashed'
  }
}

function navigateToApp() {
  if (window.mateClawAPI?.navigateToApp) {
    window.mateClawAPI.navigateToApp()
  }
}

// ─── Backend-ready dispatch ────────────────────────────
async function handleBackendReady() {
  if (connectionMode.value === 'remote') {
    // A remote server is already initialized by its administrator — skip the
    // local setup/language flow and enter the application directly.
    if (window.mateClawAPI) {
      BACKEND_URL = await window.mateClawAPI.getBackendUrl()
    }
    status.value = 'ready'
    setTimeout(() => navigateToApp(), 300)
  } else {
    checkSetupStatus()
  }
}

// ─── Connection chooser actions ────────────────────────
async function chooseLocal() {
  connectionMode.value = 'local'
  status.value = 'starting'
  await window.mateClawAPI?.useLocalConnection()
  if (window.mateClawAPI) {
    BACKEND_URL = await window.mateClawAPI.getBackendUrl()
  }
}

function openRemoteForm() {
  connView.value = 'remote-form'
  testResult.value = null
}

function backToChoose() {
  connView.value = 'choose'
  testResult.value = null
}

function describeConnError(r: { error?: string }): string {
  switch (r.error) {
    case 'invalid-url': return '地址格式无效'
    case 'timeout':     return '连接超时，请检查地址与网络'
    default:            return r.error ? `连接失败: ${r.error}` : '连接失败'
  }
}

async function testRemote() {
  if (!remoteUrlInput.value.trim() || !window.mateClawAPI) return
  testing.value = true
  testResult.value = null
  try {
    const r = await window.mateClawAPI.testConnection(remoteUrlInput.value)
    testResult.value = r.ok
      ? { ok: true, msg: '连接成功' }
      : { ok: false, msg: describeConnError(r) }
  } finally {
    testing.value = false
  }
}

async function connectRemote(url?: string) {
  const target = (url ?? remoteUrlInput.value).trim()
  if (!target || !window.mateClawAPI) return
  remoteUrlInput.value = target
  const r = await window.mateClawAPI.useRemoteConnection(target)
  if (!r.ok) {
    testResult.value = { ok: false, msg: describeConnError(r) }
    return
  }
  connectionMode.value = 'remote'
  testResult.value = null
  status.value = 'starting'
}

onMounted(async () => {
  // Detect system dark mode preference
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  isDark.value = prefersDark

  createParticles()

  if (window.mateClawAPI) {
    // Get dynamic backend URL from main process
    BACKEND_URL = await window.mateClawAPI.getBackendUrl()

    unsubStatus = window.mateClawAPI.onBackendStatus((s: string) => {
      if (s === 'choose') {
        status.value = 'connection-select'
      } else if (s === 'ready') {
        handleBackendReady()
      } else {
        status.value = s as typeof status.value
      }
    })
    unsubCrashed = window.mateClawAPI.onBackendCrashed((msg: string) => {
      status.value = 'crashed'
      errorMessage.value = msg
    })

    // Decide the initial screen from saved connection configuration.
    try {
      const cfg = await window.mateClawAPI.getConnectionConfig()
      recentServers.value = cfg.servers || []
      remoteUrlInput.value = cfg.remoteUrl || ''
      buildMode.value = cfg.buildMode || 'local'

      // Remote (lite) builds: skip the mode chooser, go straight to the
      // remote server form — the "local" option is not available.
      if (buildMode.value === 'remote') {
        if (cfg.forceChoose || !cfg.mode || cfg.mode === 'local') {
          status.value = 'connection-select'
          connView.value = 'remote-form'
        } else {
          connectionMode.value = cfg.mode
          if (await window.mateClawAPI.isBackendReady()) {
            handleBackendReady()
          }
        }
      } else if (cfg.forceChoose || !cfg.mode) {
        status.value = 'connection-select'
        connView.value = 'choose'
      } else {
        connectionMode.value = cfg.mode
        // The backend may have become ready before listeners attached.
        if (await window.mateClawAPI.isBackendReady()) {
          handleBackendReady()
        }
      }
    } catch (e) {
      console.error('Failed to load connection config:', e)
    }

    unsubUpdater = window.mateClawAPI.onUpdaterState((state: UpdaterState) => {
      updater.value = state
    })
    window.mateClawAPI.getUpdaterState().then((state) => {
      if (state) updater.value = state
    })
  }
})

onUnmounted(() => {
  unsubStatus?.()
  unsubCrashed?.()
  unsubUpdater?.()
})

function handleRestart() {
  status.value = 'restarting'
  errorMessage.value = ''
  window.mateClawAPI?.restartBackend()
}

function createParticles() {
  const container = document.getElementById('particles')
  if (!container) return
  for (let i = 0; i < 12; i++) {
    const p = document.createElement('div')
    p.className = 'particle'
    const size = Math.random() * 3 + 1.5
    p.style.width = size + 'px'
    p.style.height = size + 'px'
    p.style.left = Math.random() * 100 + '%'
    p.style.bottom = '-10px'
    p.style.animationDuration = (Math.random() * 6 + 5) + 's'
    p.style.animationDelay = (Math.random() * 8) + 's'
    container.appendChild(p)
  }
}
</script>

<template>
  <div :class="{ 'theme-dark': isDark, 'theme-light': !isDark }">
    <!-- Background layers -->
    <div class="bg-layer">
      <div class="orb orb-1"></div>
      <div class="orb orb-2"></div>
      <div class="orb orb-3"></div>
    </div>
    <div class="bg-noise"></div>
    <div class="bg-grid"></div>

    <!-- Particles -->
    <div class="particles" id="particles"></div>

    <!-- Main splash content -->
    <div class="splash">
      <!-- Logo -->
      <div class="logo-section fade-enter">
        <div class="logo-wrap">
          <div class="logo-glow"></div>
          <img class="logo-img" src="/logo/mateclaw_logo_s.png" alt="MateClaw" />
        </div>
        <div class="brand-name">
          <span class="mate">Mate</span><span class="claw">Claw</span>
        </div>
        <div class="brand-tagline">AI Personal Assistant</div>
      </div>

      <!-- Connection Selection Card -->
      <div
        v-if="status === 'connection-select'"
        class="status-card fade-enter"
      >
        <!-- Mode choice -->
        <template v-if="connView === 'choose'">
          <div class="lang-title">选择连接方式 / Connection</div>
          <div class="lang-options">
            <button
              v-if="buildMode === 'local'"
              class="lang-card"
              @click="chooseLocal"
            >
              <span class="lang-flag">💻</span>
              <span class="lang-label">本地运行</span>
              <span class="lang-desc">在本机内嵌运行服务</span>
            </button>
            <button class="lang-card" @click="openRemoteForm">
              <span class="lang-flag">🌐</span>
              <span class="lang-label">连接远程</span>
              <span class="lang-desc">接入集中部署的服务器</span>
            </button>
          </div>
          <!-- Remote (lite) build notice -->
          <div v-if="buildMode === 'remote'" class="remote-build-notice">
            当前为轻量版客户端，仅支持连接远程服务器
          </div>
        </template>

        <!-- Remote server form -->
        <template v-else>
          <div class="lang-title">连接远程服务器</div>
          <div class="conn-form">
            <input
              class="conn-input"
              v-model.trim="remoteUrlInput"
              type="text"
              placeholder="https://server.example.com"
              spellcheck="false"
              autocapitalize="off"
              @keyup.enter="connectRemote()"
            />
            <div
              v-if="testResult"
              class="conn-result"
              :class="{ ok: testResult.ok, bad: !testResult.ok }"
            >
              {{ testResult.msg }}
            </div>
            <div v-if="recentServers.length" class="conn-recent">
              <div class="conn-recent-title">最近使用</div>
              <button
                v-for="s in recentServers"
                :key="s.url"
                class="conn-recent-item"
                @click="connectRemote(s.url)"
              >{{ s.url }}</button>
            </div>
            <div class="conn-actions">
              <button class="conn-btn ghost" @click="backToChoose">返回</button>
              <button
                class="conn-btn ghost"
                :disabled="testing || !remoteUrlInput"
                @click="testRemote"
              >{{ testing ? '测试中…' : '测试连接' }}</button>
              <button
                class="conn-btn primary"
                :disabled="!remoteUrlInput"
                @click="connectRemote()"
              >连接</button>
            </div>
          </div>
        </template>
      </div>

      <!-- Language Selection Card -->
      <div
        v-else-if="status === 'language-select'"
        class="status-card fade-enter"
      >
        <div class="lang-title">Choose Language / 选择语言</div>
        <div class="lang-options">
          <button class="lang-card" @click="selectLanguage('zh-CN')">
            <span class="lang-flag">🇨🇳</span>
            <span class="lang-label">中文</span>
            <span class="lang-desc">简体中文界面</span>
          </button>
          <button class="lang-card" @click="selectLanguage('en-US')">
            <span class="lang-flag">🇺🇸</span>
            <span class="lang-label">English</span>
            <span class="lang-desc">English interface</span>
          </button>
        </div>

        <!-- Steps (compact) -->
        <div class="steps" style="margin-top: 8px;">
          <div
            v-for="(step, i) in steps"
            :key="i"
            class="step"
            :class="stepClass(i)"
          >
            <div class="step-dot">
              <svg v-if="stepClass(i) === 'done'" width="10" height="10" viewBox="0 0 10 10">
                <path d="M2 5.2l2.2 2.3L8 3" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span v-else class="step-num">{{ i + 1 }}</span>
            </div>
            <span class="step-label">{{ step.label }}</span>
          </div>
        </div>
      </div>

      <!-- Status Card (starting / initializing / ready / error) -->
      <div
        v-else
        class="status-card fade-enter"
        :class="{
          'ready': status === 'ready',
          'error-state': status === 'crashed' || status === 'timeout'
        }"
      >
        <!-- Steps -->
        <div class="steps">
          <div
            v-for="(step, i) in steps"
            :key="i"
            class="step"
            :class="stepClass(i)"
          >
            <div class="step-dot">
              <!-- Done: checkmark -->
              <svg v-if="stepClass(i) === 'done'" width="10" height="10" viewBox="0 0 10 10">
                <path d="M2 5.2l2.2 2.3L8 3" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <!-- Step 0: gear/cog -->
              <svg v-else-if="i === 0" width="12" height="12" viewBox="0 0 16 16" fill="none">
                <path d="M8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z" stroke="currentColor" stroke-width="1.2"/>
                <path d="M7 1.5h2l.3 1.6a5 5 0 0 1 1.3.7l1.5-.6.9 1.6-1.2 1a5 5 0 0 1 0 1.6l1.2 1-.9 1.6-1.5-.6a5 5 0 0 1-1.3.7L9 14.5H7l-.3-1.6a5 5 0 0 1-1.3-.7l-1.5.6-.9-1.6 1.2-1a5 5 0 0 1 0-1.6l-1.2-1 .9-1.6 1.5.6a5 5 0 0 1 1.3-.7L7 1.5z" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round"/>
              </svg>
              <!-- Step 1: rocket -->
              <svg v-else-if="i === 1" width="12" height="12" viewBox="0 0 16 16" fill="none">
                <path d="M8 2C8 2 4.5 4 4 8c-.3 2.5.5 4 .5 4M8 2c0 0 3.5 2 4 6 .3 2.5-.5 4-.5 4M8 2v9" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
                <circle cx="8" cy="7" r="1.3" stroke="currentColor" stroke-width="1" fill="none"/>
                <path d="M6 13l2 1.5L10 13" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <!-- Step 2: globe/language -->
              <svg v-else-if="i === 2" width="12" height="12" viewBox="0 0 16 16" fill="none">
                <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.2"/>
                <path d="M2 8h12M8 2c-2 2-2 4-2 6s0 4 2 6M8 2c2 2 2 4 2 6s0 4-2 6" stroke="currentColor" stroke-width="1" stroke-linecap="round"/>
              </svg>
              <!-- Step 3: shield-check / ready -->
              <svg v-else-if="i === 3" width="12" height="12" viewBox="0 0 16 16" fill="none">
                <path d="M8 1.5L3 4v4c0 3.3 2.2 5.5 5 6.5 2.8-1 5-3.2 5-6.5V4L8 1.5z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
                <path d="M5.8 8l1.6 1.6L10.2 6" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <span class="step-label">{{ step.label }}</span>
          </div>
        </div>

        <!-- Progress bar -->
        <div class="progress-wrap">
          <div class="progress-bar" :style="{ width: progressWidth }"></div>
        </div>

        <!-- Status text -->
        <div class="status-row">
          <template v-if="status === 'starting' || status === 'restarting'">
            <div class="spinner"></div>
            <span class="status-text">{{ status === 'restarting' ? 'Restarting service...' : 'Starting backend service...' }}</span>
          </template>

          <template v-else-if="status === 'initializing'">
            <div class="spinner"></div>
            <span class="status-text">Initializing{{ selectedLanguage === 'zh-CN' ? ' (中文)' : ' (English)' }}...</span>
          </template>

          <template v-else-if="status === 'ready'">
            <div class="icon-check">
              <svg width="10" height="10" viewBox="0 0 10 10"><path d="M2 5.2l2.2 2.3L8 3" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </div>
            <span class="status-text success">Ready, entering application...</span>
          </template>

          <template v-else-if="status === 'timeout' || status === 'crashed'">
            <div class="icon-error">
              <svg width="10" height="10" viewBox="0 0 10 10"><path d="M3 3l4 4M7 3l-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
            </div>
            <span class="status-text error">{{ status === 'timeout' ? 'Startup timeout' : 'Service error' }}</span>
          </template>
        </div>

        <!-- Error detail -->
        <div v-if="errorMessage && (status === 'crashed' || status === 'timeout')" class="error-detail">
          {{ errorMessage }}
        </div>

        <!-- Retry button -->
        <button
          v-if="status === 'crashed' || status === 'timeout'"
          class="retry-btn"
          @click="handleRestart"
        >
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M1.5 7a5.5 5.5 0 1 1 1.1 3.3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            <path d="M1 4v3.5h3.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          Restart
        </button>
      </div>
    </div>

    <!-- Bottom bar -->
    <div class="bottom-bar">
      <div class="version-tag">
        <span class="version-dot"></span>
        MateClaw Desktop v{{ appVersion }}
      </div>

      <!-- Update banner -->
      <div v-if="showUpdateBanner" class="update-banner">
        <template v-if="updater.status === 'available'">
          <span class="update-text">v{{ updater.version }} available</span>
          <button class="update-btn" @click="handleDownloadUpdate">Download</button>
        </template>
        <template v-else-if="updater.status === 'downloading'">
          <div class="update-progress-bar">
            <div class="update-progress-fill" :style="{ width: downloadPercent + '%' }"></div>
          </div>
          <span class="update-text muted">{{ downloadPercent }}%</span>
        </template>
        <template v-else-if="updater.status === 'downloaded'">
          <span class="update-text">v{{ updater.version }} ready</span>
          <button class="update-btn restart" @click="handleInstallUpdate">Restart</button>
        </template>
        <template v-else-if="updater.status === 'error'">
          <span class="update-text error-text">Update failed</span>
        </template>
      </div>

      <!-- Theme toggle -->
      <div class="theme-toggle">
        <button class="theme-btn" :class="{ active: isDark }" @click="isDark || toggleTheme()">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
            <path d="M13.5 8.5a5.5 5.5 0 1 1-6-6 4.5 4.5 0 0 0 6 6z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
          </svg>
        </button>
        <button class="theme-btn" :class="{ active: !isDark }" @click="isDark && toggleTheme()">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
            <circle cx="8" cy="8" r="3" stroke="currentColor" stroke-width="1.2"/>
            <path d="M8 2v1.5M8 12.5V14M2 8h1.5M12.5 8H14M3.7 3.7l1 1M11.3 11.3l1 1M12.3 3.7l-1 1M4.7 11.3l-1 1" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ══════════════════════════════════════════════════
   Design Tokens — Dark Theme (default)
══════════════════════════════════════════════════ */
.theme-dark {
  --mc-primary:       #D97757;
  --mc-primary-light: #E08860;
  --mc-primary-dark:  #C1572B;

  --bg-base:          #1A1410;
  --bg-elevated:      #231C17;
  --bg-card:          #2D2420;
  --bg-glow-1:        rgba(217, 119, 87, 0.18);
  --bg-glow-2:        rgba(184, 92, 58, 0.12);
  --bg-glow-3:        rgba(139, 58, 30, 0.08);

  --text-primary:     #F0EAE4;
  --text-secondary:   #C4A898;
  --text-tertiary:    #8A7060;
  --text-muted:       #5A4A42;

  --border:           rgba(217,119,87,.15);
  --border-strong:    rgba(217,119,87,.3);

  --success:          #7AB87A;
  --success-bg:       rgba(122,184,122,.15);
  --error:            #E05A4A;
  --error-bg:         rgba(224,90,74,.15);

  --card-shadow:      rgba(0,0,0,.3);
  --card-inset:       rgba(255,255,255,.04);
}

/* ══════════════════════════════════════════════════
   Design Tokens — Light Theme
══════════════════════════════════════════════════ */
.theme-light {
  --mc-primary:       #D97757;
  --mc-primary-light: #E8A87C;
  --mc-primary-dark:  #B85C3A;

  --bg-base:          #FAF6F2;
  --bg-elevated:      #FFFFFF;
  --bg-card:          #FFFFFF;
  --bg-glow-1:        rgba(217, 119, 87, 0.08);
  --bg-glow-2:        rgba(184, 92, 58, 0.06);
  --bg-glow-3:        rgba(139, 58, 30, 0.04);

  --text-primary:     #2D1A0E;
  --text-secondary:   #6B4535;
  --text-tertiary:    #9A7060;
  --text-muted:       #C4A898;

  --border:           rgba(217,119,87,.18);
  --border-strong:    rgba(217,119,87,.35);

  --success:          #5A8A5A;
  --success-bg:       rgba(90,138,90,.12);
  --error:            #C0392B;
  --error-bg:         rgba(192,57,43,.1);

  --card-shadow:      rgba(139,58,30,.08);
  --card-inset:       rgba(255,255,255,.6);
}

/* ══════════════════════════════════════════════════
   Reset
══════════════════════════════════════════════════ */
* { box-sizing: border-box; margin: 0; padding: 0; }

/* ══════════════════════════════════════════════════
   Background — Layered Glow Orbs
══════════════════════════════════════════════════ */
.bg-layer {
  position: fixed; inset: 0; z-index: 0;
  pointer-events: none;
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  animation: orb-drift 8s ease-in-out infinite;
  transition: background .4s;
}
.orb-1 {
  width: 500px; height: 500px;
  top: -120px; left: -80px;
  background: var(--bg-glow-1);
}
.orb-2 {
  width: 400px; height: 400px;
  bottom: -100px; right: -60px;
  background: var(--bg-glow-2);
  animation-delay: -3s;
}
.orb-3 {
  width: 300px; height: 300px;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  background: var(--bg-glow-3);
  animation-delay: -5s;
  animation-name: orb-drift-center;
}
@keyframes orb-drift {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33%       { transform: translate(30px, -20px) scale(1.05); }
  66%       { transform: translate(-20px, 15px) scale(0.95); }
}
@keyframes orb-drift-center {
  0%, 100% { transform: translate(-50%, -50%) scale(1); }
  50%       { transform: translate(-50%, -55%) scale(1.1); }
}

.bg-noise {
  position: fixed; inset: 0; z-index: 1;
  opacity: .03;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  pointer-events: none;
}
.bg-grid {
  position: fixed; inset: 0; z-index: 1;
  background-image:
    linear-gradient(var(--border) 1px, transparent 1px),
    linear-gradient(90deg, var(--border) 1px, transparent 1px);
  background-size: 60px 60px;
  opacity: .4;
  pointer-events: none;
  mask-image: radial-gradient(ellipse 80% 80% at 50% 50%, black 30%, transparent 100%);
  -webkit-mask-image: radial-gradient(ellipse 80% 80% at 50% 50%, black 30%, transparent 100%);
  transition: opacity .4s;
}
.theme-light .bg-grid { opacity: .25; }

/* ══════════════════════════════════════════════════
   Particles
══════════════════════════════════════════════════ */
.particles {
  position: fixed; inset: 0; z-index: 2;
  pointer-events: none;
  overflow: hidden;
}
:deep(.particle) {
  position: absolute;
  border-radius: 50%;
  background: var(--mc-primary);
  opacity: 0;
  animation: particle-rise linear infinite;
}
@keyframes particle-rise {
  0%   { opacity: 0;   transform: translateY(0) scale(0); }
  10%  { opacity: .15; transform: translateY(-20px) scale(1); }
  90%  { opacity: .08; transform: translateY(-200px) scale(.5); }
  100% { opacity: 0;   transform: translateY(-240px) scale(0); }
}
.theme-light :deep(.particle) { opacity: 0; animation: particle-rise-light linear infinite; }
@keyframes particle-rise-light {
  0%   { opacity: 0;   transform: translateY(0) scale(0); }
  10%  { opacity: .1;  transform: translateY(-20px) scale(1); }
  90%  { opacity: .05; transform: translateY(-200px) scale(.5); }
  100% { opacity: 0;   transform: translateY(-240px) scale(0); }
}

/* ══════════════════════════════════════════════════
   Main Layout
══════════════════════════════════════════════════ */
.splash {
  position: relative; z-index: 10;
  width: 100vw; height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0;
  font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  background: var(--bg-base);
  color: var(--text-primary);
  overflow: hidden;
  transition: background .4s, color .4s;
}

/* ══════════════════════════════════════════════════
   Logo Section
══════════════════════════════════════════════════ */
.logo-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  margin-bottom: 48px;
}
.logo-wrap {
  position: relative;
  width: 100px; height: 100px;
}
.logo-glow {
  position: absolute;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  width: 140px; height: 140px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(217,119,87,.3) 0%, transparent 70%);
  animation: glow-pulse 3s ease-in-out infinite;
  pointer-events: none;
}
.theme-light .logo-glow {
  background: radial-gradient(circle, rgba(217,119,87,.15) 0%, transparent 70%);
}
@keyframes glow-pulse {
  0%, 100% { opacity: .6; transform: translate(-50%, -50%) scale(1); }
  50%       { opacity: 1;  transform: translate(-50%, -50%) scale(1.15); }
}
.logo-img {
  width: 100px; height: 100px;
  object-fit: contain;
  position: relative;
  z-index: 1;
  animation: logo-float 4s ease-in-out infinite;
  filter: drop-shadow(0 12px 32px rgba(217,119,87,.4));
}
.theme-light .logo-img {
  filter: drop-shadow(0 8px 20px rgba(217,119,87,.25));
}
@keyframes logo-float {
  0%, 100% { transform: translateY(0px); }
  25%       { transform: translateY(-6px); }
  75%       { transform: translateY(-3px); }
}

.brand-name {
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.5px;
}
.brand-name .mate { color: var(--text-primary); transition: color .4s; }
.brand-name .claw { color: var(--mc-primary); }
.brand-tagline {
  font-size: 13px;
  color: var(--text-tertiary);
  letter-spacing: 0.5px;
  transition: color .4s;
}

/* ══════════════════════════════════════════════════
   Status Card
══════════════════════════════════════════════════ */
.status-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 24px 32px;
  width: 360px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  box-shadow:
    0 1px 0 var(--card-inset) inset,
    0 20px 60px var(--card-shadow);
  transition: all .4s ease;
}
.theme-light .status-card {
  box-shadow:
    0 1px 0 var(--card-inset) inset,
    0 4px 24px var(--card-shadow),
    0 1px 3px rgba(0,0,0,.06);
}
.status-card.ready {
  border-color: rgba(122,184,122,.3);
}
.theme-dark .status-card.ready {
  box-shadow: 0 1px 0 var(--card-inset) inset, 0 20px 60px var(--card-shadow), 0 0 40px rgba(122,184,122,.08);
}
.status-card.error-state {
  border-color: rgba(224,90,74,.3);
}
.theme-dark .status-card.error-state {
  box-shadow: 0 1px 0 var(--card-inset) inset, 0 20px 60px var(--card-shadow), 0 0 40px rgba(224,90,74,.08);
}

/* ══════════════════════════════════════════════════
   Language Selection
══════════════════════════════════════════════════ */
.lang-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  text-align: center;
  margin-bottom: 4px;
}
.lang-options {
  display: flex;
  gap: 12px;
  width: 100%;
}
.lang-card {
  -webkit-app-region: no-drag;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 18px 12px;
  border-radius: 12px;
  border: 1.5px solid var(--border);
  background: var(--bg-elevated);
  cursor: pointer;
  transition: all .2s;
}
.lang-card:hover {
  border-color: var(--mc-primary);
  background: rgba(217,119,87,.06);
  box-shadow: 0 0 0 3px rgba(217,119,87,.12);
  transform: translateY(-2px);
}
.lang-card:active {
  transform: translateY(0) scale(.98);
}
.lang-flag {
  font-size: 28px;
  line-height: 1;
}
.lang-label {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
.lang-desc {
  font-size: 11px;
  color: var(--text-tertiary);
}
.remote-build-notice {
  font-size: 11px;
  color: var(--text-tertiary);
  text-align: center;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  width: 100%;
}

/* ══════════════════════════════════════════════════
   Remote Connection Form
══════════════════════════════════════════════════ */
.conn-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}
.conn-input {
  -webkit-app-region: no-drag;
  width: 100%;
  padding: 11px 14px;
  border-radius: 10px;
  border: 1.5px solid var(--border);
  background: var(--bg-elevated);
  color: var(--text-primary);
  font-size: 13px;
  outline: none;
  transition: all .2s;
}
.conn-input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px rgba(217,119,87,.12);
}
.conn-input::placeholder { color: var(--text-muted); }

.conn-result {
  font-size: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  line-height: 1.4;
}
.conn-result.ok {
  color: var(--success);
  background: var(--success-bg);
  border: 1px solid rgba(122,184,122,.25);
}
.conn-result.bad {
  color: var(--error);
  background: var(--error-bg);
  border: 1px solid rgba(224,90,74,.2);
}

.conn-recent {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.conn-recent-title {
  font-size: 10px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: .5px;
}
.conn-recent-item {
  -webkit-app-region: no-drag;
  text-align: left;
  padding: 7px 10px;
  border-radius: 7px;
  border: 1px solid var(--border);
  background: var(--bg-elevated);
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all .15s;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.conn-recent-item:hover {
  border-color: var(--mc-primary);
  color: var(--text-primary);
}

.conn-actions {
  display: flex;
  gap: 8px;
  margin-top: 2px;
}
.conn-btn {
  -webkit-app-region: no-drag;
  flex: 1;
  padding: 9px 0;
  border-radius: 9px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all .2s;
}
.conn-btn:disabled {
  opacity: .45;
  cursor: not-allowed;
}
.conn-btn.ghost {
  border: 1.5px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
}
.conn-btn.ghost:hover:not(:disabled) {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}
.conn-btn.primary {
  border: 1.5px solid var(--mc-primary);
  background: var(--mc-primary);
  color: #fff;
}
.conn-btn.primary:hover:not(:disabled) {
  background: var(--mc-primary-dark);
  border-color: var(--mc-primary-dark);
}

/* ── Progress Steps ── */
.steps {
  display: flex;
  align-items: center;
  width: 100%;
  margin-bottom: 4px;
}
.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  flex: 1;
  position: relative;
}
.step:not(:last-child)::after {
  content: '';
  position: absolute;
  top: 11px;
  left: calc(50% + 12px);
  right: calc(-50% + 12px);
  height: 1.5px;
  background: var(--border);
  transition: background .4s;
}
.step.done:not(:last-child)::after { background: var(--mc-primary); }
.step.active:not(:last-child)::after { background: linear-gradient(90deg, var(--mc-primary), var(--border)); }

.step-dot {
  width: 24px; height: 24px;
  border-radius: 50%;
  border: 1.5px solid var(--border);
  background: var(--bg-elevated);
  display: flex; align-items: center; justify-content: center;
  color: var(--text-muted);
  transition: all .3s;
  position: relative; z-index: 1;
}
.step-num {
  font-size: 10px;
  font-weight: 600;
}
.step.done .step-dot {
  background: var(--mc-primary);
  border-color: var(--mc-primary);
  color: #fff;
  box-shadow: 0 0 0 4px rgba(217,119,87,.2);
}
.step.active .step-dot {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
  box-shadow: 0 0 0 4px rgba(217,119,87,.15);
  animation: step-pulse 1.5s ease-in-out infinite;
}
@keyframes step-pulse {
  0%, 100% { box-shadow: 0 0 0 4px rgba(217,119,87,.15); }
  50%       { box-shadow: 0 0 0 8px rgba(217,119,87,.08); }
}
.step-label {
  font-size: 10px;
  color: var(--text-muted);
  white-space: nowrap;
  transition: color .3s;
}
.step.done .step-label,
.step.active .step-label {
  color: var(--text-secondary);
}

/* ── Progress Bar ── */
.progress-wrap {
  width: 100%;
  height: 3px;
  background: var(--border);
  border-radius: 100px;
  overflow: hidden;
}
.progress-bar {
  height: 100%;
  border-radius: 100px;
  background: linear-gradient(90deg, var(--mc-primary-dark), var(--mc-primary), var(--mc-primary-light));
  background-size: 200% 100%;
  transition: width .6s cubic-bezier(.4,0,.2,1);
  animation: shimmer 2s linear infinite;
}
@keyframes shimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

/* ── Status Row ── */
.status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 28px;
}
.spinner {
  width: 18px; height: 18px;
  border: 2px solid var(--border-strong);
  border-top-color: var(--mc-primary);
  border-radius: 50%;
  animation: spin .7s linear infinite;
  flex-shrink: 0;
}
@keyframes spin { to { transform: rotate(360deg); } }

.icon-check {
  width: 18px; height: 18px;
  border-radius: 50%;
  background: var(--success-bg);
  border: 1.5px solid var(--success);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  color: var(--success);
}
.icon-error {
  width: 18px; height: 18px;
  border-radius: 50%;
  background: var(--error-bg);
  border: 1.5px solid var(--error);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  color: var(--error);
}

.status-text {
  font-size: 13px;
  color: var(--text-secondary);
  transition: color .3s;
}
.status-text.success { color: var(--success); }
.status-text.error   { color: var(--error); }

/* ── Error Detail ── */
.error-detail {
  width: 100%;
  background: var(--error-bg);
  border: 1px solid rgba(224,90,74,.2);
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 11px;
  color: var(--error);
  font-family: 'SF Mono', 'Consolas', monospace;
  line-height: 1.5;
  word-break: break-all;
}

/* ── Retry Button ── */
.retry-btn {
  -webkit-app-region: no-drag;
  padding: 8px 24px;
  border-radius: 8px;
  border: 1.5px solid var(--mc-primary);
  background: transparent;
  color: var(--mc-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all .2s;
  display: flex;
  align-items: center;
  gap: 6px;
}
.retry-btn:hover {
  background: rgba(217,119,87,.1);
  box-shadow: 0 0 0 3px rgba(217,119,87,.15);
}
.retry-btn:active { transform: scale(.97); }

/* ══════════════════════════════════════════════════
   Bottom Bar
══════════════════════════════════════════════════ */
.bottom-bar {
  position: fixed;
  bottom: 0; left: 0; right: 0;
  padding: 16px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  z-index: 20;
}
.version-tag {
  font-size: 11px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 6px;
  transition: color .4s;
}
.version-dot {
  width: 4px; height: 4px;
  border-radius: 50%;
  background: var(--mc-primary);
  opacity: .6;
}

/* ── Theme Toggle ── */
.theme-toggle {
  display: flex;
  align-items: center;
  gap: 2px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 3px;
  transition: background .4s, border-color .4s;
}
.theme-btn {
  -webkit-app-region: no-drag;
  padding: 4px 8px;
  border-radius: 5px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: all .2s;
  display: flex;
  align-items: center;
  line-height: 0;
}
.theme-btn.active {
  background: var(--bg-elevated);
  color: var(--text-secondary);
  box-shadow: 0 1px 3px rgba(0,0,0,.15);
}
.theme-btn:hover:not(.active) { color: var(--text-secondary); }

/* ══════════════════════════════════════════════════
   Update Banner
══════════════════════════════════════════════════ */
.update-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 11px;
  animation: fade-in .3s ease forwards;
}
.update-text {
  color: var(--text-secondary);
  white-space: nowrap;
}
.update-text.muted {
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  min-width: 32px;
  text-align: right;
}
.update-text.error-text {
  color: var(--error);
}
.update-btn {
  -webkit-app-region: no-drag;
  padding: 3px 10px;
  border-radius: 5px;
  border: 1px solid var(--mc-primary);
  background: transparent;
  color: var(--mc-primary);
  cursor: pointer;
  font-size: 11px;
  font-weight: 500;
  transition: all .2s;
  white-space: nowrap;
}
.update-btn:hover {
  background: var(--mc-primary);
  color: var(--bg-base);
}
.update-btn.restart {
  border-color: var(--success);
  color: var(--success);
}
.update-btn.restart:hover {
  background: var(--success);
  color: var(--bg-base);
}
.update-progress-bar {
  width: 80px;
  height: 4px;
  background: var(--border);
  border-radius: 2px;
  overflow: hidden;
}
.update-progress-fill {
  height: 100%;
  background: var(--mc-primary);
  border-radius: 2px;
  transition: width .3s ease;
}

/* ══════════════════════════════════════════════════
   Animations
══════════════════════════════════════════════════ */
.fade-enter {
  animation: fade-in .5s ease forwards;
}
@keyframes fade-in {
  from { opacity: 0; transform: translateY(10px); }
  to   { opacity: 1; transform: translateY(0); }
}
</style>
