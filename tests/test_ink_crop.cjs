const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const elements = new Map();
const calls = [];
const context = {fillRect(){},drawImage(){calls.push('image');},fillText(){calls.push('guide');}};
function element(id) {
  if (!elements.has(id)) elements.set(id, {checked:true,value:'1',hidden:true,addEventListener(){},getContext(){return context;},toDataURL(type){assert.equal(type,'image/jpeg');return 'data:image/jpeg;base64,YQ==';}});
  return elements.get(id);
}
const sandbox = {document:{getElementById:element},statusEl:{},background:'',Blob,Uint8Array,atob,run(){}};
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync('server/app/ink_crop.js','utf8'),sandbox);
vm.runInContext(`cropImage={width:400,height:800};resetCrop();`,sandbox);
assert.equal(vm.runInContext('cropScale',sandbox),2);
assert.equal(vm.runInContext('cropX',sandbox),0);
assert.equal(vm.runInContext('cropY',sandbox),-560);
vm.runInContext('cropX=500;cropY=-9999;clampCrop()',sandbox);
assert.equal(vm.runInContext('cropX',sandbox),0);
assert.equal(vm.runInContext('cropY',sandbox),-1120);
calls.length=0;
vm.runInContext('drawCrop(false)',sandbox);
assert.deepEqual(calls,['image']);
element('token').value='';
element('crop-confirm').onclick();
assert.equal(vm.runInContext('cropPending',sandbox),false);
assert.equal(vm.runInContext('croppedPhoto.type',sandbox),'image/jpeg');
console.log('Crop bounds, portrait scaling, clean export and confirmation passed');
