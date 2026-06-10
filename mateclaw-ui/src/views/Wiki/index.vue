<template>
  <div class="mc-page-shell wiki-shell">
    <div class="mc-page-frame wiki-frame">
      <div class="mc-page-inner wiki-inner">
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
import { ref, reactive, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useWikiStore, type WikiKB } from '@/stores/useWikiStore'
import { wikiApi } from '@/api/index'
import { mcConfirm } from '@/components/common/useConfirm'
import { mcToast } from '@/composables/useMcToast'
import WikiLibrary from './components/WikiLibrary.vue'
import WikiWorkspace from './components/WikiWorkspace.vue'

const route = useRoute()
const router = useRouter()

const { t } = useI18n()
const store = useWikiStore()

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

async function consumeQueryNavigation() {
  // Global wikilink click delegator (see App.vue) pushes us with
  // ?kbId=X&slug=Y on click. Honour both: enter the KB then surface
  // the page directly. Strips the query immediately so a manual reload
  // doesn't keep re-opening the same page.
  //
  // **Snowflake precision** (per CLAUDE.md): the kbId is a 19-digit
  // Snowflake that exceeds Number.MAX_SAFE_INTEGER. NEVER coerce via
  // Number()/parseInt() — that truncates the last 2-3 digits and turns
  // a real lookup into a silent "KB not found". Keep the string and
  // pass it through to the store; the store / api layer treats kbId as
  // an opaque token interpolated into the request URL.
  const kbIdRaw = route.query.kbId
  const slugRaw = route.query.slug
  if (typeof kbIdRaw !== 'string' || !kbIdRaw) return
  if (typeof slugRaw !== 'string' || !slugRaw) return
  // Cast to number ONLY to satisfy the store's type signature — the
  // runtime value stays a string under the hood. TypeScript can't
  // express "number-or-Snowflake-string" without widening every signature,
  // so the cast is the localised, documented escape hatch.
  // snowflake-precision-ok: kbIdRaw is the URL-encoded string from the
  // global click delegator; never passed through Number()/parseInt().
  const kbId = kbIdRaw as unknown as number
  await store.selectKB(kbId)
  try {
    await store.loadPage(kbId, slugRaw)
  } catch (e) {
    console.warn('[Wiki] auto-open page failed', e)
  }
  // Drop the query so back-button + reload behave sanely.
  router.replace({ name: 'Wiki' })
}

onMounted(async () => {
  await store.fetchKnowledgeBases()
  await consumeQueryNavigation()
})

// Re-consume the query when a click delegator navigates while we're
// already on /wiki (route.path unchanged, query changed).
watch(() => route.query, () => { consumeQueryNavigation() })
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
