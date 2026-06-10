<template>
  <button type="button" class="guide-open-btn" @click="open">
    {{ t('agents.guide.open') }}
  </button>

  <Teleport to="body">
    <div v-if="visible" class="guide-overlay" @click.self="visible = false">
      <div class="guide-modal">
        <div class="guide-modal__header">
          <h3 class="guide-modal__title">{{ t('agents.guide.summary') }}</h3>
          <button type="button" class="guide-modal__close" @click="visible = false">×</button>
        </div>

        <div class="guide-modal__body">
          <div v-if="loading" class="guide-state">…</div>

          <!-- Empty state: AGENTS.md not present or blank -->
          <div v-else-if="sections.length === 0" class="guide-empty">
            <p class="guide-empty__text">{{ t('agents.guide.empty') }}</p>
            <button type="button" class="guide-create-btn" :disabled="saving" @click="createGuide">
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
              :can-move-up="canMoveUp(idx)"
              :can-move-down="canMoveDown(idx)"
              @move-up="move(idx, -1)"
              @move-down="move(idx, 1)"
            />

            <div class="guide-add">
              <input
                v-model="newHeading"
                class="guide-add__input"
                :placeholder="t('agents.guide.addSectionPlaceholder')"
                @keyup.enter="addSection"
              />
              <button type="button" class="guide-add__btn" :disabled="!newHeading.trim() || saving" @click="addSection">
                + {{ t('agents.guide.addSection') }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { agentContextApi } from '@/api/index'
import { mcToast } from '@/composables/useMcToast'
import MemorySection from '@/views/Memory/components/MemorySection.vue'
import type { MemorySectionData } from '@/views/Memory/components/types'

const props = defineProps<{ agentId: string | number }>()
const { t } = useI18n()

const FILE = 'AGENTS.md'
const visible = ref(false)
const loading = ref(false)
const saving = ref(false)
const sections = ref<MemorySectionData[]>([])
const newHeading = ref('')

function open() {
  visible.value = true
  load()
}

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

// A synthetic preamble (content before the first `## `) is pinned to the top
// and never reorders, so the first movable section is index 1 when present.
function firstMovable(): number {
  return sections.value[0]?.synthetic ? 1 : 0
}
function canMoveUp(idx: number): boolean {
  return !sections.value[idx]?.synthetic && idx > firstMovable()
}
function canMoveDown(idx: number): boolean {
  return !sections.value[idx]?.synthetic && idx < sections.value.length - 1
}

async function move(idx: number, dir: -1 | 1) {
  const target = idx + dir
  if (target < firstMovable() || target >= sections.value.length) return
  saving.value = true
  try {
    const next = [...sections.value]
    ;[next[idx], next[target]] = [next[target], next[idx]]
    await agentContextApi.saveFile(props.agentId, FILE, serialize(next))
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
.guide-open-btn {
  flex-shrink: 0; white-space: nowrap;
  padding: 7px 14px; border-radius: 8px; font-size: 12px; font-weight: 500;
  background: var(--mc-bg-elevated); color: var(--mc-text-secondary); border: 1px solid var(--mc-border);
  cursor: pointer; transition: all 0.12s;
}
.guide-open-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); }

/* Nested modal — sits above the edit-agent modal (z-index 1000). */
.guide-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex;
  align-items: center; justify-content: center; z-index: 1100; padding: 20px;
}
.guide-modal {
  background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px;
  width: 100%; max-width: 640px; max-height: 85vh; display: flex; flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,0.15);
}
.guide-modal__header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px; border-bottom: 1px solid var(--mc-border-light);
}
.guide-modal__title { margin: 0; font-size: 15px; font-weight: 600; color: var(--mc-text-primary); }
.guide-modal__close {
  border: none; background: transparent; font-size: 22px; line-height: 1;
  color: var(--mc-text-tertiary); cursor: pointer; padding: 0 4px;
}
.guide-modal__close:hover { color: var(--mc-text-primary); }
.guide-modal__body { padding: 16px 20px; overflow-y: auto; }

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
