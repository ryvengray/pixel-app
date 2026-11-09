const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('server/app/esp32_screen.html','utf8');
const code=html.slice(html.indexOf('function updateVisionButton(){'),html.indexOf('/* PHONE_SCREEN_SCRIPT */'));
function harness({missing=false,disconnect=false}={}){
 const elements={},calls=[];
 const $=id=>elements[id]??=( {hidden:false,disabled:false,textContent:'',value:'点赞按钮',handlers:{},addEventListener(type,fn){this.handlers[type]=fn;}} );
 const context={$,
  authenticated:true,visionResult:null,visionJobId:null,visionClickBusy:false,visionRecognizing:false,visionDrawVersion:0,
  Date,Promise,Image:class{set src(value){}},api:'/v1/phone-control',ready:()=>true,newId:()=>'a'.repeat(32),
  setTimeout:fn=>{fn();},
  request:async(url,options={})=>{
   calls.push({url,options});
   if(url.endsWith('/locate'))return {found:!missing,result_id:'b'.repeat(32),expires_at:Date.now()/1000+90,target:'点赞按钮',point:{x:880,y:1819},bounds:[850,1790,910,1850],width:960,height:2142,description:'点赞按钮。',elapsed_seconds:30,image_base64:'test'};
   if(options.method==='POST'){
    if(disconnect)throw Error('connection lost');
    return {state:'running',message:'移动中'};
   }
   return {state:disconnect?'unknown':'succeeded',message:disconnect?'结果未知':'报告已发送'};
  }
 };
 context.updateButton=()=>context.updateVisionButton();
 vm.createContext(context);vm.runInContext(code,context);
 return {context,$,calls};
}
(async()=>{
 for(const disconnect of [false,true]){
  const {context,$,calls}=harness({disconnect});
  await $('vision-form').handlers.submit({preventDefault(){}});
  assert.equal($('vision-click').hidden,false);assert.equal($('vision-click').disabled,false);
  const first=$('vision-click').handlers.click();
  await $('vision-click').handlers.click(); // Double click while the original POST is in flight.
  await first;
  const posts=calls.filter(c=>c.url.endsWith('/vision/click')&&c.options.method==='POST');
  assert.equal(posts.length,1);
  assert.equal(JSON.parse(posts[0].options.body).id,'a'.repeat(32));
  assert.equal(calls.filter(c=>c.url.endsWith('/click/'+'a'.repeat(32))).length,1);
  assert.equal(context.visionResult,null);assert.equal($('vision-click').hidden,true);
 }
 const { $ }=harness({missing:true});
 await $('vision-form').handlers.submit({preventDefault(){}});
 assert.equal($('vision-click').hidden,true);
 console.log('Vision UI: recognized target, missing target, double click, and lost-response query passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
