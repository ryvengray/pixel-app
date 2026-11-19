import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import {inflateSync} from 'node:zlib';
import {webcrypto} from 'node:crypto';
const root=new URL('../web/',import.meta.url);
const script=name=>readFileSync(new URL(name+'.html',root),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
for(const name of ['setup','upload'])new vm.Script(script(name),{filename:name+'.html'});
const elements=new Map();let putFrame,drawArgs;
const context={fillRect(){},drawImage(...a){drawArgs=a;},getImageData(){
  const data=new Uint8ClampedArray(800*480*4);
  // A known six-color raster lets us check byte order, palette and exact packing.
  const colors=[[255,255,255],[20,20,18],[172,40,45],[237,198,60],[50,115,74],[32,67,139]];
  for(let p=0;p<800*480;p++)data.set([...colors[p%6],255],p*4);
  return {data};
},putImageData(f){putFrame=f;}};
const get=id=>{if(!elements.has(id))elements.set(id,{value:({'zoom':1,'horizontal':.5,'vertical':.5})[id],attributes:{},add(option){if(option.selected)this.value=String(option.value);},setAttribute(name,value){this.attributes[name]=value;},getContext:()=>context});return elements.get(id);};
const sandbox=vm.createContext({document:{getElementById:get},location:{hash:'#'+'a'.repeat(32),pathname:'/'},sessionStorage:{setItem(){},getItem(){}},history:{replaceState(){}},setTimeout(){},clearTimeout(){},crypto:webcrypto,Blob,Response,CompressionStream,Uint8Array,Uint8ClampedArray,console,Option:function(text,value){this.text=text;this.value=value;},confirm:()=>true,fetch:async()=>({ok:false})});
vm.runInContext(script('upload'),sandbox);
vm.runInContext('source={width:1200,height:1800};',sandbox);
await vm.runInContext('prepare()',sandbox);
const result=vm.runInContext('prepared',sandbox);
assert.equal(result.encoding,'deflate');
const raw=inflateSync(result.bytes);assert.equal(raw.length,192000);
for(let i=0;i<raw.length;i++)assert.equal(raw[i],((i*2)%6<<4)|((i*2+1)%6));
assert.equal(vm.runInContext("crc32(new TextEncoder().encode('123456789'))",vm.createContext({TextEncoder,crc32:vm.runInContext('crc32',sandbox)})),'cbf43926');
assert.equal(result.crc,vm.runInContext('crc32',sandbox)(raw));
assert.equal(putFrame.data.length,800*480*4);
assert.equal(drawArgs[1],0);assert.equal(drawArgs[2],540);assert.equal(drawArgs[3],1200);assert.equal(drawArgs[4],720);
// Landscape image: horizontal slider reaches the full left/right crop extremes.
vm.runInContext('source={width:2400,height:1000};',sandbox);get('horizontal').value=0;
await vm.runInContext('prepare()',sandbox);assert.equal(drawArgs[1],0);
get('horizontal').value=1;await vm.runInContext('prepare()',sandbox);assert(Math.abs(drawArgs[1]-(2400-800/.48))<1e-8);
// Browsers without CompressionStream must send the same packed bitmap.
sandbox.CompressionStream=undefined;await vm.runInContext('prepare()',sandbox);
const plain=vm.runInContext('prepared',sandbox);assert.equal(plain.encoding,'identity');assert.deepEqual(Buffer.from(plain.bytes),raw);
assert.equal(vm.runInContext('requestId.length',sandbox),32);
console.log('phone crop, six-color packing, CRC, DEFLATE round-trip and fallback tests passed');

// Exercise the actual settings UI script, including execution acknowledgements
// and the network transition. A 202 alone must never be shown as applied.
const calls=[];
const state={battery_percent:83,photos:2,display_mode:'photo',refreshing:false,remaining_seconds:287,ssid:'Home <Wi-Fi>',ip:'192.168.1.42',timezone_offset:480,epoch:1794830400,clock_set:true,rtc:true,setting_results:[]};
let resultOK=true;
sandbox.fetch=async(url,options={})=>{
  calls.push({url,...options});
  if(options.method==='POST')return {ok:true,json:async()=>({queued:true,id:7})};
  return {ok:true,json:async()=>({...state,setting_results:[{id:7,ok:resultOK,message:resultOK?'已应用设置，请查看设备。':'相册还是空的，请先上传照片。'}]})};
};
await vm.runInContext('loadSettings()',sandbox);
assert.equal(get('device-network').textContent,'Home <Wi-Fi>'); // textContent, never interpreted as HTML
assert.equal(get('device-battery').textContent,'83%');
assert.equal(get('device-window').textContent,'4分 47秒');
assert.equal(calls[0].url,'/api/settings');
vm.runInContext("showPanel('settings')",sandbox);
assert.equal(get('upload-panel').hidden,true);assert.equal(get('system-panel').hidden,false);
assert.equal(get('tab-settings').attributes['aria-selected'],'true');
await Promise.resolve();await Promise.resolve();calls.length=0;
assert.equal(await vm.runInContext("changeSetting('calendar')",sandbox),true);
assert.equal(calls[0].method,'POST');assert.equal(JSON.parse(calls[0].body).action,'calendar');
assert.equal(calls[0].headers.Authorization,'Bearer '+'a'.repeat(32));
assert(calls.some(call=>call.url==='/api/status'));assert.equal(get('settings-status').textContent,'已应用设置，请查看设备。');
assert.equal(vm.runInContext('settingsBusy',sandbox),false);
resultOK=false;calls.length=0;
assert.equal(await vm.runInContext("changeSetting('photo')",sandbox),false);
assert(get('settings-status').textContent.includes('相册还是空的'));
assert.equal(calls.filter(call=>call.method==='POST').length,1); // never silently retry a setting
resultOK=true;get('timezone').value='345';calls.length=0;
await get('clock-form').onsubmit({preventDefault(){}});
const clockRequest=JSON.parse(calls.find(call=>call.method==='POST').body);
assert.equal(clockRequest.action,'sync_time');assert.equal(clockRequest.offset,345);assert(Number.isInteger(clockRequest.epoch));
calls.length=0;await vm.runInContext("changeSetting('factory_reset')",sandbox);
assert.equal(JSON.parse(calls[0].body).action,'factory_reset');
// Reconfigure is explicit, preserves the photo input, and stops polling the
// address that is about to disappear. Cancellation must send no request.
calls.length=0;sandbox.confirm=()=>false;
assert.equal(await vm.runInContext("changeSetting('reconfigure')",sandbox),false);assert.equal(calls.length,0);
sandbox.confirm=()=>true;
assert.equal(await vm.runInContext("changeSetting('reconfigure')",sandbox),true);
assert.equal(vm.runInContext('leavingNetwork',sandbox),true);assert.equal(get('send').disabled,true);
assert(get('settings-status').textContent.includes('192.168.4.1'));
calls.length=0;await vm.runInContext('poll()',sandbox);assert.equal(calls.length,0);
console.log('settings tabs, authenticated actions, execution acknowledgement, clock sync and network transition tests passed');
