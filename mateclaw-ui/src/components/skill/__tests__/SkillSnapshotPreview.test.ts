import {createApp,defineComponent,h,nextTick,ref} from 'vue'
import {afterEach,expect,it,vi} from 'vitest'
const api=vi.hoisted(()=>({curatorSnapshotPreview:vi.fn(),curatorSnapshotRestore:vi.fn()}))
vi.mock('@/api',()=>({skillApi:api}))
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(key:string)=>key})}))
import SkillSnapshotPreview from '../SkillSnapshotPreview.vue'
const apps:ReturnType<typeof createApp>[]=[]
afterEach(()=>{apps.splice(0).forEach(a=>a.unmount());document.body.innerHTML='';vi.resetAllMocks()})
const value=(id='100')=>({snapshotId:id,restoreCount:1,unchangedCount:0,missingCount:0,archiveCount:1,entries:[{skillId:'1',name:'<script>alpha</script>',action:'restore',changedFields:['skillContent']},{skillId:'2',name:'new skill',action:'archive',changedFields:['enabled']}]})
async function flush(){for(let i=0;i<4;i++){await Promise.resolve();await nextTick()}}
function mount(){const id=ref('100'),loaded=vi.fn(),host=document.createElement('div');document.body.append(host);const app=createApp(defineComponent({setup:()=>()=>h(SkillSnapshotPreview,{snapshotId:id.value,onLoaded:loaded})}));apps.push(app);app.mount(host);return {id,host,loaded}}
it('shows escaped impact rows without calling restore',async()=>{
 api.curatorSnapshotPreview.mockResolvedValue({data:value()});const{host,loaded}=mount();await flush()
 expect(host.textContent).toContain('<script>alpha</script>');expect(host.querySelector('script')).toBeNull();expect(host.textContent).toContain('skillCurator.snapshotPreviewActions.archive');expect(loaded).toHaveBeenCalledWith(expect.objectContaining({snapshotId:'100'}));expect(api.curatorSnapshotRestore).not.toHaveBeenCalled()
})
it('ignores a delayed response for the previous snapshot',async()=>{
 let resolve!:(value:any)=>void;api.curatorSnapshotPreview.mockImplementation(id=>id==='100'?new Promise(r=>resolve=r):Promise.resolve({data:value('200')}))
 const{id,loaded}=mount();id.value='200';await flush();resolve({data:value('100')});await flush();expect(loaded).toHaveBeenCalledTimes(1);expect(loaded).toHaveBeenCalledWith(expect.objectContaining({snapshotId:'200'}))
})
it('does not enable restore for an invalid or failed preview',async()=>{
 api.curatorSnapshotPreview.mockResolvedValue({data:{snapshotId:'wrong'}});const{host,loaded}=mount();await flush();expect(loaded).not.toHaveBeenCalled();expect(host.textContent).toContain('skillCurator.snapshotPreviewFailed')
})
