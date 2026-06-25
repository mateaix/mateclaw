/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface UpdaterState {
  status: 'idle' | 'checking' | 'available' | 'not-available' | 'downloading' | 'downloaded' | 'error'
  version?: string
  releaseNotes?: string
  progress?: { percent: number; bytesPerSecond: number; transferred: number; total: number }
  error?: string
}

interface RemoteServer {
  url: string
  name?: string
  lastUsed?: number
}

interface ConnectionConfigState {
  mode: 'local' | 'remote' | null
  remoteUrl: string
  servers: RemoteServer[]
  forceChoose: boolean
}

interface ConnectionTestResult {
  ok: boolean
  status?: number
  error?: string
}

interface MateClawAPI {
  getPlatform: () => Promise<string>
  getVersion: () => Promise<string>
  getBackendUrl: () => Promise<string>
  isBackendReady: () => Promise<boolean>
  getUserDataPath: () => Promise<string>
  openExternal: (url: string) => Promise<void>
  restartBackend: () => Promise<void>
  onBackendStatus: (callback: (status: string) => void) => () => void
  onBackendCrashed: (callback: (message: string) => void) => () => void
  navigateToApp: () => void

  // Connection management
  getConnectionConfig: () => Promise<ConnectionConfigState>
  testConnection: (url: string) => Promise<ConnectionTestResult>
  useLocalConnection: () => Promise<void>
  useRemoteConnection: (url: string) => Promise<ConnectionTestResult>
  switchServer: () => Promise<void>

  // Auto-updater
  getUpdaterState: () => Promise<UpdaterState>
  checkForUpdates: () => Promise<UpdaterState>
  downloadUpdate: () => Promise<void>
  installUpdate: () => Promise<void>
  onUpdaterState: (callback: (state: UpdaterState) => void) => () => void
}

interface Window {
  mateClawAPI: MateClawAPI
}
