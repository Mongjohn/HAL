// Real-browser checks for the bundled video-library UI, using a local mock HAL API.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { createServer } = require('node:http');
const { spawn } = require('node:child_process');

const assets = path.resolve('app/src/main/assets/dashboard-editor');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(read) {
  for (let i = 0; i < 200; i++) { const value = await read(); if (value) return value; await sleep(50); }
  throw new Error('Timed out');
}
async function browserChecks() {
  const el = id => document.getElementById(id);
  const expect = (value, message) => { if (!value) throw new Error(message); };
  const wait = async read => { for (let i = 0; i < 200; i++) { if (read()) return; await new Promise(r => setTimeout(r, 25)); } throw new Error('UI timeout: ' + el('video-status').textContent); };
  const input = (id, value) => { el(id).value = value; el(id).dispatchEvent(new Event('input', { bubbles: true })); };
  const add = (name, url) => { input('video-title', name); input('video-url', url); el('video-add').requestSubmit(); };
  window.confirm = () => true;
  await fetch('/__reset');
  await fetch('/__loadfail');
  expect(el('tab-dashboard').getAttribute('aria-selected') === 'true', 'dashboard should open first');
  expect(el('backgrounds-panel').hidden, 'background settings clutter the dashboard');
  el('tab-backgrounds').click();
  expect(el('tab-backgrounds').getAttribute('aria-selected') === 'true' && el('dashboard-panel').hidden, 'background workspace navigation');
  el('video-library').open = true;
  await wait(() => el('video-status').textContent.includes('load failed'));
  expect(!el('video-reload').disabled, 'failed initial load cannot be retried');
  el('video-reload').click();
  await wait(() => !el('video-fields').disabled);
  expect(el('video-save').disabled, 'unchanged library should not save');
  add('Entré æøå', 'https://example.org/first.mp4?token=a%2Bb');
  add('<img src=x onerror=alert(1)>', 'http://192.168.1.20/second.webm');
  expect(el('video-list').children.length === 2, 'add videos');
  expect(el('video-list').querySelectorAll('img').length === 0, 'unsafe title rendering');
  const option = (index, key) => el('video-list').children[index].querySelector(`[data-option="${key}"]`);
  expect(!option(0, 'background').checked && !option(1, 'screensaver').checked, 'new videos should be opt-in');
  for (const key of ['background', 'screensaver', 'randomStart']) option(0, key).click();
  option(1, 'screensaver').click();
  el('video-shuffle').click();
  input('video-switch', '5');
  el('video-save').click();
  expect(el('video-status').textContent.includes('at least 10'), 'invalid switch interval accepted');
  input('video-switch', '120');
  expect(!el('screensaver-timeout'), 'retired HAL timer still exposed');
  input('dashboard-dim', '55'); input('screensaver-dim', '85');
  input('playlist-title', 'Landscapes æøå'); el('playlist-add').requestSubmit();
  const playlist = el('playlist-list').firstElementChild;
  expect(!playlist.querySelector('[data-option="playlist-enabled"]').checked, 'new playlists should be opt-in');
  playlist.querySelector('[data-option="playlist-enabled"]').click();
  for (const member of playlist.querySelectorAll('[data-option="playlist-member"]')) member.click();
  expect(el('playlist-overview').textContent.includes('1 playlist enabled'), 'active playlist overview');
  el('tab-dashboard').click(); el('tab-backgrounds').click();
  expect(el('playlist-list').firstElementChild.querySelector('input[type=text]').value === 'Landscapes æøå', 'workspace switch lost unsaved draft');
  el('tab-settings').click();
  expect(!el('settings-panel').hidden && el('backgrounds-panel').hidden, 'keyboard workspace navigation');
  el('settings-transfer').open = true;
  expect(el('settings-transfer').scrollWidth <= el('settings-transfer').clientWidth, 'settings transfer overflow');
  el('tab-backgrounds').click();
  expect(!el('backgrounds-panel').hidden, 'keyboard return to backgrounds');
  const disclosure = el('video-list').children[0].querySelector('details');
  disclosure.open = false; disclosure.querySelector('summary').click();
  expect(disclosure.open, 'video disclosure is not usable');
  el('video-list').children[1].querySelector('[aria-label="Move video up"]').click();
  expect(el('video-list').children[1].querySelector('input').value === 'Entré æøå', 'order');
  const first = el('video-list').children[0].querySelector('input');
  first.value = 'Edited'; first.dispatchEvent(new Event('input', { bubbles: true }));
  const editedUrl = el('video-list').children[0].querySelector('input[type=url]');
  editedUrl.value = 'https://example.org/edited.webm?key=renewed'; editedUrl.dispatchEvent(new Event('input', { bubbles: true }));
  el('video-save').click();
  await wait(() => el('video-status').textContent === 'Video settings saved to the TV.');
  let saved = await (await fetch('/api/videos')).json();
  expect(saved.videos[0].title === 'Edited' && saved.videos[1].title === 'Entré æøå', 'saved Unicode/order');
  expect(saved.videos[1].url.endsWith('token=a%2Bb'), 'query token changed');
  expect(saved.videos[0].url.endsWith('key=renewed'), 'URL edit not saved');
  expect(!saved.videos[0].background && saved.videos[0].screensaver && !saved.videos[0].randomStart, 'per-video settings leaked');
  expect(saved.videos[1].background && saved.videos[1].screensaver && saved.videos[1].randomStart, 'per-video settings lost on reorder');
  expect(saved.playlists.length === 1 && saved.playlists[0].enabled && saved.playlists[0].videoIds.length === 2, 'playlist membership was not saved');
  expect(saved.shuffle && saved.switchSeconds === 120 && !Object.hasOwn(saved, 'idleMinutes') && saved.dashboardDim === 55 && saved.screensaverDim === 85, 'timing/dimming settings lost');
  el('video-list').children[0].querySelector('.controls button:last-child').click();
  expect(el('playlist-list').querySelectorAll('[data-option="playlist-member"]').length === 1, 'removed video remains in playlist picker');
  await fetch('/__stale');
  el('video-save').click();
  await wait(() => el('video-status').textContent.includes('changed'));
  expect(el('video-list').children.length === 1 && !el('video-save').disabled, 'stale save lost draft');
  el('video-reload').click();
  await wait(() => !el('video-fields').disabled && el('video-list').children.length === 2);
  option(0, 'background').click();
  await fetch('/__unconfirmed');
  el('video-save').click();
  await wait(() => el('video-status').textContent.includes('did not confirm'));
  expect(!el('video-save').disabled && option(0, 'background').checked, 'unconfirmed save lost draft');
  el('video-reload').click();
  await wait(() => !el('video-fields').disabled && el('video-save').disabled);
  expect(option(0, 'background').checked, 'reload did not show committed settings');
  const url = el('video-list').querySelector('input[type=url]');
  url.value = 'file:///private/video'; url.dispatchEvent(new Event('input', { bubbles: true }));
  el('video-save').click();
  expect(el('video-status').textContent.includes('valid direct HTTP'), 'invalid URL accepted');
  expect(document.documentElement.scrollWidth <= innerWidth + 1, 'horizontal page overflow');
  expect(el('video-fields').scrollWidth <= el('video-fields').clientWidth + 1, 'video controls overflow');
  return 'passed';
}

