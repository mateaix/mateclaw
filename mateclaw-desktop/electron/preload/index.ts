import { contextBridge, ipcRenderer } from 'electron'

// Expose safe APIs to the renderer process (splash screen)
contextBridge.exposeInMainWorld('mateClawAPI', {
  // Platform info
  getPlatform: () => ipcRenderer.invoke('app:get-platform'),
  getVersion: () => ipcRenderer.invoke('app:get-version'),
  getBuildMode: () => ipcRenderer.invoke('app:get-build-mode'),
  getBackendUrl: () => ipcRenderer.invoke('app:get-backend-url'),
  isBackendReady: () => ipcRenderer.invoke('app:is-backend-ready'),
  getUserDataPath: () => ipcRenderer.invoke('app:get-user-data-path'),

  // Actions
  openExternal: (url: string) => ipcRenderer.invoke('app:open-external', url),
  restartBackend: () => ipcRenderer.invoke('app:restart-backend'),
  navigateToApp: () => ipcRenderer.invoke('app:navigate-to-app'),

  // Connection management
  getConnectionConfig: () => ipcRenderer.invoke('connection:get-config'),
  testConnection: (url: string) => ipcRenderer.invoke('connection:test', url),
  useLocalConnection: () => ipcRenderer.invoke('connection:use-local'),
  useRemoteConnection: (url: string) => ipcRenderer.invoke('connection:use-remote', url),
  switchServer: () => ipcRenderer.invoke('connection:switch-server'),

  // Backend status events
  onBackendStatus: (callback: (status: string) => void) => {
    const handler = (_event: Electron.IpcRendererEvent, status: string) => callback(status)
    ipcRenderer.on('backend:status', handler)
    return () => ipcRenderer.removeListener('backend:status', handler)
  },

  onBackendCrashed: (callback: (message: string) => void) => {
    const handler = (_event: Electron.IpcRendererEvent, message: string) => callback(message)
    ipcRenderer.on('backend:crashed', handler)
    return () => ipcRenderer.removeListener('backend:crashed', handler)
  },

  // Auto-updater
  getUpdaterState: () => ipcRenderer.invoke('updater:get-state'),
  checkForUpdates: () => ipcRenderer.invoke('updater:check'),
  downloadUpdate: () => ipcRenderer.invoke('updater:download'),
  installUpdate: () => ipcRenderer.invoke('updater:install'),

  onUpdaterState: (callback: (state: any) => void) => {
    const handler = (_event: Electron.IpcRendererEvent, state: any) => callback(state)
    ipcRenderer.on('updater:state', handler)
    return () => ipcRenderer.removeListener('updater:state', handler)
  },
})
