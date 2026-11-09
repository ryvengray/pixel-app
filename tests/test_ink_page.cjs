// Exercise the preview/send workflow without a browser or a live device.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const elements = new Map();
const requests = [];
const ratings = ['like', 'easy', 'skip'].map(rating => ({dataset:{rating},textContent:rating}));
function element(id) {
  if (!elements.has(id)) elements.set(id, {
    value: id === 'letter-layout' ? 'auto' : id === 'device' ? 'desk-01' : '',
    checked:false, hidden:false, textContent:'',
    addEventListener(){}, querySelector(){return element('submit');}, getContext(){return {};},
  });
  return elements.get(id);
}
const fresh = {use_gallery:true, weather:'多云', letter:{day:'2026-11-14',layout:'orbit',category:'周末故事',title:'一封新来信'}};
async function fetch(url, options = {}) {
  requests.push({url, options});
  return {
    ok:!url.endsWith('/auth/session'), status:url.endsWith('/auth/session') ? 401 : 200,
    async json(){return url.endsWith('/today/data') ? structuredClone(fresh) : {version:'test'};},
    async blob(){return new Blob(['png']);}, async text(){return '';},
  };
}
const sandbox = {
  document:{getElementById:element,querySelectorAll(){return ratings;}},
  location:{pathname:'/ink-screen'}, fetch, Blob, Uint8Array, atob,
  URL:{createObjectURL(){return 'blob:preview';},revokeObjectURL(){}},
};
vm.createContext(sandbox);
const source = fs.readFileSync('server/app/ink.py','utf8').split('<script>')[1].split('</script>')[0]
  .replace('__CROP_SCRIPT__',fs.readFileSync('server/app/ink_crop.js','utf8'));
vm.runInContext(source, sandbox);
(async () => {
  // Choosing illustrations before the first load must survive weekend defaults.
  element('clear-photo').onclick();
  element('letter-layout').value = 'postcard';
  await vm.runInContext('run(false)', sandbox);
  await vm.runInContext('run(true)', sandbox);
  const previews = requests.filter(r => r.url.endsWith('/preview.png'));
  const sends = requests.filter(r => r.url.endsWith('/desk-01/today'));
  assert.equal(previews.length, 1);
  assert.equal(sends.length, 1);
  const preview = JSON.parse(previews[0].options.body);
  const sent = JSON.parse(sends[0].options.body);
  assert.deepEqual(preview, sent);
  assert.equal(sent.letter.layout, 'postcard');
  assert.equal(sent.use_gallery, false);
  assert.equal(sent.background, '');
  await ratings[1].onclick();
  const feedback = requests.find(r => r.url.endsWith('/letters/2026-11-14/feedback'));
  assert.deepEqual(JSON.parse(feedback.options.body), {rating:'easy'});
  assert.match(element('feedback-status').textContent, /已记录/);
  console.log('Layout selection, illustration preference, preview/send parity and parent feedback passed');
})().catch(error => {console.error(error); process.exitCode = 1;});
