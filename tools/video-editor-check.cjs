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
  el('card-focused-opacity').value = '80'; el('card-focused-opacity').dispatchEvent(new Event('change'));
  el('card-unfocused-opacity').value = '20'; el('card-unfocused-opacity').dispatchEvent(new Event('change'));
  el('dashboard-forecast').value = 'hourly'; el('dashboard-forecast').dispatchEvent(new Event('change'));
  el('screensaver-forecast').value = 'daily'; el('screensaver-forecast').dispatchEvent(new Event('change'));
  input('playlist-title', 'Landscapes æøå'); el('playlist-add').requestSubmit();
  const playlist = el('playlist-list').firstElementChild;
  expect(!playlist.querySelector('[data-option="playlist-enabled"]').checked, 'new playlists should be opt-in');
  playlist.querySelector('[data-option="playlist-enabled"]').click();
  for (const member of playlist.querySelectorAll('[data-option="playlist-member"]')) member.click();
  expect(el('playlist-overview').textContent.includes('1 playlist enabled'), 'active playlist overview');
  el('tab-dashboard').click(); el('tab-backgrounds').click();
  expect(el('playlist-list').firstElementChild.querySelector('input[type=text]').value === 'Landscapes æøå', 'workspace switch lost unsaved draft');
  el('tab-backgrounds').dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowRight',bubbles:true}));
  expect(!el('settings-panel').hidden && el('backgrounds-panel').hidden, 'keyboard workspace navigation');
  el('settings-transfer').open = true;
  expect(el('settings-transfer').scrollWidth <= el('settings-transfer').clientWidth, 'settings transfer overflow');
  el('tab-settings').dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowLeft',bubbles:true}));
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
  expect(saved.focusedCardOpacity === 80 && saved.unfocusedCardOpacity === 20, 'independent focus opacity was not saved');
  expect(saved.dashboardForecast === 'hourly' && saved.screensaverForecast === 'daily', 'independent weather choices were not saved');
  expect(saved.shuffle && saved.switchSeconds === 120 && !Object.hasOwn(saved, 'idleMinutes') && saved.dashboardDim === 55 && saved.screensaverDim === 85, 'timing/dimming settings lost');
  el('video-list').children[0].querySelector('.controls button:last-child').click();
  expect(el('playlist-list').querySelectorAll('[data-option="playlist-member"]').length === 1, 'removed video remains in playlist picker');
  await fetch('/__stale');
  el('video-save').click();
  await wait(() => el('video-status').textContent.includes('changed'));
  expect(el('video-list').children.length === 1 && !el('video-save').disabled, 'stale save lost draft');
  el('video-reload').click();
  await wait(() => !el('video-fields').disabled && el('video-list').children.length === 2);
  expect(el('dashboard-forecast').value === 'hourly' && el('screensaver-forecast').value === 'daily', 'weather choices lost on reload');
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

async function main() {
  let saved, unconfirmed = false, loadFail = false;
  const reset = () => { saved = { schema: 4, revision: 0, shuffle: false, switchSeconds: 0, dashboardDim: 66, screensaverDim: 72, playlists: [], dashboardForecast: "off", screensaverForecast: "off", focusedCardOpacity: null, unfocusedCardOpacity: null, videos: [] }; unconfirmed = false; loadFail = false; };
  reset();
  const server = createServer(async (request, response) => {
    const url = request.url.split('?')[0];
    response.setHeader('Cache-Control', 'no-store');
    if (url === '/') { response.setHeader('Content-Type', 'text/html; charset=utf-8'); return response.end(await fs.readFile(path.join(assets, 'index.html'))); }
    if (['/editor.js', '/editor.css', '/videos.js'].includes(url)) {
      response.setHeader('Content-Type', url.endsWith('.css') ? 'text/css' : 'application/javascript; charset=utf-8');
      return response.end(await fs.readFile(path.join(assets, url.slice(1))));
    }
    response.setHeader('Content-Type', 'application/json');
    if (url === '/__reset') { reset(); return response.end('{}'); }
    if (url === '/__stale') { saved.revision++; return response.end('{}'); }
    if (url === '/__unconfirmed') { unconfirmed = true; return response.end('{}'); }
    if (url === '/__loadfail') { loadFail = true; return response.end('{}'); }
    if (url === '/api/state') return response.end(JSON.stringify({ revision: 'dashboard', canRestore: { home: false, assistant: false },
      home: { pages: [{ title: 'Home', columns: 1, cards: [] }] }, assistant: { pages: [] }, apps: [] }));
    if (url === '/api/catalog') return response.end('{"entities":[],"templates":[]}');
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
    async function gesture(selector) {
      await evaluate('document.querySelector(' + JSON.stringify(selector) + ').scrollIntoView({block:"center",inline:"nearest"})');
      await sleep(40);
      const geometry = await evaluate('(() => { const b=document.querySelector(' + JSON.stringify(selector) + ').getBoundingClientRect(), s=getComputedStyle(document.getElementById("grid"));return {x:b.left+b.width/2,y:b.top+b.height/2,dx:parseFloat(s.gridTemplateColumns)+parseFloat(s.columnGap),dy:parseFloat(s.gridTemplateRows)+parseFloat(s.rowGap)};})()');
      await pointer('mouseMoved', geometry.x, geometry.y, 0);
      await pointer('mousePressed', geometry.x, geometry.y, 1);
      for (let n=1;n<=10;n++) { await pointer('mouseMoved',geometry.x+geometry.dx*n/10,geometry.y+geometry.dy*n/10,1); await sleep(12); }
      await pointer('mouseReleased', geometry.x+geometry.dx, geometry.y+geometry.dy, 0);
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
      console.log(`Dashboard pointer checks passed at ${width}px`);
    }
    await call('Browser.close', {});
  } finally { socket?.close(); browser.kill(); await new Promise(resolve => server.close(resolve)); }
}
async function runWidths() {
  if (process.env.HAL_TEST_WIDTH) return main();
  for (const width of [1280, 420]) await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [__filename], {env:{...process.env, HAL_TEST_WIDTH:String(width)},stdio:'inherit',windowsHide:true});
    child.on('error', reject); child.on('exit', code => code === 0 ? resolve() : reject(new Error('Browser check failed at ' + width + 'px')));
  });
}
runWidths().catch(error => { console.error(error); process.exitCode = 1; });
