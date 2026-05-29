import { defineConfig } from 'vitest/config'
import path from 'node:path'

// Two test conventions coexist in this repo:
//   - `test/**/*.test.ts` — pre-existing files using the Node `node:test`
//     runner (run via `node --test test/<file>.test.ts`).
//   - `src/**/__tests__/*.test.ts` — vitest tests for new code.
//
// Scope vitest to `src/**` so it never picks up the node:test files (which
// don't export describe/it/expect and would otherwise fail discovery).
export default defineConfig({
  test: {
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    environment: 'happy-dom',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
})