async function configurationChecks() {
  const el = id => document.getElementById(id);
  const expect = (value, message) => { if (!value) throw new Error(message); };
  const wait = async read => { for (let i=0;i<200;i++) { if(read()) return; await new Promise(r=>setTimeout(r,25)); } throw new Error('Configuration UI timeout'); };
  const form = section => document.querySelector(`form[data-section="${section}"]`);
  const input = (section,key,value) => { const f=form(section), field=f.elements.namedItem(key); if(field.type==='checkbox') field.checked=value; else field.value=value; field.dispatchEvent(new Event('input',{bubbles:true})); field.dispatchEvent(new Event('change',{bubbles:true})); return field; };
  const save = async section => { form(section).requestSubmit(); await wait(()=>form(section).querySelector('.config-status').textContent==='Saved to this TV.').catch(e=>{throw new Error(section+': '+form(section).querySelector('.config-status').textContent+' invalid='+[...form(section).elements].filter(i=>i.validity&&!i.validity.valid).map(i=>i.name));}); };
  await wait(()=>!!form('weather'));
  el('tab-weather').click();
  input('appearance','unfocusedOpacity','20');
  input('weather','dashboard','both'); input('weather','card','daily'); input('weather','screensaver','hourly');
  await fetch('/__config-delay');
  form('weather').requestSubmit();
  const reloadDuringSave=[...form('weather').querySelectorAll('button')].find(b=>b.textContent==='Reload saved settings');
  expect(reloadDuringSave.disabled,'reload remains enabled while a save is in flight');
  reloadDuringSave.click();
  await wait(()=>form('weather').querySelector('.config-status').textContent==='Saved to this TV.');
  expect(form('appearance').elements.namedItem('unfocusedOpacity').value==='20','switch/save lost another section draft');
  expect(form('appearance').dataset.dirty==='true','another section draft was cleared');
  input('appearance','focusedOpacity','60'); input('appearance','popupOpacity','80');
  await save('appearance');
  let saved=await (await fetch('/api/config')).json();
  expect(saved.sections.weather.card==='daily' && saved.sections.weather.dashboard==='both' && saved.sections.weather.screensaver==='hourly','three weather surfaces are not independent');
  expect(saved.sections.appearance.unfocusedOpacity===20 && saved.sections.appearance.focusedOpacity===60 && saved.sections.appearance.popupOpacity===80,'tile and popup opacity are not independent');
  el('tab-appearance').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true}));
  expect(el('tab-weather').getAttribute('aria-selected')==='true','vertical sidebar keyboard navigation');
  input('weather','card','hourly'); await fetch('/__config-stale'); form('weather').requestSubmit();
  await wait(()=>form('weather').querySelector('.config-status').textContent.includes('changed'));
  expect(form('weather').elements.namedItem('card').value==='hourly','stale save discarded weather draft');
  await fetch('/__config-read-delay');
  [...form('weather').querySelectorAll('button')].find(b=>b.textContent==='Reload saved settings').click();
  expect(form('weather').querySelector('fieldset').disabled && form('weather').querySelector('[type=submit]').disabled,'reload allows new edits that its response will discard');
  await wait(()=>form('weather').dataset.dirty==='false');
  input('weather','card','hourly'); await fetch('/__config-unconfirmed'); form('weather').requestSubmit();
  await wait(()=>form('weather').querySelector('.config-status').textContent.includes('did not confirm'));
  expect(form('weather').dataset.dirty==='true','unconfirmed save discarded draft');
  [...form('weather').querySelectorAll('button')].find(b=>b.textContent==='Reload saved settings').click();
  await wait(()=>form('weather').dataset.dirty==='false');
  const cameraUrl=form('cameras').querySelector('input[name="first-url"]');
  expect(cameraUrl.value==='' && form('ha-login').elements.namedItem('password').value==='','saved credentials were prefilled');
  const cameraName=form('cameras').querySelector('input[name="first-name"]'); cameraName.value='Køkken æøå'; cameraName.dispatchEvent(new Event('input',{bubbles:true}));
  await save('cameras'); saved=await (await fetch('/api/config')).json();
  expect(saved.sections.cameras.items[0].name==='Køkken æøå','camera Unicode edit lost');
  expect(saved.sections.cameras.items[0].url==='','editing a camera name supplied a saved credential');
  el('settings-search').value='card popup'; el('settings-search').dispatchEvent(new Event('input'));
  const search=el('settings-search-results').querySelector('button'); expect(!!search,'settings search missed popup forecast'); search.click();
  expect(el('tab-weather').getAttribute('aria-selected')==='true' && document.activeElement.name==='card','search did not navigate and focus setting');
  input('artwork','useLocal',true); await save('artwork');
  saved=await (await fetch('/api/config')).json(); expect(saved.sections.artwork.services.includes('Netflix'),'switching artwork source lost subscription choices');
  const actionSearch=form('homeassistant').querySelector('input[type=search]');
  actionSearch.value='MORGEN wake'; actionSearch.dispatchEvent(new Event('input',{bubbles:true}));
  const actionPicker=form('homeassistant').querySelector('select');
  expect(actionPicker.options.length===1 && actionPicker.value==='script.wake','friendly-name action search');
  [...form('homeassistant').querySelectorAll('button')].find(b=>b.textContent==='Add header action').click();
  input('ha-login','username','new-user'); input('ha-login','password','temporary-secret');
  await fetch('/__config-fail');
  [...form('ha-login').querySelectorAll('button')].find(b=>b.textContent==='Connect & save login').click();
  await wait(()=>form('ha-login').querySelector('.config-status').textContent.includes('Connection failed'));
  expect(form('ha-login').elements.namedItem('password').value==='temporary-secret','failed login request discarded password draft');
  [...form('ha-login').querySelectorAll('button')].find(b=>b.textContent==='Connect & save login').click();
  await wait(()=>!form('ha-login').elements.namedItem('code').parentElement.hidden);
  const restartLogin=[...form('ha-login').querySelectorAll('button')].find(b=>b.textContent==='Start login again');
  expect(!restartLogin.hidden,'pending MFA has no restart path'); restartLogin.click();
  expect(form('ha-login').elements.namedItem('code').parentElement.hidden,'MFA restart kept the expired challenge');
  input('ha-login','password','temporary-secret');
  [...form('ha-login').querySelectorAll('button')].find(b=>b.textContent==='Connect & save login').click();
  await wait(()=>!form('ha-login').elements.namedItem('code').parentElement.hidden);
  expect(form('ha-login').elements.namedItem('password').value==='','password retained after MFA challenge');
  input('ha-login','code','123456');
  [...form('ha-login').querySelectorAll('button')].find(b=>b.textContent==='Verify code & save login').click();
  await wait(()=>form('ha-login').querySelector('.config-status').textContent==='Saved to this TV.');
  expect(form('ha-login').elements.namedItem('username').value==='new-user','MFA login did not save');
  expect(form('homeassistant').querySelector('ol').textContent.includes('script.wake'),'login save discarded header action draft');
  await save('homeassistant');
  saved=await (await fetch('/api/config')).json(); expect(saved.sections.homeassistant.topBar[0]==='script.wake','header action draft did not save after login');
  el('tab-appearance').click();
  expect(document.documentElement.scrollWidth<=innerWidth+1,'settings page overflows horizontally');
  return 'passed';
}

