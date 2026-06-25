import { app, BrowserWindow, shell, ipcMain, dialog, Menu, nativeImage } from 'electron'
import { join, resolve } from 'path'
import { ChildProcess, spawn } from 'child_process'
import { existsSync, mkdirSync } from 'fs'
import http from 'http'
import https from 'https'
import net from 'net'
import { autoUpdater } from 'electron-updater'
import type { UpdateInfo, ProgressInfo } from 'electron-updater'
import {
  loadConfig,
  saveConfig,
  normalizeServerUrl,
  recordServer,
  type ConnectionMode,
} from './config'

// ─── Constants ───────────────────────────────────────────────────────────────

let BACKEND_PORT = 0
let BACKEND_URL = ''
const HEALTH_CHECK_INTERVAL = 1000 // ms
const HEALTH_CHECK_TIMEOUT = 120_000 // 2 minutes max wait
const WINDOW_WIDTH = 1280
const WINDOW_HEIGHT = 860

// ─── State ───────────────────────────────────────────────────────────────────

let mainWindow: BrowserWindow | null = null
let javaProcess: ChildProcess | null = null
let isQuitting = false
let isUpdating = false
let backendReady = false

// Connection state: which backend the shell is talking to.
let connectionMode: ConnectionMode | null = null
// When true, the splash shows the connection chooser even if a mode was saved
// (used by the "Switch Server" menu action).
let forceChooser = false
// Hosts whose TLS certificate the user explicitly trusted this session (covers
// enterprise self-signed certificates on remote servers).
const trustedCertHosts = new Set<string>()

// Reachability probes (health poll / test button) must not hard-fail on an
// untrusted certificate — that only signals reachability. The real trust
// decision still happens at BrowserWindow navigation via the certificate-error
// handler, which prompts the user before loading the page.
const insecureAgent = new https.Agent({ rejectUnauthorized: false })

interface UpdaterState {
  status: 'idle' | 'checking' | 'available' | 'not-available' | 'downloading' | 'downloaded' | 'error'
  version?: string
  releaseNotes?: string
  progress?: { percent: number; bytesPerSecond: number; transferred: number; total: number }
  error?: string
}

let updaterState: UpdaterState = { status: 'idle' }

// ─── Platform Detection & Resource Paths ─────────────────────────────────────

function getResourcesPath(): string {
  // In production: process.resourcesPath points to <app>/Contents/Resources (macOS) or <app>/resources (Windows)
  // In dev: use the local resources/ directory
  if (app.isPackaged) {
    return process.resourcesPath
  }
  return resolve(__dirname, '../../resources')
}

function getJavaExecutable(): string {
  const resourcesPath = getResourcesPath()
  const platform = process.platform

  // In production: extraResources copies jre/<platform>/* → Resources/jre/
  // In dev: jre is at resources/jre/<platform-arch>/
  const jrePath = join(resourcesPath, 'jre')

  // Candidate paths for java binary (try all known layouts)
  const candidates: string[] = []

  if (platform === 'darwin') {
    // Packaged: jre/Contents/Home/bin/java
    candidates.push(join(jrePath, 'Contents', 'Home', 'bin', 'java'))
    // Dev: jre/mac-arm64/Contents/Home/bin/java
    candidates.push(join(jrePath, 'mac-arm64', 'Contents', 'Home', 'bin', 'java'))
    candidates.push(join(jrePath, 'mac-x64', 'Contents', 'Home', 'bin', 'java'))
    // Fallback: flat layout
    candidates.push(join(jrePath, 'bin', 'java'))
  } else if (platform === 'win32') {
    candidates.push(join(jrePath, 'bin', 'java.exe'))
    candidates.push(join(jrePath, 'win32-x64', 'bin', 'java.exe'))
  } else {
    candidates.push(join(jrePath, 'bin', 'java'))
    candidates.push(join(jrePath, 'linux-x64', 'bin', 'java'))
    candidates.push(join(jrePath, 'linux-arm64', 'bin', 'java'))
  }

  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate
  }

  // Return first candidate for error reporting
  return candidates[0]
}

function getJarPath(): string {
  const resourcesPath = getResourcesPath()
  return join(resourcesPath, 'app.jar')
}

function getUserDataPath(): string {
  const dataPath = join(app.getPath('userData'), 'data')
  if (!existsSync(dataPath)) {
    mkdirSync(dataPath, { recursive: true })
  }
  return app.getPath('userData')
}

// ─── Java Backend Lifecycle ──────────────────────────────────────────────────

function getAvailablePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address() as net.AddressInfo
      server.close(() => resolve(port))
    })
    server.on('error', reject)
  })
}

async function startJavaBackend(): Promise<void> {
  BACKEND_PORT = await getAvailablePort()
  BACKEND_URL = `http://localhost:${BACKEND_PORT}`
  console.log(`[MateClaw] Using dynamic port: ${BACKEND_PORT}`)

  const javaExec = getJavaExecutable()
  const jarPath = getJarPath()
  const workingDir = getUserDataPath()

  console.log(`[MateClaw] Java executable: ${javaExec}`)
  console.log(`[MateClaw] JAR path: ${jarPath}`)
  console.log(`[MateClaw] Working directory: ${workingDir}`)

  if (!existsSync(javaExec)) {
    console.error(`[MateClaw] Java executable not found: ${javaExec}`)
    dialog.showErrorBox(
      'MateClaw 启动失败',
      `找不到 Java 运行时环境。\n路径: ${javaExec}\n\n请重新安装 MateClaw。`
    )
    app.quit()
    return
  }

  if (!existsSync(jarPath)) {
    console.error(`[MateClaw] JAR not found: ${jarPath}`)
    dialog.showErrorBox(
      'MateClaw 启动失败',
      `找不到应用程序包。\n路径: ${jarPath}\n\n请重新安装 MateClaw。`
    )
    app.quit()
    return
  }

  // Prepare environment variables — inherit current env + override
  const env = {
    ...process.env,
    // Ensure H2 database is stored in userData
    SPRING_DATASOURCE_URL: `jdbc:h2:file:${join(workingDir, 'data', 'mateclaw')};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`,
  }

  // Spawn Java process
  javaProcess = spawn(javaExec, [
    '-jar',
    jarPath,
    `--server.port=${BACKEND_PORT}`,
    '--mateclaw.setup.await-language-selection=true',
  ], {
    cwd: workingDir,
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  javaProcess.stdout?.on('data', (data: Buffer) => {
    const line = data.toString().trim()
    if (line) console.log(`[Java] ${line}`)
  })

  javaProcess.stderr?.on('data', (data: Buffer) => {
    const line = data.toString().trim()
    if (line) console.error(`[Java:ERR] ${line}`)
  })

  javaProcess.on('error', (err: Error) => {
    console.error('[MateClaw] Failed to start Java process:', err)
    sendToWindow('backend:crashed', `Java 进程启动失败: ${err.message}`)
  })

  javaProcess.on('exit', (code: number | null, signal: string | null) => {
    console.log(`[MateClaw] Java process exited: code=${code}, signal=${signal}`)
    javaProcess = null

    if (!isQuitting) {
      sendToWindow('backend:crashed', `Java 进程意外退出 (code: ${code})`)
    }
  })

  // Start health check polling
  pollBackendReady()
}

function pollBackendReady(): void {
  const startTime = Date.now()

  sendToWindow('backend:status', 'starting')

  let resolved = false

  const check = () => {
    if (isQuitting || resolved) return

    const elapsed = Date.now() - startTime
    // Remote connections fail fast (server should already be up); the embedded
    // JVM gets the full window to boot.
    const timeout = connectionMode === 'remote' ? 15_000 : HEALTH_CHECK_TIMEOUT
    if (elapsed > timeout) {
      console.error('[MateClaw] Backend health check timed out')
      sendToWindow('backend:status', 'timeout')
      if (connectionMode !== 'remote') {
        dialog.showErrorBox(
          'MateClaw 启动超时',
          '后端服务启动超时，请检查日志或重启应用。'
        )
      }
      return
    }

    const isHttps = BACKEND_URL.startsWith('https:')
    const client = isHttps ? https : http
    const reqOpts = isHttps ? { agent: insecureAgent } : {}
    const req = client.get(`${BACKEND_URL}/`, reqOpts, (res) => {
      if (resolved) return
      resolved = true

      // Consume response data to free up the socket
      res.resume()

      backendReady = true
      console.log(`[MateClaw] Backend ready (${elapsed}ms, status: ${res.statusCode})`)
      sendToWindow('backend:status', 'ready')

      // Do NOT auto-navigate — let the splash screen handle it
      // after language selection / setup check completes.
    })

    req.on('error', () => {
      if (resolved) return
      // Server not ready yet, retry
      setTimeout(check, HEALTH_CHECK_INTERVAL)
    })

    req.setTimeout(3000, () => {
      req.destroy()
      if (resolved) return
      setTimeout(check, HEALTH_CHECK_INTERVAL)
    })
  }

  check()
}

async function stopJavaBackend(): Promise<void> {
  if (!javaProcess) return

  console.log('[MateClaw] Stopping Java backend...')

  return new Promise<void>((resolve) => {
    const timeout = setTimeout(() => {
      console.log('[MateClaw] Force killing Java process')
      javaProcess?.kill('SIGKILL')
      resolve()
    }, 10_000) // 10s grace period

    javaProcess!.on('exit', () => {
      clearTimeout(timeout)
      console.log('[MateClaw] Java process stopped')
      resolve()
    })

    // Try graceful shutdown first
    if (process.platform === 'win32') {
      // On Windows, spawn taskkill for graceful stop
      spawn('taskkill', ['/pid', String(javaProcess!.pid), '/t'])
    } else {
      javaProcess!.kill('SIGTERM')
    }
  })
}

// ─── Connection Orchestration ────────────────────────────────────────────────

// Decide how to reach the backend on launch based on saved configuration.
async function bootConnection(): Promise<void> {
  if (forceChooser) {
    sendToWindow('backend:status', 'choose')
    return
  }

  const cfg = loadConfig()
  if (cfg.mode === 'local') {
    connectionMode = 'local'
    await startJavaBackend()
  } else if (cfg.mode === 'remote' && cfg.remoteUrl) {
    startRemoteConnection(cfg.remoteUrl)
  } else {
    // First run: the renderer queries getConnectionConfig() and shows the chooser.
    connectionMode = null
    sendToWindow('backend:status', 'choose')
  }
}

// Point the shell at a remote server and start health polling against it.
function startRemoteConnection(url: string): void {
  const normalized = normalizeServerUrl(url)
  if (!normalized) {
    sendToWindow('backend:crashed', `无效的服务器地址: ${url}`)
    return
  }
  connectionMode = 'remote'
  backendReady = false
  BACKEND_URL = normalized
  console.log(`[MateClaw] Remote mode → ${BACKEND_URL}`)
  pollBackendReady()
}

// Probe an arbitrary server root with a short timeout. Used by the connection
// chooser's "Test" button before the user commits to a server.
function probeServer(
  url: string,
  timeoutMs = 6000
): Promise<{ ok: boolean; status?: number; error?: string }> {
  return new Promise((resolve) => {
    const normalized = normalizeServerUrl(url)
    if (!normalized) {
      resolve({ ok: false, error: 'invalid-url' })
      return
    }

    const isHttps = normalized.startsWith('https:')
    const client = isHttps ? https : http
    const reqOpts = isHttps ? { agent: insecureAgent } : {}
    const req = client.get(`${normalized}/`, reqOpts, (res) => {
      res.resume()
      const status = res.statusCode ?? 0
      // Any non-5xx response means the server is reachable and serving.
      resolve({ ok: status > 0 && status < 500, status })
    })

    req.on('error', (err) => resolve({ ok: false, error: err.message }))
    req.setTimeout(timeoutMs, () => {
      req.destroy()
      resolve({ ok: false, error: 'timeout' })
    })
  })
}

// Reload the splash and force the connection chooser (menu "Switch Server").
function goToConnectionChooser(): void {
  forceChooser = true
  backendReady = false
  loadSplash()
}

function loadSplash(): void {
  if (!mainWindow || mainWindow.isDestroyed()) return
  if (process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL)
  } else {
    mainWindow.loadFile(join(__dirname, '../../dist/index.html'))
  }
}

// ─── Window Management ───────────────────────────────────────────────────────

function createWindow(): void {
  const preloadPath = join(__dirname, '../preload/index.js')

  mainWindow = new BrowserWindow({
    width: WINDOW_WIDTH,
    height: WINDOW_HEIGHT,
    minWidth: 900,
    minHeight: 600,
    title: 'MateClaw',
    icon: join(__dirname, '../../build/icon.png'),
    webPreferences: {
      preload: preloadPath,
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: true,
    },
    show: false,
    backgroundColor: '#f5f5f5',
  })

  // Show when ready to prevent visual flash
  mainWindow.once('ready-to-show', () => {
    mainWindow?.show()
  })

  // Open DevTools in dev mode for debugging
  if (!app.isPackaged) {
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  }

  // Log renderer console messages to main process
  mainWindow.webContents.on('console-message', (_event, level, message, line, sourceId) => {
    const levelStr = ['DEBUG', 'INFO', 'WARN', 'ERROR'][level] || 'LOG'
    console.log(`[Renderer:${levelStr}] ${message} (${sourceId}:${line})`)
  })

  // Load splash screen first
  loadSplash()

  // Open external links in system browser, but allow WeCom auth popup in-app
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    // WeCom SDK needs in-app popup for postMessage auth callback
    if (url.includes('work.weixin.qq.com')) {
      return {
        action: 'allow',
        overrideBrowserWindowOptions: {
          width: 500,
          height: 620,
          title: '企业微信授权',
          webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
          },
        },
      }
    }
    if (url.startsWith('http')) {
      shell.openExternal(url)
    }
    return { action: 'deny' }
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

function sendToWindow(channel: string, data: unknown): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, data)
  }
}

