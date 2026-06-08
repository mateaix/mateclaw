<template>
  <div class="guide-editor">
    <div v-if="loading" class="guide-state">…</div>

    <!-- Empty state: AGENTS.md not present or blank -->
    <div v-else-if="sections.length === 0" class="guide-empty">
      <p class="guide-empty__text">{{ t('agents.guide.empty') }}</p>
      <button class="guide-create-btn" :disabled="saving" @click="createGuide">
        {{ t('agents.guide.create') }}
      </button>
    </div>

    <!-- Section cards -->
    <div v-else class="guide-sections">
      <MemorySection
        v-for="(sec, idx) in sections"
        :key="idx"
        :section="sec"
        :save-handler="(body: string) => saveSection(idx, body)"
      />

      <div class="guide-add">
        <input
          v-model="newHeading"
          class="guide-add__input"
          :placeholder="t('agents.guide.addSectionPlaceholder')"
          @keyup.enter="addSection"
        />
        <button class="guide-add__btn" :disabled="!newHeading.trim() || saving" @click="addSection">
          + {{ t('agents.guide.addSection') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { agentContextApi } from '@/api/index'
import { mcToast } from '@/composables/useMcToast'
import MemorySection from '@/views/Memory/components/MemorySection.vue'
import type { MemorySectionData } from '@/views/Memory/components/types'

const props = defineProps<{ agentId: string | number }>()
const { t } = useI18n()

const FILE = 'AGENTS.md'
const loading = ref(true)
const saving = ref(false)
const sections = ref<MemorySectionData[]>([])
const newHeading = ref('')

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res: any = await agentContextApi.getFile(props.agentId, FILE)
    sections.value = parseSections(res?.data?.content || '')
  } catch {
    // File may not exist yet — treat as empty so the create flow shows.
    sections.value = []
  } finally {
    loading.value = false
  }
}

/** Split into `## ` sections; content before the first heading is a synthetic preamble. */
function parseSections(content: string): MemorySectionData[] {
  if (!content.trim()) return []
  const result: MemorySectionData[] = []
  for (const part of content.split(/(?=^## )/m)) {
    const match = part.match(/^## (.+)\n([\s\S]*)/)
    if (match) {
      result.push({ heading: match[1].trim(), body: match[2].trim(), userEdited: false })
    } else if (part.trim() && result.length === 0) {
      result.push({ heading: t('memory.memoryBrowser.header'), body: part.trim(), userEdited: false, synthetic: true })
    }
  }
  return result
}

/** Re-serialize the local sections back into a whole AGENTS.md document. */
function serialize(secs: MemorySectionData[]): string {
  return secs
    .map(s => (s.synthetic ? s.body.trim() : `## ${s.heading}\n${s.body.trim()}`))
    .filter(Boolean)
    .join('\n\n') + '\n'
}

async function saveSection(idx: number, body: string) {
  const next = sections.value.map((s, i) => (i === idx ? { ...s, body } : s))
  await agentContextApi.saveFile(props.agentId, FILE, serialize(next))
  mcToast.success(t('agents.guide.saved'))
  await load()
}

async function createGuide() {
  saving.value = true
  try {
    await agentContextApi.saveFile(props.agentId, FILE, t('agents.guide.scaffold'))
    mcToast.success(t('agents.guide.saved'))
    await load()
  } catch (e: any) {
    mcToast.error(e?.message || 'Save failed')
  } finally {
    saving.value = false
  }
}

async function addSection() {
  const heading = newHeading.value.trim()
  if (!heading) return
  saving.value = true
  try {
    const next = [...sections.value, { heading, body: '', userEdited: false }]
    await agentContextApi.saveFile(props.agentId, FILE, serialize(next))
    newHeading.value = ''
    await load()
  } catch (e: any) {
    mcToast.error(e?.message || 'Save failed')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.guide-editor { margin-top: 8px; }
.guide-state { font-size: 13px; color: var(--mc-text-tertiary); padding: 8px 0; }
.guide-empty {
  display: flex; flex-direction: column; align-items: flex-start; gap: 10px;
  padding: 14px 16px; border-radius: 12px; background: var(--mc-bg-sunken);
}
.guide-empty__text { margin: 0; font-size: 13px; color: var(--mc-text-secondary); }
.guide-create-btn {
  padding: 6px 14px; border-radius: 8px; font-size: 12px; font-weight: 500;
  background: var(--mc-primary); color: #fff; border: 1px solid var(--mc-primary);
  cursor: pointer; transition: all 0.12s;
}
.guide-create-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.guide-sections { display: flex; flex-direction: column; gap: 10px; }
.guide-add { display: flex; gap: 8px; align-items: center; }
.guide-add__input {
  flex: 1; padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px;
  background: var(--mc-bg-elevated); font-size: 13px; color: var(--mc-text-primary); outline: none;
}
.guide-add__input:focus { border-color: var(--mc-primary); }
.guide-add__btn {
  padding: 8px 14px; border-radius: 8px; font-size: 12px; font-weight: 500;
  background: transparent; color: var(--mc-text-secondary); border: 1px solid var(--mc-border);
  cursor: pointer; white-space: nowrap; transition: all 0.12s;
}
.guide-add__btn:disabled { opacity: 0.4; cursor: not-allowed; }
.guide-add__btn:not(:disabled):hover { border-color: var(--mc-primary); color: var(--mc-primary); }
</style>