async function main() {
  let configuration = { revision: 'config-0', sections: {
    appearance: {theme:'Glass',baseOpacity:null,focusedOpacity:null,unfocusedOpacity:null,popupOpacity:null,collapse:'Off',cycleColors:false},
    weather:{dashboard:'off',screensaver:'off',card:'off'}, artwork:{enabled:false,useLocal:false,region:'DK',services:['Netflix']},
    launcher:{catchHomeKey:false,reopenAfterHome:false}, maintenance:{logHours:12,showMemory:false},
    homeassistant:{url:'http://ha.local:8123',username:'existing',connected:true,method:'password',topBar:[]},
    cameras:{items:[{id:'first',name:'Entré æøå',url:'',sub:'',urlSaved:true,subSaved:true,tcp:true,audio:true,subAudio:false,stretch:false,subStretch:true}],selected:'first',hidden:[],seconds:15,showProgress:false,showHome:true,gridLive:true,minimizeStreams:false},
    frigate:{url:'https://frigate.local:8971',username:'viewer',passwordSaved:true,certificateSaved:true}
  }, choices:{services:['Netflix','Disney+'],cameraSeconds:[5,10,15,30,60],maxActions:5}, status:{version:'1.2.0',buildCode:17,builtAt:0,decoders:'H.264: 2',logBytes:1024,helperEnabled:false,helperRunning:false,homeAutoOff:false,watchNextAllowed:false,screensaver:{readable:true,enabled:true,component:'com.evofactory.hal/.ArtworkScreensaver'}} };
  let configSerial = 0, configUnconfirmed = false, configDelay = false, configFail = false, configReadDelay = false;
  let saved, unconfirmed = false, loadFail = false;
  const reset = () => { saved = { schema: 4, revision: 0, shuffle: false, switchSeconds: 0, dashboardDim: 66, screensaverDim: 72, playlists: [], dashboardForecast: "off", screensaverForecast: "off", focusedCardOpacity: null, unfocusedCardOpacity: null, videos: [] }; unconfirmed = false; loadFail = false; };
  reset();
  const server = createServer(async (request, response) => {
    const url = request.url.split('?')[0];
    response.setHeader('Cache-Control', 'no-store');
    if (url === '/') { response.setHeader('Content-Type', 'text/html; charset=utf-8'); return response.end(await fs.readFile(path.join(assets, 'index.html'))); }
    if (['/editor.js', '/editor.css', '/videos.js', '/config.js'].includes(url)) {
      response.setHeader('Content-Type', url.endsWith('.css') ? 'text/css' : 'application/javascript; charset=utf-8');
      return response.end(await fs.readFile(path.join(assets, url.slice(1))));
    }
    response.setHeader('Content-Type', 'application/json');
    if (url === '/__reset') { reset(); return response.end('{}'); }
    if (url === '/__stale') { saved.revision++; return response.end('{}'); }
    if (url === '/__unconfirmed') { unconfirmed = true; return response.end('{}'); }
    if (url === '/__loadfail') { loadFail = true; return response.end('{}'); }
    if (url === '/__config-read-delay') { configReadDelay = true; return response.end('{}'); }
    if (url === '/__config-delay') { configDelay = true; return response.end('{}'); }
    if (url === '/__config-fail') { configFail = true; return response.end('{}'); }
    if (url === '/__config-stale') { configuration.revision = 'config-' + ++configSerial; return response.end('{}'); }
    if (url === '/__config-unconfirmed') { configUnconfirmed = true; return response.end('{}'); }
    if (url === '/api/config') {
      if (request.method === 'GET') { if (configReadDelay) {configReadDelay=false;await sleep(300);} return response.end(JSON.stringify(configuration)); }
      let raw=''; for await (const chunk of request) raw += chunk;
      const body=JSON.parse(raw);
      if(configFail) { configFail=false; response.statusCode=503; return response.end(JSON.stringify({error:"Connection failed. Retry."})); }
      if(configDelay) { configDelay=false; await sleep(300); }
      if(body.revision!==configuration.revision) {response.statusCode=400;return response.end(JSON.stringify({error:'Settings changed. Reload this section before saving.'}));}
      if(body.section==='ha-login') {
        if(!body.values.flow) return response.end(JSON.stringify({needsCode:true,flow:'mfa-flow',message:'Enter authenticator code.'}));
        if(body.values.code!=='123456') {response.statusCode=400;return response.end(JSON.stringify({error:'Incorrect code'}));}
        configuration.sections.homeassistant.username=body.values.username;
      } else if(body.section==='action') {} else if(body.section==='ha-logout') configuration.sections.homeassistant.connected=false;
      else configuration.sections[body.section]={...configuration.sections[body.section],...body.values};
      configuration.revision='config-'+ ++configSerial;
      if(configUnconfirmed) {configUnconfirmed=false;return response.end('{}');}
      return response.end(JSON.stringify({saved:true,configuration}));
    }
    if (url === '/api/state') return response.end(JSON.stringify({ revision: 'dashboard', canRestore: { home: false, assistant: false },
      home: { pages: [{ title: 'Home', columns: 1, cards: [] }] }, assistant: { pages: [] }, apps: [] }));
    if (url === '/api/catalog') return response.end(JSON.stringify({entities:[{id:'scene.evening',name:'Evening scene',domain:'scene'},{id:'script.wake',name:'Morgen æøå',domain:'script'}],templates:[]}));
    if (url === '/api/videos') {
      if (request.method === 'GET') {
        if (loadFail) { loadFail = false; response.statusCode = 503; return response.end('{"error":"Video library load failed."}'); }
        return response.end(JSON.stringify(saved));
      }
      let body = ''; for await (const chunk of request) body += chunk;
      const draft = JSON.parse(body);
      if (draft.revision !== saved.revision) { response.statusCode = 400; return response.end('{"error":"The video library changed. Reload it before saving again."}'); }
      saved = { ...draft, revision: draft.revision + 1 };
      if (unconfirmed) { unconfirmed = false; return response.end('{}'); }
      return response.end(JSON.stringify({ saved: true, settings: saved }));
    }
    response.statusCode = 404; response.end('{}');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  const profile = await fs.mkdtemp(path.resolve('build/video-editor-chrome-'));
  const browser = spawn(process.env.HAL_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    ['--headless=new', '--disable-extensions', '--disable-gpu', '--remote-debugging-port=0', '--user-data-dir=' + profile, origin],
    { stdio: 'ignore', windowsHide: true });
  let socket;
  try {
    const port = (await until(() => fs.readFile(path.join(profile, 'DevToolsActivePort'), 'utf8').catch(() => null))).split('\n')[0];
    const targets = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
    socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
    await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
    let sequence = 0; const pending = new Map();
    socket.onmessage = event => { const message = JSON.parse(event.data); if (message.id) pending.get(message.id)?.(message); else if (message.method === 'Page.javascriptDialogOpening') call('Page.handleJavaScriptDialog', {accept:true}).catch(() => {}); };
    const call = async (method, params) => {
      const id = ++sequence;
      const message = await new Promise(resolve => { pending.set(id, resolve); socket.send(JSON.stringify({ id, method, params })); });
      pending.delete(id); if (message.error) throw new Error(JSON.stringify(message.error)); return message.result;
    };
    await call('Page.enable', {});
    const evaluate = async expression => {
      const result = await call('Runtime.evaluate', { expression, returnByValue: true });
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || 'Browser evaluation failed');
      return result.result.value;
    };
    const pointer = (type, x, y, buttons) => call('Input.dispatchMouseEvent', { type, x, y, buttons, ...(type === 'mouseMoved' ? {} : { button: 'left', clickCount: 1 }) });
    async function gesture(selector, touch = false, horizontalStep = 1) {
      await evaluate('document.querySelector(' + JSON.stringify(selector) + ').scrollIntoView({block:"center",inline:"nearest"})');
      await sleep(40);
      const geometry = await evaluate('(() => { const b=document.querySelector(' + JSON.stringify(selector) + ').getBoundingClientRect(), s=getComputedStyle(document.getElementById("grid"));return {x:b.left+b.width/2,y:b.top+b.height/2,dx:parseFloat(s.gridTemplateColumns)+parseFloat(s.columnGap),dy:parseFloat(s.gridTemplateRows)+parseFloat(s.rowGap)};})()');
      const send = async (type, x, y, buttons) => touch
        ? call('Input.dispatchTouchEvent', {type: type === 'mousePressed' ? 'touchStart' : type === 'mouseReleased' ? 'touchEnd' : 'touchMove', touchPoints: type === 'mouseReleased' ? [] : [{x,y}]})
        : pointer(type,x,y,buttons);
      if (!touch) await pointer('mouseMoved', geometry.x, geometry.y, 0);
      await send('mousePressed', geometry.x, geometry.y, 1);
      for (let n=1;n<=10;n++) { await send('mouseMoved',geometry.x+geometry.dx*horizontalStep*n/10,geometry.y+geometry.dy*n/10,1); await sleep(12); }
      await send('mouseReleased', geometry.x+geometry.dx*horizontalStep, geometry.y+geometry.dy, 0);
      await sleep(60);
    }
    for (const width of [Number(process.env.HAL_TEST_WIDTH)]) {
      await call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
      await call('Page.navigate', { url: origin + '/?width=' + width });
      await until(async () => (await call('Runtime.evaluate', { expression: `location.search === '?width=${width}' && document.readyState === 'complete'` })).result.value);
      const result = await call('Runtime.evaluate', { expression: '(' + browserChecks + ')()', awaitPromise: true, returnByValue: true });
      assert.equal(result.result.value, 'passed', result.exceptionDetails?.exception?.description);
      await call('Runtime.evaluate', { expression: 'document.getElementById("video-fields").scrollIntoView()' });
      const png = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
      await fs.writeFile(path.resolve(`build/video-editor-${width}.png`), Buffer.from(png.data, 'base64'));
      console.log(`Video library checks passed at ${width}px`);
      const config = await call('Runtime.evaluate', {expression:'(' + configurationChecks + ')()',awaitPromise:true,returnByValue:true});
      assert.equal(config.result.value,'passed',config.exceptionDetails?.exception?.description);
      await evaluate('document.getElementById("tab-appearance").click();document.getElementById("appearance-panel").scrollIntoView({block:"start"})');
      const settingsPng=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
      await fs.writeFile(path.resolve(`build/settings-editor-${width}.png`),Buffer.from(settingsPng.data,'base64'));
      if (process.env.HAL_CAPTURE_SETTINGS) {
        for (const section of ['weather','homeassistant','cameras','backgrounds','launcher','maintenance','frigate']) {
          await evaluate('document.getElementById("tab-' + section + '").click();window.scrollTo(0,0)');
          const screenshot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
          await fs.writeFile(path.resolve(`build/settings-${section}-${width}.png`),Buffer.from(screenshot.data,'base64'));
        }
      }
      await call('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:1});
      const touchTarget=await evaluate('(() => {const t=document.getElementById("tab-weather");t.scrollIntoView({block:"center"});const b=t.getBoundingClientRect();return {x:b.left+b.width/2,y:b.top+b.height/2};})()');
      await call('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[touchTarget]});
      await call('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
      assert.equal(await evaluate('document.getElementById("tab-weather").getAttribute("aria-selected")'),'true','touch navigation failed');
      await call('Emulation.setTouchEmulationEnabled',{enabled:false});
      console.log(`Configuration and touch checks passed at ${width}px`);
      await evaluate('document.getElementById("tab-dashboard").click();document.getElementById("view-settings").open=true;const columns=document.getElementById("columns");columns.value=4;columns.dispatchEvent(new Event("change"));document.getElementById("add-card").click();document.getElementById("view-settings").open=false');
      await gesture('.tile .tile-title');
      const moved = await evaluate('({x:Number(document.getElementById("x").value),y:Number(document.getElementById("y").value),h:Number(document.getElementById("h").value)})');
      assert.ok(moved.x >= 2 && moved.y >= 2, 'real diagonal drag failed: ' + JSON.stringify(moved));
      await gesture('.tile .resize');
      const resized = await evaluate('({w:Number(document.getElementById("w").value),h:Number(document.getElementById("h").value)})');
      assert.ok(resized.w >= 2 && resized.h > moved.h, 'real resize failed: ' + JSON.stringify(resized));
      await evaluate('document.getElementById("dashboard-panel").scrollIntoView({block:"start"})');
      const dashboard = await call('Page.captureScreenshot', {format:'png', captureBeyondViewport:false});
      await fs.writeFile(path.resolve(`build/dashboard-editor-${width}.png`), Buffer.from(dashboard.data,'base64'));
      await call('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:1});
      await gesture('.tile .tile-title',true,0);
      const touchMoved=await evaluate('Number(document.getElementById("y").value)');
      assert.ok(touchMoved>moved.y,'touch card drag failed');
      const oldTouchHeight=await evaluate('Number(document.getElementById("h").value)');
      await gesture('.tile .resize',true,0);
      assert.ok(await evaluate('Number(document.getElementById("h").value)')>oldTouchHeight,'touch card resize failed');
      await call('Emulation.setTouchEmulationEnabled',{enabled:false});
      console.log(`Dashboard mouse/touch checks passed at ${width}px`);
    }
    await call('Browser.close', {});
  } finally { socket?.close(); browser.kill(); await new Promise(resolve => server.close(resolve)); }
}
async function runWidths() {
  if (process.env.HAL_TEST_WIDTH) return main();
  for (const width of [1920, 1280, 420]) await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [__filename], {env:{...process.env, HAL_TEST_WIDTH:String(width)},stdio:'inherit',windowsHide:true});
    child.on('error', reject); child.on('exit', code => code === 0 ? resolve() : reject(new Error('Browser check failed at ' + width + 'px')));
  });
}
runWidths().catch(error => { console.error(error); process.exitCode = 1; });
