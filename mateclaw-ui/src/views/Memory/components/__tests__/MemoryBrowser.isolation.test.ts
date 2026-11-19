import { createApp, defineComponent, h, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({listFiles:vi.fn(),getFile:vi.fn(),saveFile:vi.fn(),post:vi.fn()}))
vi.mock('@/api', () => ({agentContextApi:api,http:{post:api.post}}))
vi.mock('vue-i18n', () => ({useI18n:()=>({t:(key:string)=>key})}))
vi.mock('@/composables/useMcToast', () => ({mcToast:{success:vi.fn(),error:vi.fn()}}))
vi.mock('../MemoryIcon.vue', () => ({default:{template:'<span />'}}))
vi.mock('../MemorySkeleton.vue', () => ({default:{template:'<div>loading</div>'}}))
vi.mock('../MemoryEmptyState.vue', () => ({default:{template:'<div>empty</div>'}}))
vi.mock('../MemorySection.vue', () => ({default:{props:['section','saveHandler'],template:'<div class="section">{{section.heading}}:{{section.body}}<button class="save" @click="saveHandler(\'updated\')">save</button></div>'}}))
import MemoryBrowser from '../MemoryBrowser.vue'
const apps: ReturnType<typeof createApp>[] = []
const deferred = <T,>() => { let resolve!: (value:T)=>void; const promise = new Promise<T>(r=>{resolve=r}); return {promise,resolve} }
async function flush(){for(let i=0;i<5;i++){await Promise.resolve();await nextTick()}}
function mount(){
 const agent=ref('a'),host=document.createElement('div');document.body.append(host)
 const app=createApp(defineComponent({setup:()=>()=>h(MemoryBrowser,{agentId:agent.value})}));apps.push(app);app.mount(host)
 return {host,agent}
}
beforeEach(()=>{vi.clearAllMocks();api.listFiles.mockResolvedValue({data:[{filename:'MEMORY.md'},{filename:'PROFILE.md'}]});api.saveFile.mockResolvedValue({});api.post.mockResolvedValue({})})
afterEach(()=>{apps.splice(0).forEach(a=>a.unmount());document.body.innerHTML=''})
describe('memory browser request isolation',()=>{
 it('ignores an old file response after the selected file changed',async()=>{
  const old=deferred<unknown>();api.getFile.mockImplementation((_a,f)=>f==='MEMORY.md'?old.promise:Promise.resolve({data:{content:'## Profile\ncurrent'}}))
  const {host}=mount();await flush();(host.querySelector('[title="PROFILE.md"]') as HTMLElement).click();await flush()
  old.resolve({data:{content:'## Facts\nstale'}});await flush()
  expect(host.textContent).toContain('Profile:current');expect(host.textContent).not.toContain('stale')
 })
 it('keeps a delayed preamble save bound to its original file',async()=>{
  const read=deferred<unknown>();let memoryReads=0
  api.getFile.mockImplementation((_a,f)=>f==='MEMORY.md'?(++memoryReads===1?Promise.resolve({data:{content:'preamble\n## Facts\nkeep'}}):read.promise):Promise.resolve({data:{content:'## Profile\ncurrent'}}))
  const {host}=mount();await flush();(host.querySelector('.save') as HTMLElement).click();await flush()
  ;(host.querySelector('[title="PROFILE.md"]') as HTMLElement).click();await flush()
  read.resolve({data:{content:'preamble\n## Facts\nkeep'}});await flush()
  expect(api.saveFile).toHaveBeenCalledWith('a','MEMORY.md','updated\n\n## Facts\nkeep')
  expect(host.textContent).toContain('Profile:current')
 })
 it('ignores the old agent file list when switching agents',async()=>{
  const old=deferred<unknown>();api.listFiles.mockImplementation(a=>a==='a'?old.promise:Promise.resolve({data:[{filename:'SOUL.md'}]}));api.getFile.mockResolvedValue({data:{content:'## Tone\nwarm'}})
  const {host,agent}=mount();agent.value='b';await flush();old.resolve({data:[{filename:'MEMORY.md'}]});await flush()
  expect(host.querySelector('[title="SOUL.md"]')).not.toBeNull();expect(host.querySelector('[title="MEMORY.md"]')).toBeNull()
 })
 it('does not refresh a newly reopened file when its old save completes',async()=>{
  const pending=deferred<unknown>();api.getFile.mockImplementation((_a,f)=>Promise.resolve({data:{content:`## ${f}\ncurrent`}}));api.post.mockReturnValue(pending.promise)
  const {host}=mount();await flush();(host.querySelector('.save') as HTMLElement).click();await flush()
  ;(host.querySelector('[title="PROFILE.md"]') as HTMLElement).click();await flush()
  ;(host.querySelector('[title="MEMORY.md"]') as HTMLElement).click();await flush()
  const count=api.getFile.mock.calls.length;pending.resolve({});await flush()
  expect(api.getFile).toHaveBeenCalledTimes(count)
 })
 it('does not reload a disposed browser after a pending save completes',async()=>{
  const pending=deferred<unknown>();api.getFile.mockResolvedValue({data:{content:'## Facts\ncurrent'}});api.post.mockReturnValue(pending.promise)
  const {host}=mount();await flush();(host.querySelector('.save') as HTMLElement).click();await flush()
  const count=api.getFile.mock.calls.length;apps.shift()!.unmount();pending.resolve({});await flush()
  expect(api.getFile).toHaveBeenCalledTimes(count)
 })

})