// ─── Auto Updater ───────────────────────────────────────────────────────────

function setupAutoUpdater(): void {
  if (!app.isPackaged) {
    // In dev mode, electron-updater can still work if dev-app-update.yml exists
    // at the project root. It overrides the publish config from electron-builder.json.
    const devUpdateConfig = resolve(__dirname, '../../dev-app-update.yml')
    if (!existsSync(devUpdateConfig)) {
      console.log('[MateClaw] Skipping auto-updater in dev mode (no dev-app-update.yml)')
      return
    }
    console.log('[MateClaw] Dev mode: using dev-app-update.yml for updater')
    autoUpdater.forceDevUpdateConfig = true
  }

  autoUpdater.autoDownload = false
  autoUpdater.autoInstallOnAppQuit = false

  autoUpdater.on('checking-for-update', () => {
    updaterState = { status: 'checking' }
    sendToWindow('updater:state', updaterState)
    console.log('[MateClaw] Checking for update...')
  })

  autoUpdater.on('update-available', (info: UpdateInfo) => {
    updaterState = {
      status: 'available',
      version: info.version,
      releaseNotes: typeof info.releaseNotes === 'string' ? info.releaseNotes : undefined,
    }
    sendToWindow('updater:state', updaterState)
    console.log(`[MateClaw] Update available: ${info.version}`)
  })

  autoUpdater.on('update-not-available', (info: UpdateInfo) => {
    updaterState = { status: 'not-available', version: info.version }
    sendToWindow('updater:state', updaterState)
    console.log('[MateClaw] No update available')
  })

  autoUpdater.on('download-progress', (progress: ProgressInfo) => {
    updaterState = {
      ...updaterState,
      status: 'downloading',
      progress: {
        percent: progress.percent,
        bytesPerSecond: progress.bytesPerSecond,
        transferred: progress.transferred,
        total: progress.total,
      },
    }
    sendToWindow('updater:state', updaterState)
  })

  autoUpdater.on('update-downloaded', (info: UpdateInfo) => {
    updaterState = { status: 'downloaded', version: info.version }
    sendToWindow('updater:state', updaterState)
    console.log(`[MateClaw] Update downloaded: ${info.version}`)
  })

  autoUpdater.on('error', (err: Error) => {
    updaterState = { status: 'error', error: err.message }
    sendToWindow('updater:state', updaterState)
    console.error('[MateClaw] Auto-updater error:', err.message)

    setTimeout(() => {
      if (updaterState.status === 'error') {
        updaterState = { status: 'idle' }
        sendToWindow('updater:state', updaterState)
      }
    }, 10_000)
  })

  // Check for updates after a short delay to avoid blocking startup
  setTimeout(() => {
    autoUpdater.checkForUpdates().catch((err) => {
      console.error('[MateClaw] Update check failed:', err.message)
    })
  }, 3000)
}

