import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus components and imperative APIs (ElMessage, ElMessageBox, …) are
// now resolved on demand by unplugin (see vite.config.ts) instead of registering
// the whole library via app.use(ElementPlus). Only the full stylesheet is still
// imported once here so every component's styles and theme CSS variables stay
// intact (no visual change); unused component *code* is tree-shaken out.
// Icons are imported locally where used — no more global registration of all ~300.
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './assets/main.css'
import { i18n, initializeLocale } from './i18n'

// Note: the heavy <model-viewer> Web Component is no longer imported here. It is
// lazy-registered on demand (see src/utils/lazyModelViewer.ts) the first time a
// chat bubble renders a 3D (.glb) asset, keeping it out of the initial bundle.
// Vue's compiler still treats <model-viewer> as a custom element via vite.config.ts.

async function bootstrap() {
  await initializeLocale()

  const app = createApp(App)

  app.use(createPinia())
  app.use(router)
  app.use(i18n)

  // Global error handler — prevents uncaught Vue errors from causing white screens
  app.config.errorHandler = (err, instance, info) => {
    console.error('[Vue Error]', info, err)
  }

  app.mount('#app')
}

bootstrap()
