<template>
  <div class="html-preview">
    <div class="html-preview__notice">
      <el-icon><InfoFilled /></el-icon>
      <span>{{ $t('chat.preview.htmlSandboxNotice') }}</span>
    </div>
    <!--
      Security boundary: user-uploaded / agent-generated HTML must NEVER run
      in the app origin. `allow-scripts` WITHOUT `allow-same-origin` gives the
      frame an opaque origin — scripts execute (interactive pages, charts)
      but cannot touch the app's localStorage/cookies (JWT). Content is
      injected via srcdoc so the frame has no same-origin URL of its own.
      Never add allow-same-origin here: combined with allow-scripts it would
      fully escape the sandbox.
    -->
    <iframe
      class="html-preview__frame"
      sandbox="allow-scripts"
      :srcdoc="html"
      :title="filename"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'

const props = defineProps<{
  /** Raw HTML bytes (already fetched with auth). */
  data: ArrayBuffer
  filename: string
}>()

const html = computed(() => new TextDecoder().decode(props.data))
</script>

<style scoped>
.html-preview {
  height: 100%;
  display: flex;
  flex-direction: column;
  /* Arbitrary HTML assumes an opaque white canvas; give it one so page
     content doesn't composite over the translucent glass behind it. */
  background: #fff;
}
.html-preview__notice {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  font-size: var(--mc-text-xs, 11px);
  color: var(--mc-text-secondary, #665245);
  background: var(--mc-bg-muted, #f1e8df);
  border-bottom: 1px solid var(--mc-border-light, #ebe3db);
}
.html-preview__frame {
  flex: 1;
  width: 100%;
  border: none;
  background: #fff;
}
</style>
