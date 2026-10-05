import {test,expect} from '@playwright/test'
import {readFileSync} from 'node:fs'
const bundle=readFileSync(new URL('../dist/unoone-page-agent.js',import.meta.url),'utf8')
const planner=readFileSync(new URL('../../../android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/PageAgentGemmaPlanner.kt',import.meta.url),'utf8')
test('literal planner contract: date, explicit checkbox, vertical and horizontal targeted scroll',async({page})=>{
 expect(planner).toContain('pick_date{index,date}')
 expect(planner).toContain('toggle_checkbox{index,checked}')
 expect(planner).toContain('scroll{down,num_pages,pixels,index}')
 expect(planner).toContain('scroll_horizontally{right,pixels,index}')
 await page.setContent('<h1>Booking</h1><input id="date" type="date" value="2025-12-01"><input id="check" type="checkbox"><div id="box" tabindex="0" style="width:100px;height:100px;overflow:scroll"><div style="width:2000px;height:2000px">Text</div></div>')
 await page.addScriptTag({content:bundle})
 const result=await page.evaluate(()=>{
  const a=(window as any).UnoOneDomAdapter
  const target=(id:string)=>a.observe().elements.find((e:any)=>JSON.parse(e.summary).id===id)
  const date={...target('date'),action:'pick_date',date:'2026-01-02'};a.act(date)
  const check={...target('check'),action:'toggle_checkbox',checked:true};a.act(check)
  const box=document.querySelector('#box')!;box.scrollTop=500;box.scrollLeft=500
  a.act({...target('box'),action:'scroll',down:false,pixels:120,num_pages:1})
  a.act({...target('box'),action:'scroll_horizontally',right:false,pixels:80})
  let invalid=false;try{a.act({...target('date'),action:'pick_date',date:''})}catch{invalid=true}
  return {date:(document.querySelector('#date') as HTMLInputElement).value,dateVerified:a.verify(date).verified,checked:a.verify(check).verified,top:box.scrollTop,left:box.scrollLeft,invalid,headings:a.observe().headings}
 })
 expect(result).toEqual({date:'2026-01-02',dateVerified:true,checked:true,top:380,left:420,invalid:true,headings:['Booking']})
})
