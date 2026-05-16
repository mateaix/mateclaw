<template>
  <MateDrawer
    :visible="visible"
    :title="t('doctor.title')"
    :subtitle="t('doctor.subtitle')"
    :close-label="t('common.close')"
    @close="emit('close')"
  >
    <template #icon>
      <!-- Heartbeat glyph — Apple Health metaphor: this drawer reports
           vital signs of the local instance, not just status flags. -->
      <svg
        width="22"
        height="22"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
      </svg>
    </template>

    <div class="doctor-stack">
      <!-- Status hero: the first fact the user reads. A frosted card
           carries the overall verdict; the colored dot does the
           semantic work, the background stays calm. -->
      <div class="status-hero" :class="`status-hero--${heroState}`">
        <span class="status-hero__dot"></span>
        <div class="status-hero__text">
          <h4 class="status-hero__headline">{{ heroHeadline }}</h4>
          <p class="status-hero__sub">{{ heroSub }}</p>
        </div>
      </div>

      <!-- Check list — each row is a frosted card, not a bordered row. -->
      <div class="check-list">
        <div
          v-for="check in health?.checks"
          :key="check.name"
          class="check-card"
        >
          <span class="check-card__dot" :class="`check-card__dot--${check.status}`"></span>
          <div class="check-card__info">
            <div class="check-card__name">{{ check.name }}</div>
            <div class="check-card__message">{{ check.message }}</div>
          </div>
          <router-link
            v-if="check.action && check.status !== 'healthy'"
            :to="check.action.route"
            class="check-card__action"
            @click="emit('close')"
          >
            {{ check.action.label }}
          </router-link>
        </div>
      </div>
    </div>

    <template #footer>
      <button class="footer-refresh" :disabled="loading" @click="fetchHealth">
        <svg
          v-if="!loading"
          width="13"
          height="13"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <polyline points="23 4 23 10 17 10" />
          <polyline points="1 20 1 14 7 14" />
          <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
        </svg>
        {{ loading ? t('doctor.checking') : t('doctor.refresh') }}
      </button>
      <span v-if="lastChecked" class="footer-stamp">
        {{ t('doctor.lastChecked', { time: lastCheckedText }) }}
      </span>
    </template>
  </MateDrawer>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { http } from '@/api/index'
import MateDrawer from '@/components/common/MateDrawer.vue'

interface HealthAction { label: string; route: string }
interface HealthCheck { name: string; status: string; message: string; action?: HealthAction }
interface HealthResponse { overall: string; checks: HealthCheck[] }

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'status', overall: string): void }>()
const { t } = useI18n()

const health = ref<HealthResponse | null>(null)
const loading = ref(false)
const lastChecked = ref<Date | null>(null)

const totalChecks = computed(() => health.value?.checks.length || 0)
const healthyChecks = computed(
  () => health.value?.checks.filter(c => c.status === 'healthy').length || 0,
)
const warningCount = computed(
  () => health.value?.checks.filter(c => c.status === 'warning').length || 0,
)
const errorCount = computed(
  () => health.value?.checks.filter(c => c.status === 'error').length || 0,
)

const heroState = computed(() => {
  if (loading.value && !health.value) return 'loading'
  return health.value?.overall || 'loading'
})

const heroHeadline = computed(() => {
  if (heroState.value === 'loading') return t('doctor.checking')
  if (heroState.value === 'healthy') return t('doctor.allGood')
  if (heroState.value === 'warning') return t('doctor.hasWarnings', { count: warningCount.value })
  if (heroState.value === 'error') return t('doctor.hasErrors', { count: errorCount.value })
  return ''
})

const heroSub = computed(() => {
  if (heroState.value === 'loading') return t('doctor.checksLoading')
  return t('doctor.checksPassed', { healthy: healthyChecks.value, total: totalChecks.value })
})

const lastCheckedText = computed(() => {
  if (!lastChecked.value) return ''
  const secs = Math.floor((Date.now() - lastChecked.value.getTime()) / 1000)
  if (secs < 60) return `${secs}s`
  return `${Math.floor(secs / 60)}m`
})