// ─── IPC Handlers ────────────────────────────────────────────────────────────

function registerIpcHandlers(): void {
  ipcMain.handle('app:get-platform', () => process.platform)

  ipcMain.handle('app:get-version', () => app.getVersion())

  ipcMain.handle('app:get-backend-url', () => BACKEND_URL)

  ipcMain.handle('app:is-backend-ready', () => backendReady)

  ipcMain.handle('app:open-external', (_event, url: string) => {
    shell.openExternal(url)
  })

  ipcMain.handle('app:get-user-data-path', () => app.getPath('userData'))

  ipcMain.handle('app:restart-backend', async () => {
    backendReady = false
    sendToWindow('backend:status', 'restarting')
    if (connectionMode === 'remote') {
      // Nothing to restart locally — just re-probe the remote server.
      startRemoteConnection(BACKEND_URL)
      return
    }
    await stopJavaBackend()
    await startJavaBackend()
  })

  // ── Connection management IPC ──

  ipcMain.handle('connection:get-config', () => {
    const cfg = loadConfig()
    return {
      mode: cfg.mode,
      remoteUrl: cfg.remoteUrl,
      servers: cfg.servers,
      // The renderer shows the chooser on first run or when "Switch Server" forced it.
      forceChoose: forceChooser,
    }
  })

  ipcMain.handle('connection:test', async (_event, url: string) => {
    return probeServer(url)
  })

  ipcMain.handle('connection:use-local', async () => {
    forceChooser = false
    saveConfig({ mode: 'local' })
    connectionMode = 'local'
    backendReady = false
    if (!javaProcess) {
      await startJavaBackend()
    } else {
      // Already running (e.g. switched away and back) — just re-check health.
      pollBackendReady()
    }
  })

  ipcMain.handle('connection:use-remote', async (_event, url: string) => {
    const normalized = normalizeServerUrl(url)
    if (!normalized) return { ok: false, error: 'invalid-url' }

    forceChooser = false
    // A local JVM is pointless in remote mode — free its resources.
    if (javaProcess) {
      await stopJavaBackend()
    }
    saveConfig({ mode: 'remote', remoteUrl: normalized })
    recordServer(normalized)
    startRemoteConnection(normalized)
    return { ok: true }
  })

  ipcMain.handle('connection:switch-server', () => {
    goToConnectionChooser()
  })

  ipcMain.handle('app:navigate-to-app', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      console.log('[MateClaw] Navigating to main application')
      mainWindow.loadURL(BACKEND_URL)
    }
  })

  // ── Auto Updater IPC ──

  ipcMain.handle('updater:get-state', () => updaterState)

  ipcMain.handle('updater:check', async () => {
    if (!app.isPackaged) return { status: 'not-available' } as UpdaterState
    try {
      await autoUpdater.checkForUpdates()
    } catch (err: any) {
      console.error('[MateClaw] Manual update check failed:', err.message)
    }
    return updaterState
  })

  ipcMain.handle('updater:download', async () => {
    if (updaterState.status !== 'available') return
    try {
      await autoUpdater.downloadUpdate()
    } catch (err: any) {
      console.error('[MateClaw] Download failed:', err.message)
    }
  })

  ipcMain.handle('updater:install', async () => {
    if (updaterState.status !== 'downloaded') return

    console.log('[MateClaw] Installing update, stopping backend first...')
    isUpdating = true

    try {
      await stopJavaBackend()
    } catch (err) {
      console.error('[MateClaw] Error stopping backend before update:', err)
    }

    autoUpdater.quitAndInstall(false, true)
  })
}

