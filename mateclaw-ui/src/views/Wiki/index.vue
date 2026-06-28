<template>
  <div class="mc-page-shell wiki-shell">
    <div class="mc-page-frame wiki-frame">
      <div class="mc-page-inner wiki-inner">
        <WikiFailureCenter
          v-if="!store.currentKB && isAdmin"
          @open="openFromFailureCenter"
        />
        <WikiLibrary
          v-if="!store.currentKB"
          :kbs="store.knowledgeBases"
          :kb-stats="kbStats"
          :loading="store.loading"
          @open="enterKB"
          @manage="enterKBManage"
          @create="showCreateKB = true"
          @delete="handleDeleteKB"
        />
        <WikiWorkspace
          v-else
          :kb="store.currentKB"
        />
      </div>
    </div>

    <div v-if="showCreateKB" class="modal-overlay" @click.self="showCreateKB = false">
      <div class="modal-content">
        <h3 class="modal-title">{{ t('wiki.createKB') }}</h3>
        <div class="form-group">
          <label>{{ t('wiki.kbName') }}</label>
          <input v-model="newKBName" type="text" class="form-input" :placeholder="t('wiki.kbNamePlaceholder')" autofocus />
        </div>
        <div class="form-group">
          <label>{{ t('wiki.kbDescription') }}</label>
          <textarea v-model="newKBDesc" class="form-input" rows="3" :placeholder="t('wiki.kbDescPlaceholder')"></textarea>
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" @click="showCreateKB = false">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="handleCreateKB" :disabled="!newKBName.trim()">{{ t('common.create') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useWikiStore, type WikiKB } from '@/stores/useWikiStore'
import { wikiApi } from '@/api/index'
import { mcConfirm } from '@/components/common/useConfirm'
import { mcToast } from '@/composables/useMcToast'
import WikiLibrary from './components/WikiLibrary.vue'
import WikiWorkspace from './components/WikiWorkspace.vue'
import WikiFailureCenter from './components/WikiFailureCenter.vue'

const route = useRoute()
const router = useRouter()

const { t } = useI18n()
const store = useWikiStore()

// The cross-KB failure center spans every workspace, so it is admin-only —
// mirrors the gate on the backing endpoint.
const isAdmin = computed(() => (localStorage.getItem('role') || 'user') === 'admin')

interface KBStats {
  pageCount: number
  enrichedPageCount: number
  rawCount: number
  failedJobCount: number
  runningJobCount: number
}
const kbStats = reactive<Record<number, KBStats>>({})

async function fetchKBStats() {
  for (const kb of store.knowledgeBases) {
    try {
      const res: any = await wikiApi.getKBStats(kb.id)
      kbStats[kb.id] = res.data || res
    } catch { /* ignore */ }
  }
}

watch(() => store.knowledgeBases.length, () => {
  if (store.knowledgeBases.length > 0) fetchKBStats()
})

const showCreateKB = ref(false)
const newKBName = ref('')
const newKBDesc = ref('')

async function enterKB(id: number) {
  await store.selectKB(id, 'browse')
}

// The failure center emits a Snowflake kbId as a string — keep it a string end
// to end (snowflake-precision-ok) and let the store cast satisfy its signature.
async function openFromFailureCenter(kbId: string) {
  await store.selectKB(kbId as unknown as number, 'browse')
}

async function enterKBManage(id: number) {
  await store.selectKB(id, 'manage')
}

async function handleCreateKB() {
  await store.createKB({ name: newKBName.value, description: newKBDesc.value })
  showCreateKB.value = false
  newKBName.value = ''
  newKBDesc.value = ''
}

