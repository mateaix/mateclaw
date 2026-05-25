import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { visualizer } from 'rollup-plugin-visualizer'
import { resolve } from 'path'

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // Treat <model-viewer> as a custom Web Component (registered globally
          // via @google/model-viewer in main.ts) so Vue doesn't try to resolve
          // it as a Vue component and emit a "Failed to resolve component"
          // warning at runtime.
          isCustomElement: (tag) => tag === 'model-viewer',
        },
      },
    }),
    tailwindcss(),
    // ANALYZE=1 pnpm build writes dist/stats.html. Skipped on normal builds so
    // CI artifact upload is opt-in and the Docker image build doesn't waste
    // memory generating an HTML report it never reads.
    process.env.ANALYZE && visualizer({
      filename: 'dist/stats.html',
      open: false,
      gzipSize: true,
      brotliSize: true,
    }),
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  // echarts is consumed via subpath modules (echarts/core, echarts/charts, …)
  // that only the route-lazy Dashboard and Wiki graph views import. Vite's
  // startup dep scan never sees these subpaths, so it re-optimizes the first
  // time either route loads — rolling the optimized-deps hash and 504-ing the
  // chunks the in-flight page already referenced. Pre-listing them forces a
  // single consistent pre-bundle at server startup.
  optimizeDeps: {
    include: [
      'echarts/core',
      'echarts/charts',
      'echarts/components',
      'echarts/renderers',
    ],
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:18088',
        changeOrigin: true,
        // ws:true forwards WebSocket Upgrade requests through to the backend.
        // Without it Vite serves the GET /api/v1/talk/ws as a regular HTTP
        // proxy, the Upgrade header gets dropped, and the WS handshake
        // silently fails — frontend stuck on "Connecting", backend never
        // sees the connection. TalkMode (STT) lives on this WS so the
        // whole feature is dead in dev mode without it.
        ws: true,
      },
      // Backend-served per-skill bundled assets (logos / screenshots) — see
      // WebMvcConfig.addResourceHandlers. Without this proxy entry vite
      // treats the path as a SPA route and returns index.html, which the
      // browser then tries to render as an image and shows a broken-icon
      // placeholder for any built-in skill that ships a logo.
      '/skill-assets': {
        target: 'http://localhost:18088',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: '../mateclaw-server/src/main/resources/static',
    emptyOutDir: true,
    // Cap warning so a future barrel import (see history with monaco) trips
    // the build log instead of slipping in silently.
    chunkSizeWarningLimit: 1024,
    rollupOptions: {
      output: {
        // Pin heavy vendor libs to named chunks. Two reasons:
        //   1. Cache: bumping a business chunk doesn't invalidate the
        //      monaco / vue-flow bundles, so returning visitors only
        //      re-download the few KB that actually changed.
        //   2. Rollup minify peak heap: keeping monaco out of the route
        //      chunk lets each Worker on the chunk run with a smaller
        //      working set, which is what blew up the Docker build at
        //      1.3.0 (needed --max-old-space-size=6144 as a band-aid).
        manualChunks: {
          'vendor-monaco': ['monaco-editor', '@guolao/vue-monaco-editor'],
          'vendor-vue-flow': [
            '@vue-flow/core',
            '@vue-flow/background',
            '@vue-flow/controls',
            '@vue-flow/minimap',
          ],
          'vendor-mermaid': ['mermaid'],
          'vendor-echarts': ['echarts'],
          'vendor-element': ['element-plus', '@element-plus/icons-vue'],
          'vendor-markdown': ['marked', 'marked-highlight', 'highlight.js', 'dompurify'],
        },
      },
    },
  },
})
