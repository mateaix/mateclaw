// Bridge API exposed by the desktop shell's preload script. Present only when
// the SPA runs inside the desktop app; always access via optional chaining.
export interface LocalToolsConfig {
  // Master switch for the local file/shell tool proxy.
  enabled: boolean
  // Directories the agent may touch through the local tools tunnel.
  allowedDirs: string[]
  // Policy when allowedDirs is empty: true = deny all, false = allow all.
  failClosed: boolean
}

export interface LocalToolsState extends LocalToolsConfig {
  // Whether the tunnel to the backend is currently connected.
  connected: boolean
}

export interface MateClawDesktopAPI {
  getPlatform: () => Promise<string>
  getVersion: () => Promise<string>
  openExternal: (url: string) => Promise<void>

  getLocalToolsConfig: () => Promise<LocalToolsState>
  setLocalToolsConfig: (patch: Partial<LocalToolsConfig>) => Promise<LocalToolsConfig>
  // Opens a native folder picker; `added` is null when the user cancels.
  addLocalToolsDir: () => Promise<LocalToolsConfig & { added: string | null }>
  removeLocalToolsDir: (dir: string) => Promise<LocalToolsConfig>
}

declare global {
  interface Window {
    mateClawAPI?: MateClawDesktopAPI
  }
}

export {}
