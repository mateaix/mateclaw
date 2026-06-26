import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import electron from 'vite-plugin-electron'
import renderer from 'vite-plugin-electron-renderer'
import { resolve } from 'path'
import { brandingPlugin } from './scripts/branding.cjs'

export default defineConfig(({ command }) => {
  const isServe = command === 'serve'
  const isBuild = command === 'build'

  // Shared branding plugin instance — applied to the renderer build as well
  // as the electron main/preload builds so brand strings are replaced
  // everywhere without touching source code.
  const brand = brandingPlugin()

  return {
    plugins: [
      vue(),
      // White-label branding: replaces "MateClaw" with the configured brand
      // name at build time. Source code stays untouched. Configure via
      // branding.config.json or BRAND_* env vars.
      brand,
      electron([
        {
          entry: 'electron/main/index.ts',
          onstart(args) {
            args.startup()
          },
          vite: {
            plugins: [brand],
            build: {
              sourcemap: isServe,
              minify: isBuild,
              outDir: 'dist-electron/main',
              rollupOptions: {
                external: ['electron', 'electron-updater'],
              },
            },
          },
        },
        {
          entry: 'electron/preload/index.ts',
          onstart(args) {
            args.reload()
          },
          vite: {
            plugins: [brand],
            build: {
              sourcemap: isServe ? 'inline' : undefined,
              minify: isBuild,
              outDir: 'dist-electron/preload',
              rollupOptions: {
                external: ['electron'],
              },
            },
          },
        },
      ]),
      renderer(),
    ],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    build: {
      outDir: 'dist',
      emptyOutDir: true,
    },
  }
})
