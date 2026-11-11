const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('server/app/esp32_screen.html','utf8');
const code=html.split('/* PHONE_TASK_SCRIPT */')[1].split('/* END_PHONE_TASK_SCRIPT */')[0];
function harness(lost=false){
 const elements={},calls=[],stored={};
 const $=id=>elements[id]??={value:'用微信给张三发消息，内容是明天见',handlers:{},addEventListener(k,f){this.handlers[k]=f;}};
 const task={ok:true,task_id:'a'.repeat(32),state:'running',goal:'用微信给张三发消息，内容是明天见',events:[{at:1,kind:'task_created',data:{goal:'<script>hello</script>'}}]};
 const ctx={$,authenticated:true,prefix:'/pixel',api:'/pixel/v1/phone-control',localStorage:{getItem:k=>stored[k],setItem:(k,v)=>stored[k]=v,removeItem:k=>delete stored[k]},newId:()=>'a'.repeat(32),clock:()=> '12:00',request:async(url,options={})=>{calls.push({url,options});if(lost&&options.method==='POST')throw Error('网络中断');return url.endsWith('/stop')?{...task,state:'unknown',summary:'用户要求停止'}:task;}};
 vm.createContext(ctx);vm.runInContext(code,ctx);
 return {$,calls,ctx,stored};
}
(async()=>{
 for(const lost of [false,true]){
  const {$,calls,ctx,stored}=harness(lost);
  const first=$('task-form').handlers.submit({preventDefault(){}});
  await $('task-form').handlers.submit({preventDefault(){}});await first;
  assert.equal(calls.filter(c=>c.options.method==='POST').length,1);
  assert.equal($('task-start').disabled,true);
  assert.equal(stored['pixel-phone-task:/pixel'],'a'.repeat(32));
  await ctx.refreshTask();
  assert.equal(calls.at(-1).url,'/pixel/v1/phone-control/tasks/'+'a'.repeat(32));
  assert.equal($('task-stop').disabled,false);
  assert.match($('task-events').textContent,/<script>hello<\/script>/);
  if(!lost){await $('task-stop').handlers.click();assert.equal($('task-stop').disabled,true);assert.equal($('task-start').disabled,false);}
 }
 const {ctx,$,calls}=harness();await ctx.refreshTask();assert.equal(calls[0].url,'/pixel/v1/phone-control/tasks');
 ctx.authenticated=false;ctx.updateTaskButtons();assert.equal($('task-start').disabled,true);assert.equal($('task-stop').disabled,true);
 console.log('Phone task UI: latest task, double submit, lost response, stable ID, stop, and auth passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