// ─── App Lifecycle ───────────────────────────────────────────────────────────

// Prevent multiple instances
const gotTheLock = app.requestSingleInstanceLock()
if (!gotTheLock) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.focus()
    }
  })
}

// ─── Application Menu ────────────────────────────────────────────────────────

function showAboutDialog(): void {
  const iconPath = join(__dirname, '../../build/icon.png')
  const icon = existsSync(iconPath) ? nativeImage.createFromPath(iconPath) : undefined

  dialog.showMessageBox({
    type: 'info',
    title: 'About MateClaw',
    message: 'MateClaw',
    detail: [
      `Version: ${app.getVersion()}`,
      '',
      'Your intelligent AI assistant powered by Spring AI Alibaba.',
      '',
      `Copyright © 2026 MateClaw Team`,
    ].join('\n'),
    buttons: ['OK'],
    icon,
  })
}

async function menuCheckForUpdates(): Promise<void> {
  if (!app.isPackaged) {
    dialog.showMessageBox({ type: 'info', message: 'Update check is not available in dev mode.' })
    return
  }

  try {
    const result = await autoUpdater.checkForUpdates()
    if (!result || !result.updateInfo || result.updateInfo.version === app.getVersion()) {
      dialog.showMessageBox({
        type: 'info',
        title: 'Check for Updates',
        message: 'You are up to date!',
        detail: `MateClaw ${app.getVersion()} is the latest version.`,
      })
    }
    // If update is available, the existing updater:state IPC events will notify the renderer
  } catch (err: any) {
    dialog.showMessageBox({
      type: 'error',
      title: 'Update Error',
      message: 'Failed to check for updates',
      detail: err.message || 'Please check your network connection and try again.',
    })
  }
}

