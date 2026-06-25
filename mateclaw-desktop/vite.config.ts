import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import electron from 'vite-plugin-electron'
import renderer from 'vite-plugin-electron-renderer'
import { resolve } from 'path'

export default defineConfig(({ command }) => {
  const isServe = command === 'serve'
  const isBuild = command === 'build'

  return {
    plugins: [
      vue(),
      electron([
        {
          entry: 'electron/main/index.ts',
          onstart(args) {
            args.startup()
          },
          vite: {
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
