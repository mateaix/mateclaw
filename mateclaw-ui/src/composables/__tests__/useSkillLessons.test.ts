import { effectScope, nextTick, ref } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
const api=vi.hoisted(()=>({getLessons:vi.fn()}))
vi.mock('@/api',()=>({skillApi:api}))
import {useSkillLessons} from '../useSkillLessons'
const scopes:ReturnType<typeof effectScope>[]=[]
afterEach(()=>{scopes.splice(0).forEach(s=>s.stop());vi.resetAllMocks()})
const deferred=()=>{let resolve!:(x:any)=>void;return {promise:new Promise<any>(r=>resolve=r),resolve:(x:any)=>resolve(x)}}
function mount(){const id=ref<string|undefined>('a'),scope=effectScope();scopes.push(scope);const state=scope.run(()=>useSkillLessons(id,vi.fn()))!;return {id,state}}
it('invalidates a pending read when lessons are cleared',async()=>{
 const pending=deferred();api.getLessons.mockReturnValue(pending.promise);const{state}=mount();const read=state.load();expect(api.getLessons).toHaveBeenCalledWith('a');state.clearLocal('a');pending.resolve({data:{raw:'stale',entries:[{id:'old'}]}});await read
 expect(state.raw.value).toBe('');expect(state.entries.value).toEqual([]);expect(state.loading.value).toBe(false)
})
it('does not display a previous skill response after changing selection',async()=>{
 const pending=deferred();api.getLessons.mockReturnValue(pending.promise);const{id,state}=mount();const read=state.load();expect(api.getLessons).toHaveBeenCalledWith('a');id.value='b';await nextTick();pending.resolve({data:{raw:'stale'}});await read;expect(state.raw.value).toBe('')
})
it('ignores a response after the owning panel is disposed',async()=>{
 const pending=deferred();api.getLessons.mockReturnValue(pending.promise);const{state}=mount();const read=state.load();expect(api.getLessons).toHaveBeenCalledWith('a');scopes[0]!.stop();pending.resolve({data:{raw:'stale'}});await read;expect(state.raw.value).toBe('')
})

it('accepts the current successful response',async()=>{api.getLessons.mockResolvedValue({data:{raw:'current',entries:[]}});const{state}=mount();await state.load();expect(state.raw.value).toBe('current')})
it('does not restart a load from a late mutation callback after disposal',async()=>{const{state}=mount();scopes[0]!.stop();await state.load();expect(api.getLessons).not.toHaveBeenCalled()})
