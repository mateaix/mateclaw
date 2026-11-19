import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { skillApi, type SkillLessonEntry } from '@/api'

export function useSkillLessons(id: Ref<string | number | undefined>, onError: (error: unknown) => void) {
  const raw = ref(''), entries = ref<SkillLessonEntry[] | null>(null), loading = ref(false)
  let revision = 0
  let disposed = false
  const reset = () => { if (disposed) return; revision++; raw.value = ''; entries.value = null; loading.value = false }
  watch(id, reset, { flush: 'sync' })
  onScopeDispose(() => { reset(); disposed = true })
  const owns = (owner: string | number) => !disposed && String(owner) === String(id.value)
  async function load() {
    const owner = id.value
    if (disposed || owner === undefined) return
    const request = ++revision
    const current = () => revision === request && owns(owner)
    loading.value = true
    try {
      const response: any = await skillApi.getLessons(owner)
      if (!current()) return
      raw.value = response?.data?.raw || ''
      entries.value = Array.isArray(response?.data?.entries) ? response.data.entries : null
    } catch (error) { if (current()) { raw.value = ''; entries.value = null; onError(error) } }
    finally { if (current()) loading.value = false }
  }
  function clearLocal(owner: string | number) {
    if (!owns(owner)) return
    reset(); entries.value = []
  }
  return { raw, entries, loading, load, reset, clearLocal, owns }
}
