import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
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

  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
  }

  app.use(createPinia())
  app.use(router)
  app.use(i18n)
  app.use(ElementPlus)

  // Global error handler — prevents uncaught Vue errors from causing white screens
  app.config.errorHandler = (err, instance, info) => {
    console.error('[Vue Error]', info, err)
  }

  app.mount('#app')
}

bootstrap()