function setupApplicationMenu(): void {
  const isMac = process.platform === 'darwin'

  const template: Electron.MenuItemConstructorOptions[] = []

  // ── macOS App Menu ──
  if (isMac) {
    template.push({
      label: app.name,
      submenu: [
        { label: `About ${app.name}`, click: showAboutDialog },
        { label: 'Check for Updates...', click: menuCheckForUpdates },
        { type: 'separator' },
        { label: 'Switch Server…', click: goToConnectionChooser },
        { type: 'separator' },
        { role: 'hide' },
        { role: 'hideOthers' },
        { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' },
      ],
    })
  }

  // ── File Menu (Windows/Linux only) ──
  if (!isMac) {
    template.push({
      label: 'File',
      submenu: [
        { label: 'Switch Server…', click: goToConnectionChooser },
        { type: 'separator' },
        { role: 'quit', label: 'Exit' },
      ],
    })
  }

  // ── Edit Menu ──
  template.push({
    label: 'Edit',
    submenu: [
      { role: 'undo' },
      { role: 'redo' },
      { type: 'separator' },
      { role: 'cut' },
      { role: 'copy' },
      { role: 'paste' },
      { role: 'selectAll' },
    ],
  })

  // ── View Menu ──
  template.push({
    label: 'View',
    submenu: [
      { role: 'reload' },
      { role: 'forceReload' },
      { role: 'toggleDevTools' },
      { type: 'separator' },
      { role: 'resetZoom' },
      { role: 'zoomIn' },
      { role: 'zoomOut' },
      { type: 'separator' },
      { role: 'togglefullscreen' },
    ],
  })

  // ── Window Menu (macOS) ──
  if (isMac) {
    template.push({
      label: 'Window',
      submenu: [
        { role: 'minimize' },
        { role: 'zoom' },
        { type: 'separator' },
        { role: 'front' },
      ],
    })
  }

  // ── Help Menu ──
  template.push({
    label: 'Help',
    submenu: [
      ...(!isMac ? [
        { label: 'Check for Updates...', click: menuCheckForUpdates },
        { type: 'separator' as const },
      ] : []),
      {
        label: 'GitHub Repository',
        click: () => shell.openExternal('https://github.com/matevip/mateclaw'),
      },
      {
        label: 'Report Issue',
        click: () => shell.openExternal('https://github.com/matevip/mateclaw/issues'),
      },
      ...(!isMac ? [
        { type: 'separator' as const },
        { label: `About ${app.name}`, click: showAboutDialog },
      ] : []),
    ],
  })

  const menu = Menu.buildFromTemplate(template)
  Menu.setApplicationMenu(menu)
}

// Allow the user to accept a self-signed / untrusted certificate for the remote
// server they explicitly chose to connect to (common on enterprise intranets).
app.on('certificate-error', (event, _webContents, url, _error, _certificate, callback) => {
  let host = ''
  try {
    host = new URL(url).host
  } catch {
    callback(false)
    return
  }

  if (trustedCertHosts.has(host)) {
    event.preventDefault()
    callback(true)
    return
  }

  // Only prompt for the server the user is actively connecting to.
  if (connectionMode !== 'remote' || !BACKEND_URL.includes(host)) {
    callback(false)
    return
  }

  const choice = dialog.showMessageBoxSync({
    type: 'warning',
    title: '证书不受信任',
    message: `服务器 ${host} 使用了不受信任的证书`,
    detail: '该服务器的 TLS 证书无法验证（可能是自签名证书）。仅在你信任此服务器时继续。',
    buttons: ['取消', '信任并继续'],
    defaultId: 0,
    cancelId: 0,
  })

  if (choice === 1) {
    trustedCertHosts.add(host)
    event.preventDefault()
    callback(true)
  } else {
    callback(false)
  }
})

app.whenReady().then(() => {
  setupApplicationMenu()
  registerIpcHandlers()
  createWindow()
  bootConnection()
  setupAutoUpdater()
})

app.on('window-all-closed', () => {
  // On macOS, apps typically stay active until Cmd+Q
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('activate', () => {
  // On macOS, re-create window when dock icon is clicked
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow()
    if (!backendReady) {
      bootConnection()
    }
  }
})

app.on('before-quit', async (event) => {
  if (isQuitting) return

  // During update install, backend is already stopped by updater:install handler
  if (isUpdating) {
    isQuitting = true
    return
  }

  isQuitting = true
  event.preventDefault()

  try {
    await stopJavaBackend()
  } catch (err) {
    console.error('[MateClaw] Error stopping backend:', err)
  } finally {
    app.exit(0)
  }
})
