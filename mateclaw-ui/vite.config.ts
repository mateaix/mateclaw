import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { visualizer } from 'rollup-plugin-visualizer'
import Components from 'unplugin-vue-components/vite'
import { resolve } from 'path'

// Sub-components that Element Plus re-exports from a parent package rather than
// shipping under their own es/components/<name> directory.
const EP_AGGREGATE: Record<string, string> = {
  ElDropdownItem: 'dropdown',
  ElDropdownMenu: 'dropdown',
  ElOption: 'select',
  ElOptionGroup: 'select',
  ElCheckboxButton: 'checkbox',
  ElCheckboxGroup: 'checkbox',
  ElRadioButton: 'radio',
  ElRadioGroup: 'radio',
  ElCollapseItem: 'collapse',
  ElBreadcrumbItem: 'breadcrumb',
  ElStep: 'steps',
  ElTabPane: 'tabs',
  ElTimelineItem: 'timeline',
  ElMenuItem: 'menu',
  ElSubMenu: 'menu',
  ElFormItem: 'form',
  ElTableColumn: 'table',
  ElCarouselItem: 'carousel',
}

// Resolve <el-*> components to their per-component subpath
// (element-plus/es/components/<dir>/index) instead of the element-plus/es
// barrel. The barrel statically imports every component, so importing any single
// name from it drags the whole library into the bundle — the stock
// ElementPlusResolver (for Element Plus 2.x) uses that barrel and relies on
// Rollup tree-shaking, which does not hold here. Per-component subpaths make the
// tree-shaking explicit. No style side effect is emitted because the full
// element-plus/dist/index.css is imported once in main.ts.
function elementPlusSubpathResolver() {
  return {
    type: 'component' as const,
    resolve(name: string) {
      if (!/^El[A-Z]/.test(name)) return
      // <el-icon-xxx> style icon usage — resolve to the icons package.
      if (/^ElIcon.+/.test(name)) {
        return { name: name.replace(/^ElIcon/, ''), from: '@element-plus/icons-vue' }
      }
      const dir = EP_AGGREGATE[name]
        ?? name.slice(2).replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()
      return { name, from: `element-plus/es/components/${dir}/index` }
    },
  }
}

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // Treat <model-viewer> as a custom Web Component (lazy-registered on
          // demand via src/utils/lazyModelViewer.ts) so Vue doesn't try to
          // resolve it as a Vue component and emit a "Failed to resolve
          // component" warning at runtime.
          isCustomElement: (tag) => tag === 'model-viewer',
        },
      },
    }),
    // On-demand Element Plus: resolve <el-*> components used in templates to
    // their per-component subpath (element-plus/es/components/*) instead of
    // registering the whole library via app.use(ElementPlus), so only the ~16
    // components the app actually uses land in the bundle. importStyle:false
    // because the full element-plus/dist/index.css is still imported once in
    // main.ts — every component's styles and the theme CSS variables stay intact
    // (no visual regression); only dead component *code* is tree-shaken out.
    // dirs:[] so local components stay explicitly imported (no auto-scan).
    // NOTE: imperative APIs (ElMessage) are imported explicitly from their
    // subpath at the 3 call sites — auto-import resolved them from the
    // `element-plus/es` barrel, which re-exports everything and defeated
    // tree-shaking entirely.
    Components({
      dirs: [],
      resolvers: [elementPlusSubpathResolver()],
      dts: 'src/types/components.d.ts',
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
        //
        // Function form (not the object map) so we can ISOLATE the CommonJS
        // interop helpers into their own tiny chunk. With the object form,
        // Rollup hoisted the shared `commonjsHelpers` module into
        // vendor-mermaid / vendor-markdown; because the eager entry chunk needs
        // that helper, it gained a *static* import of both ~1 MB vendor chunks,
        // and Vite then `modulepreload`-ed them on first paint even though
        // mermaid/markdown are only used on the (lazy) chat route. Splitting the
        // helper into its own leaf chunk lets the entry depend on a few-hundred-
        // byte module instead, so the heavy vendors stay purely on-demand.
        manualChunks(id) {
          // Isolate shared runtime helpers FIRST — before the node_modules gate.
          // The Vite preload helper (\0vite/preload-helper, exported as the
          // __vitePreload used by every lazy route import) and the CommonJS
          // interop helpers are virtual modules with no node_modules path. If one
          // lands inside a heavy vendor chunk, the entry's route-lazy imports pull
          // that whole chunk in as a static dependency and Vite preloads it. A
          // dedicated leaf chunk keeps them out of the vendors.
          if (id.includes('vite/preload-helper') || id.includes('commonjsHelpers')
              || id.includes('commonjs-dynamic-modules') || id.includes('\0commonjs')) {
            return 'vendor-runtime'
          }
          if (!id.includes('node_modules')) return undefined
          if (id.includes('monaco-editor') || id.includes('vue-monaco-editor')) return 'vendor-monaco'
          if (id.includes('@vue-flow')) return 'vendor-vue-flow'
          if (id.includes('/mermaid/')) return 'vendor-mermaid'
          if (id.includes('/echarts/') || id.includes('zrender')) return 'vendor-echarts'
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element'
          if (id.includes('/marked') || id.includes('highlight.js') || id.includes('dompurify')) {
            return 'vendor-markdown'
          }
          return undefined
        },
      },
    },
  },
})
