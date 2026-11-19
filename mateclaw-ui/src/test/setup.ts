import { Storage } from 'happy-dom'

// Node 25 exposes its own localStorage even without a backing file. Vitest
// preserves existing Node globals, so it can shadow happy-dom's browser API.
// Give each test file browser storage independent of the host Node version.
for (const name of ['localStorage', 'sessionStorage'] as const) {
  Object.defineProperty(globalThis, name, {
    configurable: true,
    writable: true,
    value: new Storage(),
  })
}
