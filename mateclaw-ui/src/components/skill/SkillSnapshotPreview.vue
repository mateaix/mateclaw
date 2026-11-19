<template>
  <section class="snapshot-preview" :aria-label="t('skillCurator.snapshotPreviewTitle')">
    <p v-if="loading">{{ t('common.loading') }}</p>
    <div v-else-if="failed"><p role="alert">{{ t('skillCurator.snapshotPreviewFailed') }}</p><button type="button" @click="load">{{ t('skillCurator.snapshotPreviewRetry') }}</button></div>
    <template v-else-if="data">
      <p class="preview-summary">{{ t('skillCurator.snapshotPreviewSummary',data) }}</p>
      <p class="preview-hint">{{ t('skillCurator.snapshotPreviewHint') }}</p>
      <ul>
        <li v-for="entry in data.entries" :key="entry.skillId">
          <strong>{{ entry.name || entry.skillId }}</strong> · {{ t(`skillCurator.snapshotPreviewActions.${entry.action}`) }}
          <span v-if="entry.changedFields.length"> — {{ entry.changedFields.map(field => t(`skillCurator.snapshotPreviewFields.${field}`)).join('、') }}</span>
        </li>
      </ul>
    </template>
  </section>
</template>
<script setup lang="ts">
import {onScopeDispose,ref,watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {skillApi,type SkillSnapshotPreviewData} from '@/api'
const props=defineProps<{snapshotId:string}>()
const emit=defineEmits<{loaded:[data:SkillSnapshotPreviewData];loading:[]}>()
const {t}=useI18n()
const data=ref<SkillSnapshotPreviewData|null>(null),loading=ref(false),failed=ref(false)
let generation=0,disposed=false
onScopeDispose(()=>{disposed=true;generation++})
function valid(value:any,id:string):value is SkillSnapshotPreviewData {
  return value && value.snapshotId===id && ['restoreCount','unchangedCount','missingCount','archiveCount'].every(k=>Number.isSafeInteger(value[k])&&value[k]>=0)
    && value.unchangedCount<=value.restoreCount && Array.isArray(value.entries)
    && value.entries.length===value.restoreCount+value.missingCount+value.archiveCount
    && value.entries.every((e:any)=>e&&typeof e.skillId==='string'&&typeof e.name==='string'&&['restore','unchanged','missing','archive'].includes(e.action)&&Array.isArray(e.changedFields)&&e.changedFields.every((f:unknown)=>typeof f==='string'))
}
async function load(){
  if(disposed)return
  const id=props.snapshotId,request=++generation
  const current=()=>!disposed&&request===generation&&id===props.snapshotId
  loading.value=true;failed.value=false;data.value=null;emit('loading')
  try{const result:any=await skillApi.curatorSnapshotPreview(id);if(!current())return;if(!valid(result?.data,id))throw new Error('Invalid preview');data.value=result.data;emit('loaded',result.data)}
  catch{if(current())failed.value=true}
  finally{if(current())loading.value=false}
}
watch(()=>props.snapshotId,load,{immediate:true})
</script>
<style scoped>
.snapshot-preview { padding: 12px; margin-top: 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 13px; line-height: 1.7; }
.preview-summary { font-weight: 600; }.preview-hint { color: var(--mc-text-tertiary); } ul { padding-left: 18px; } li { overflow-wrap: anywhere; }
</style>
