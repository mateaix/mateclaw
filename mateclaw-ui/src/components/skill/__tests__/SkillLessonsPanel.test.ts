import { createApp } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(key:string)=>key})}))
import SkillLessonsPanel from '../SkillLessonsPanel.vue'
const apps:ReturnType<typeof createApp>[]=[]
afterEach(()=>{apps.splice(0).forEach(a=>a.unmount());document.body.innerHTML=''})
it('shows escaped individual lessons and emits only the selected stable id',()=>{
 const remove=vi.fn(),host=document.createElement('div');document.body.append(host)
 const app=createApp(SkillLessonsPanel,{entries:[{id:'a',heading:'One',content:'<script>bad</script>',deletable:true},{id:'b',heading:'Two',content:'keep',deletable:false}],onRemove:remove});apps.push(app);app.mount(host)
 expect(host.textContent).toContain('<script>bad</script>');expect(host.querySelector('script')).toBeNull()
 const buttons=host.querySelectorAll('button');expect(buttons).toHaveLength(2)
 buttons[0]!.click();expect(remove).toHaveBeenCalledWith('a');expect(buttons[1]!.disabled).toBe(true)
})
