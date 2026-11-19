import { expect, it } from 'vitest'
import { applySkillSnapshot } from '../skillSnapshot'
interface Row { id:string|number; name:string; securityScanStatus:string; securityScanResult:string|null }
const old:Row={id:'1',name:'test',securityScanStatus:'FAILED',securityScanResult:'incomplete'}
const fresh:Row={...old,id:1,securityScanStatus:'PASSED',securityScanResult:null}
it('updates the list and open drawer together, clearing obsolete findings',()=>{
 const segments=[{items:[{...old}]}],detail={value:{...old} as Row|null}
 applySkillSnapshot(fresh,segments,detail)
 expect(segments[0]!.items[0]?.securityScanStatus).toBe('PASSED')
 expect(detail.value?.securityScanStatus).toBe('PASSED')
 expect(detail.value?.securityScanResult).toBeNull()
})
it('updates an open drawer even off-page, without replacing a different selected skill',()=>{
 const detail={value:{...old} as Row|null};applySkillSnapshot(fresh,[],detail);expect(detail.value?.securityScanStatus).toBe('PASSED')
 detail.value={...old,id:'2'};applySkillSnapshot(fresh,[{items:[{...old}]}],detail);expect(detail.value.id).toBe('2');expect(detail.value.securityScanStatus).toBe('FAILED')
})
