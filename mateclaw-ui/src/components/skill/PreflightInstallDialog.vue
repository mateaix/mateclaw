<template>
  <div v-if="visible" class="modal-overlay" @click.self="handleClose">
    <div class="preflight-modal">
      <div class="modal-header">
        <h2>{{ t('skills.preflight.title') }}: {{ skillName }}</h2>
        <button class="modal-close" @click="handleClose">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <p v-if="loading" class="loading">{{ t('common.loading') }}</p>

        <template v-else>
          <!-- Status summary -->
          <div class="status-banner" :class="statusBannerClass">
            <span class="status-icon">{{ allMet ? '✅' : '⚙️' }}</span>
            <div class="status-text">
              <strong>
                {{ allMet ? t('skills.preflight.allMet') : t('skills.preflight.setupNeeded', { count: missingCount }) }}
              </strong>
              <p>{{ allMet ? t('skills.preflight.allMetDesc') : t('skills.preflight.setupNeededDesc') }}</p>
            </div>
          </div>

          <!-- Requirement list -->
          <div v-if="statuses.length > 0" class="req-list">
            <h3>{{ t('skills.preflight.requirements') }}</h3>
            <div v-for="req in statuses" :key="req.key" class="req-item" :class="{ 'req-ok': req.satisfied, 'req-missing': !req.satisfied && !req.optional, 'req-optional': req.optional }">
              <div class="req-head">
                <span class="req-icon">{{ req.satisfied ? '✓' : (req.optional ? '○' : '✗') }}</span>
                <span class="req-name">{{ req.key }}</span>
                <span v-if="req.type" class="req-type">{{ req.type }}</span>
                <span v-if="req.optional" class="req-optional-tag">{{ t('skills.preflight.optional') }}</span>
              </div>
              <p v-if="req.description" class="req-desc">{{ req.description }}</p>
              <!-- Install commands per platform -->
              <div v-if="!req.satisfied && req.installCommands && Object.keys(req.installCommands).length > 0" class="install-cmds">
                <div class="install-cmds-head">{{ t('skills.preflight.installCommands') }}</div>
                <div v-for="(cmd, platform) in req.installCommands" :key="platform" class="install-cmd-row">
                  <span class="platform-tag" :class="`platform-${platformClass(platform)}`">{{ formatPlatform(platform) }}</span>
                  <code class="install-cmd">{{ cmd }}</code>
                  <button class="copy-btn" :title="t('common.copy')" @click="copy(String(cmd))">📋</button>
                </div>
              </div>
            </div>
          </div>

          <!-- Feature matrix -->
          <div v-if="featureRows.length > 0" class="feat-section">
            <h3>{{ t('skills.preflight.features') }}</h3>
            <div v-for="row in featureRows" :key="row.id" class="feat-item">
              <span class="feat-id">{{ row.id }}</span>
              <span class="feat-status" :class="`feat-${row.status.toLowerCase()}`">{{ row.status }}</span>
            </div>
          </div>

          <!-- Empty manifest fallback -->
          <p v-if="statuses.length === 0 && featureRows.length === 0" class="empty-msg">
            {{ summary || t('skills.preflight.noManifest') }}
          </p>
        </template>
      </div>

      <div class="modal-footer">
        <button class="btn-secondary" @click="handleClose">{{ t('common.close') }}</button>
        <button v-if="!allMet" class="btn-secondary" @click="reload" :disabled="loading">
          {{ loading ? t('common.loading') : t('skills.preflight.recheck') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { skillApi } from '@/api/index'
import { copyToClipboard } from '@/utils/clipboard'

interface RequirementStatus {
  key: string
  type?: string
  description?: string
  optional?: boolean
  status?: string
  satisfied?: boolean
  installCommands?: Record<string, string>
}

const props = defineProps<{
  visible: boolean
  skillId: number | string | null
  skillName: string
}>()
const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
}>()

const { t } = useI18n()
const loading = ref(false)
const allMet = ref(false)
const statuses = ref<RequirementStatus[]>([])
const summary = ref('')
const featureStatuses = ref<Record<string, string>>({})
const activeFeatures = ref<string[]>([])

const missingCount = computed(() =>
  statuses.value.filter(s => !s.satisfied && !s.optional).length,
)
const statusBannerClass = computed(() => allMet.value ? 'banner-ok' : 'banner-needs-setup')

const featureRows = computed(() =>
  Object.entries(featureStatuses.value).map(([id, status]) => ({ id, status })),
)

watch(() => props.visible, (v) => {
  if (v && props.skillId != null) reload()
})

async function reload() {
  if (props.skillId == null) return
  loading.value = true
  try {
    const res: any = await skillApi.requirements(props.skillId)
    const data = res?.data || {}
    allMet.value = !!data.allMet
    statuses.value = Array.isArray(data.statuses) ? data.statuses : []
    summary.value = data.summary || ''
    featureStatuses.value = data.featureStatuses || {}
    activeFeatures.value = Array.isArray(data.activeFeatures) ? data.activeFeatures : []
  } catch (e: any) {
    mcToast.error(typeof e === 'string' ? e : e?.message || t('skills.preflight.loadFailed'))
    statuses.value = []
    allMet.value = false
  } finally {
    loading.value = false
  }
}

function handleClose() {
  emit('update:visible', false)
}

async function copy(cmd: string) {
  try {
    await copyToClipboard(cmd)
    mcToast.success(t('common.copied'))
  } catch {
    mcToast.warning(t('common.copyFailed'))
  }
}

function platformClass(platform: string): string {
  const p = String(platform).toLowerCase()
  if (p.includes('mac')) return 'mac'
  if (p.includes('linux') || p.includes('apt') || p.includes('dnf') || p.includes('yum')) return 'linux'
  if (p.includes('win')) return 'win'
  return 'other'
}

function formatPlatform(platform: string): string {
  const p = String(platform).toLowerCase()
  if (p === 'macos') return 'macOS'
  if (p === 'linux_apt') return 'Linux (apt)'
  if (p === 'linux_dnf') return 'Linux (dnf)'
  if (p === 'windows') return 'Windows'
  if (p === 'manual_url') return 'Manual'
  return String(platform)
}
</script>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); display: flex; align-items: center; justify-content: center; z-index: 1100; padding: 20px; }
.preflight-modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 720px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 24px 64px rgba(0, 0, 0, 0.18); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 22px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 17px; font-weight: 600; margin: 0; color: var(--mc-text-primary); }
.modal-close { width: 30px; height: 30px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); border-radius: 6px; display: flex; align-items: center; justify-content: center; }
.modal-close:hover { background: var(--mc-bg-sunken); }
.modal-body { flex: 1; overflow-y: auto; padding: 18px 22px; display: flex; flex-direction: column; gap: 14px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 22px; border-top: 1px solid var(--mc-border-light); }
.btn-secondary { padding: 8px 14px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 13px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: 0.6; cursor: not-allowed; }