async function fetchHealth() {
  loading.value = true
  try {
    const res: any = await http.get('/system/health')
    health.value = res.data || res
    lastChecked.value = new Date()
    emit('status', health.value?.overall || 'healthy')
  } catch (e) {
    console.error('Health check failed', e)
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (v) => {
  if (v) fetchHealth()
})
</script>

<style scoped>
/* Body layout — MateDrawer's body is minimal; we own padding + gap. */
.doctor-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 22px 22px;
}

/* Status hero — frosted card with a colored dot doing the semantic work. */
.status-hero {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.55);
  box-shadow: 0 1px 3px rgba(25, 14, 8, 0.04);
}
:global(html.dark .status-hero) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
}
.status-hero__dot {
  width: 14px;
  height: 14px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--mc-text-tertiary);
  box-shadow: 0 0 0 4px rgba(0, 0, 0, 0.04);
}
.status-hero--loading .status-hero__dot {
  background: var(--mc-text-tertiary);
  animation: status-pulse 1.4s ease-in-out infinite;
}
.status-hero--healthy .status-hero__dot {
  background: var(--mc-success);
  box-shadow: 0 0 0 4px rgba(90, 138, 90, 0.12);
}
.status-hero--warning .status-hero__dot {
  background: var(--mc-primary);
  box-shadow: 0 0 0 4px rgba(217, 119, 87, 0.14);
}
.status-hero--error .status-hero__dot {
  background: var(--mc-danger);
  box-shadow: 0 0 0 4px rgba(200, 60, 60, 0.14);
}
@keyframes status-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
.status-hero__text {
  min-width: 0;
}
.status-hero__headline {
  margin: 0 0 2px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.005em;
  color: var(--mc-text-primary);
}
.status-hero__sub {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--mc-text-tertiary);
}

/* Check list — frosted cards, no visible borders. The dot carries
   the status, the action sits as a pill on the right. */
.check-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.check-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.42);
  box-shadow: 0 1px 2px rgba(25, 14, 8, 0.03);
  transition: background 0.15s ease, transform 0.15s ease;
}
.check-card:hover {
  background: rgba(255, 255, 255, 0.62);
}
:global(html.dark .check-card) {
  background: rgba(255, 255, 255, 0.04);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
}
:global(html.dark .check-card:hover) {
  background: rgba(255, 255, 255, 0.07);
}
.check-card__dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  margin-top: 6px;
  flex-shrink: 0;
}
.check-card__dot--healthy { background: var(--mc-success); }
.check-card__dot--warning { background: var(--mc-primary); }
.check-card__dot--error { background: var(--mc-danger); }
.check-card__info {
  flex: 1;
  min-width: 0;
}
.check-card__name {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: -0.005em;
  color: var(--mc-text-primary);
}
.check-card__message {
  font-size: 12px;
  line-height: 1.5;
  color: var(--mc-text-secondary);
  margin-top: 2px;
}
.check-card__action {
  flex-shrink: 0;
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  color: #fff;
  background: var(--mc-primary);
  text-decoration: none;
  white-space: nowrap;
  transition: background 0.15s ease, transform 0.15s ease;
}
.check-card__action:hover {
  background: var(--mc-primary-hover);
  transform: translateY(-1px);
}

/* Footer refresh — pill secondary button to match the rest of the app. */
.footer-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.6);
  box-shadow: inset 0 0 0 1px rgba(123, 88, 67, 0.12);
  color: var(--mc-text-primary);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease, box-shadow 0.15s ease;
}
.footer-refresh:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.85);
  box-shadow: inset 0 0 0 1px rgba(123, 88, 67, 0.20);
}
.footer-refresh:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
:global(html.dark .footer-refresh) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}
:global(html.dark .footer-refresh:hover:not(:disabled)) {
  background: rgba(255, 255, 255, 0.10);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.14);
}
.footer-stamp {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin-left: auto;
}
</style>
