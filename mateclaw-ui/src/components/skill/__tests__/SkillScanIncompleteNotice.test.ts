import { createApp } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(key:string)=>key})}))
import SkillScanIncompleteNotice from '../SkillScanIncompleteNotice.vue'
const apps: ReturnType<typeof createApp>[] = []
afterEach(()=>{apps.splice(0).forEach(a=>a.unmount());document.body.innerHTML=''})
function mount(findings:unknown){const host=document.createElement('div');document.body.append(host);const app=createApp(SkillScanIncompleteNotice,{findings});apps.push(app);app.mount(host);return host}
it('explains an incomplete scan instead of presenting it as a clean result',()=>{
 expect(mount([{category:'SCAN_INCOMPLETE',ruleId:'SCAN_FILE_READ_FAILED'}]).textContent).toContain('skills.security.scanIncompleteHint')
})
it('does not conflate a detected dangerous pattern with a scan failure',()=>{
 for(const value of [null,'invalid',[null],[],[{category:'CODE_EXECUTION',ruleId:'REVERSE_SHELL'}]]) expect(mount(value).querySelector('[role="status"]')).toBeNull()
})