.loading { color: var(--mc-text-tertiary); }
.empty-msg { color: var(--mc-text-tertiary); font-size: 13px; }

.status-banner { display: flex; align-items: flex-start; gap: 12px; padding: 14px; border-radius: 12px; }
.banner-ok { background: rgba(34, 197, 94, 0.08); border: 1px solid rgba(34, 197, 94, 0.2); }
.banner-needs-setup { background: var(--mc-primary-bg); border: 1px solid rgba(217, 109, 70, 0.18); }
.status-icon { font-size: 22px; line-height: 1; }
.status-text strong { display: block; font-size: 14px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 2px; }
.status-text p { margin: 0; font-size: 12px; color: var(--mc-text-secondary); line-height: 1.5; }

.req-list h3, .feat-section h3 { font-size: 12px; font-weight: 700; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.06em; margin: 0 0 6px; }
.req-item { padding: 10px 12px; border-radius: 10px; border: 1px solid var(--mc-border-light); margin-bottom: 6px; background: var(--mc-bg-muted); }
.req-item.req-ok { border-color: rgba(34, 197, 94, 0.2); }
.req-item.req-missing { border-color: rgba(217, 109, 70, 0.25); }
.req-head { display: flex; align-items: center; gap: 8px; }
.req-icon { font-weight: 700; }
.req-ok .req-icon { color: #16a34a; }
.req-missing .req-icon { color: var(--mc-danger); }
.req-optional .req-icon { color: var(--mc-text-tertiary); }
.req-name { font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }
.req-type { font-size: 10px; padding: 1px 6px; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); border-radius: 999px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
.req-optional-tag { font-size: 10px; padding: 1px 6px; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); border-radius: 999px; font-weight: 600; }
.req-desc { margin: 6px 0 0; font-size: 12px; color: var(--mc-text-secondary); line-height: 1.5; }
.install-cmds { margin-top: 8px; }
.install-cmds-head { font-size: 11px; font-weight: 700; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 4px; }
.install-cmd-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; }
.platform-tag { font-size: 10px; padding: 1px 6px; border-radius: 4px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em; min-width: 64px; text-align: center; }
.platform-mac { background: rgba(99, 102, 241, 0.12); color: #6366f1; }
.platform-linux { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.platform-win { background: rgba(59, 130, 246, 0.12); color: #3b82f6; }
.platform-other { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.install-cmd { flex: 1; font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; font-size: 12px; padding: 4px 8px; background: var(--mc-bg-sunken); border-radius: 4px; color: var(--mc-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.copy-btn { width: 26px; height: 26px; border: none; background: none; cursor: pointer; border-radius: 4px; font-size: 14px; }
.copy-btn:hover { background: var(--mc-bg-sunken); }

.feat-section { display: flex; flex-direction: column; gap: 4px; }
.feat-item { display: flex; align-items: center; justify-content: space-between; padding: 6px 10px; background: var(--mc-bg-muted); border-radius: 8px; font-size: 12px; }
.feat-id { font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; color: var(--mc-text-primary); }
.feat-status { font-size: 10px; padding: 1px 6px; border-radius: 999px; font-weight: 700; letter-spacing: 0.04em; }
.feat-ready { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.feat-setup_needed { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.feat-unsupported { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.feat-unknown { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
</style>