async function handleDeleteKB(kb: WikiKB) {
  const ok = await mcConfirm({
    title: t('wiki.library.deleteKB'),
    message: t('wiki.library.deleteKBConfirm', {
      name: kb.name,
      raws: kb.rawCount ?? 0,
      pages: kb.pageCount ?? 0,
    }),
    confirmText: t('common.delete'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await store.deleteKB(kb.id)
    mcToast.success(t('wiki.library.deleteKBSuccess', { name: kb.name }))
  } catch (e: any) {
    mcToast.error(e?.response?.data?.message || t('wiki.library.deleteKBFailed'))
  }
}

// ---- URL ↔ store sync --------------------------------------------------
// The open KB (and the open page within it) live in the route so a manual
// refresh restores exactly where you were instead of dropping back to the
// library list. Canonical shape: /wiki/:kbId?view=manage&slug=<page>.
//
// The store stays the source of truth for the rendered view; we mirror it
// into the URL (syncUrlFromStore) and seed it from the URL on first load and
// on browser back/forward (syncStoreFromUrl). A single `syncing` latch keeps
// the two watchers from ping-ponging.
//
// **Snowflake precision** (per CLAUDE.md): kbId is a 19-digit Snowflake that
// exceeds Number.MAX_SAFE_INTEGER. NEVER Number()/parseInt() it — that
// truncates the id into a silent "KB not found". It stays a string and is
// passed opaquely to the store / API; the `as unknown as number` casts only
// satisfy the store's numeric type signature — the runtime value is the string.
let syncing = false

function routeKbId(): string | null {
  // Accept the canonical path param and the legacy ?kbId= query that the
  // global wikilink delegator still pushes (App.vue → useGlobalWikilinkClick).
  const fromParam = route.params.kbId
  if (typeof fromParam === 'string' && fromParam) return fromParam
  const fromQuery = route.query.kbId
  if (typeof fromQuery === 'string' && fromQuery) return fromQuery
  return null
}

async function syncStoreFromUrl() {
  if (syncing) return
  syncing = true
  try {
    const kbId = routeKbId()
    const mode = route.query.view === 'manage' ? 'manage' : 'browse'
    const slug = typeof route.query.slug === 'string' ? route.query.slug : ''
    if (kbId) {
      if (String(store.currentKB?.id ?? '') !== kbId) {
        // snowflake-precision-ok: kbId is the raw route string, never coerced.
        await store.selectKB(kbId as unknown as number, mode)
      } else if (store.workspaceMode !== mode) {
        store.setWorkspaceMode(mode)
      }
      if (slug && store.currentPage?.slug !== slug) {
        // snowflake-precision-ok: kbId stays a string end to end.
        try { await store.loadPage(kbId as unknown as number, slug) }
        catch (e) { console.warn('[Wiki] open page from url failed', e) }
      }
    } else if (store.currentKB) {
      store.backToLibrary()
    }
  } finally {
    syncing = false
  }
}

function syncUrlFromStore() {
  if (syncing) return
  const kb = store.currentKB
  const params: Record<string, string> = kb ? { kbId: String(kb.id) } : {}
  const query: Record<string, string> = {}
  if (kb) {
    if (store.workspaceMode === 'manage') query.view = 'manage'
    if (store.currentPage?.slug) query.slug = store.currentPage.slug
  }
  const sameKb = String(route.params.kbId ?? '') === (params.kbId ?? '')
  const sameView = (route.query.view ?? '') === (query.view ?? '')
  const sameSlug = (route.query.slug ?? '') === (query.slug ?? '')
  // Collapse the legacy ?kbId= query into the canonical path-param form.
  const legacyQuery = route.query.kbId != null
  if (sameKb && sameView && sameSlug && !legacyQuery) return
  router.replace({ name: 'Wiki', params, query })
}

watch(
  () => [store.currentKB?.id, store.workspaceMode, store.currentPage?.slug],
  syncUrlFromStore,
)

watch(() => route.fullPath, () => { syncStoreFromUrl() })

onMounted(async () => {
  await store.fetchKnowledgeBases()
  await syncStoreFromUrl()
  syncUrlFromStore()
})
</script>

<style scoped>
.wiki-shell { background: transparent; height: 100%; min-height: 0; overflow: hidden; }
.wiki-frame { height: min(calc(100vh - 28px), 100%); min-height: 0; overflow: hidden; }
.wiki-inner { display: flex; flex-direction: column; height: 100%; min-height: 0; overflow-y: auto; }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); transition: opacity 0.15s; }
.btn-primary:hover { opacity: 0.9; }
.btn-primary:disabled { background: var(--mc-border); box-shadow: none; cursor: not-allowed; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; transition: background 0.15s; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); backdrop-filter: blur(4px); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal-content { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 18px; width: 100%; max-width: 520px; padding: 24px; box-shadow: 0 24px 64px rgba(0,0,0,0.18); }
.modal-title { font-size: 17px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 18px; }
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 12px; font-weight: 600; margin-bottom: 6px; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.04em; }
.form-input { width: 100%; padding: 9px 12px; border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; background: var(--mc-bg-muted); color: var(--mc-text-primary); outline: none; font-family: inherit; box-sizing: border-box; transition: border-color 0.15s; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }

@media (max-width: 980px) {
  .wiki-frame { height: 100%; min-height: calc(100vh - 28px); }
}
</style>
