<script setup lang="ts">
/**
 * MateClaw-styled tooltip.
 *
 * A thin wrapper over Element Plus `el-tooltip` that pins the app's
 * visual identity onto every tooltip: a frosted-glass surface, rounded
 * geometry, no arrow, and automatic light/dark theming. Use it as a
 * drop-in replacement for `<el-tooltip>` — every prop and the default /
 * `#content` slots pass straight through, while `effect`, the frosted
 * `popper-class` and the arrow visibility stay owned by the wrapper so
 * tooltips look identical across the app.
 */
import { computed } from 'vue'

defineOptions({ inheritAttrs: false })

const props = defineProps<{
  /** Extra class(es) merged onto the frosted popper element. */
  popperClass?: string
}>()

// `mc-tooltip` drives the frosted styling below; callers may layer on more.
const mergedPopperClass = computed(
  () => ['mc-tooltip', props.popperClass].filter(Boolean).join(' '),
)
</script>

<template>
  <el-tooltip
    :show-after="80"
    :offset="8"
    v-bind="$attrs"
    effect="mateclaw"
    :show-arrow="false"
    :popper-class="mergedPopperClass"
  >
    <slot />
    <template v-if="$slots.content" #content>
      <slot name="content" />
    </template>
  </el-tooltip>
</template>

<!-- Not scoped: el-tooltip teleports its popper to <body>, so the popper
     is unreachable by scoped styles. The `mc-tooltip` popper class keeps
     these rules namespaced. The `mateclaw` effect carries no Element Plus
     theme of its own, so the wrapper fully owns the surface. -->
<style>
.el-popper.mc-tooltip {
  background: rgba(255, 250, 245, 0.82);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  padding: 7px 11px;
  max-width: 260px;
  color: var(--mc-text-primary);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;
  letter-spacing: -0.005em;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.6) inset,
    0 8px 24px rgba(25, 14, 8, 0.16);
}

html.dark .el-popper.mc-tooltip {
  background: rgba(32, 26, 22, 0.86);
  border-color: rgba(255, 255, 255, 0.08);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.06) inset,
    0 8px 24px rgba(0, 0, 0, 0.45);
}
</style>
