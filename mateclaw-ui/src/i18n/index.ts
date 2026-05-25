import { createI18n } from 'vue-i18n'
import { compileToFunction, registerMessageCompiler } from '@intlify/core-base'
import { ref } from 'vue'
import { settingsApi } from '@/api'

export type AppLocale = 'zh-CN' | 'en-US'

const STORAGE_KEY = 'mateclaw_locale'
const DEFAULT_LOCALE: AppLocale = 'zh-CN'

export const currentLocale = ref<AppLocale>(DEFAULT_LOCALE)

// Replace vue-i18n's default message compiler with a safety wrapper. The
// default compiler throws on parse errors in production builds, which
// caused a regression: workflow step prompts containing Pebble syntax
// (`Hello {{ inputs.payload }}`) leaked into i18n's parser through one of
// vue-i18n's internal lookups and aborted the property panel render.
// Catching the throw here keeps the panel alive — the worst case is that
// a malformed message renders as its literal text instead of the
// interpolated form, which is the same fallback dev mode already has.
const safeMessageCompiler = ((message: any, context: any) => {
  try {
    return compileToFunction(message, context)
  } catch {
    const literal = typeof message === 'string' ? message : String(message)
    return () => literal
  }
}) as typeof compileToFunction
registerMessageCompiler(safeMessageCompiler)

export const i18n = createI18n({
  legacy: false,
  locale: DEFAULT_LOCALE,
  fallbackLocale: DEFAULT_LOCALE,
  messages: {} as Record<AppLocale, any>,
  messageCompiler: safeMessageCompiler,
})

const loadedLocales = new Set<AppLocale>()

// Each locale dictionary is ~78KB. Splitting them into their own chunks keeps
// the entry bundle ~150KB lighter — only the active locale is fetched on cold
// start, the other one only when the user switches language.
async function loadLocaleMessages(locale: AppLocale) {
  if (loadedLocales.has(locale)) return
  const messages = locale === 'zh-CN'
    ? (await import('./locales/zh-CN')).default
    : (await import('./locales/en-US')).default
  i18n.global.setLocaleMessage(locale, messages)
  loadedLocales.add(locale)
}

function normalizeLocale(locale?: string | null): AppLocale {
  if (locale === 'en' || locale === 'en-US') {
    return 'en-US'
  }
  return 'zh-CN'
}

export async function applyLocale(locale?: string | null) {
  const normalized = normalizeLocale(locale)
  // Must finish loading messages before flipping currentLocale, otherwise the
  // first render after a switch would show the i18n keys verbatim.
  await loadLocaleMessages(normalized)
  currentLocale.value = normalized
  i18n.global.locale.value = normalized
  localStorage.setItem(STORAGE_KEY, normalized)
  return normalized
}

export async function initializeLocale() {
  try {
    const res: any = await settingsApi.getLanguage()
    return await applyLocale(res.data)
  } catch {
    return await applyLocale(localStorage.getItem(STORAGE_KEY))
  }
}
