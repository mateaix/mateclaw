<template>
  <div class="mcp-icon" :class="paletteClass">
    <span
      v-if="svgMarkup"
      class="mcp-icon-svg"
      v-html="svgMarkup"
    />
    <span v-else class="mcp-icon-fallback">{{ initials }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { getMcpIconSvg } from './icons'
import { catalogInitial } from './catalog'

const props = defineProps<{
  /** Display name — used for initials fallback. */
  name: string
  /** Brand icon basename (matches `assets/icons/mcp/<iconKey>.svg`). */
  iconKey?: string
  /** Tint variant — driven by lastStatus for installed cards;
      catalog cards leave it undefined and rely on the default. */
  variant?: 'installed-ok' | 'installed-err' | 'installed' | 'brand'
}>()

const svgMarkup = computed(() => getMcpIconSvg(props.iconKey))
const initials = computed(() => catalogInitial(props.name))

const paletteClass = computed(() => {
  if (props.variant === 'installed-ok') return 'mcp-icon--installed-ok'
  if (props.variant === 'installed-err') return 'mcp-icon--installed-err'
  if (props.variant === 'installed') return 'mcp-icon--installed'
  return 'mcp-icon--brand'
})
</script>

<style scoped>
.mcp-icon {
  width: 38px;
  height: 38px;
  border-radius: 9px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 13px;
  letter-spacing: -0.02em;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  overflow: hidden;
}

.mcp-icon-svg {
  width: 60%;
  height: 60%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.mcp-icon-svg :deep(svg) {
  width: 100%;
  height: 100%;
  display: block;
}

.mcp-icon-fallback {
  line-height: 1;
}

/* Installed cards — tint background and glyph by status. */
.mcp-icon--installed-ok {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}
.mcp-icon--installed-err {
  background: rgba(239, 68, 68, 0.12);
  color: var(--mc-danger, #ef4444);
}
.mcp-icon--installed {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
}

/* Catalog brand bubble — neutral surface, brand glyph inherits color.
   Light mode keeps a warm cream tone; dark mode flips to elevated. */
.mcp-icon--brand {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
}
:global(html.dark) .mcp-icon--brand {
  background: rgba(255, 255, 255, 0.04);
  color: var(--mc-text-primary);
}
</style>
