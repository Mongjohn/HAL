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
  expect(saved.shuffle && saved.switchSeconds === 120 && !Object.hasOwn(saved, 'idleMinutes') && saved.dashboardDim === 55 && saved.screensaverDim === 85, 'timing/dimming settings lost');
  el('video-list').children[0].querySelector('.controls button:last-child').click();
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

async function main() {
  let saved, unconfirmed = false, loadFail = false;
  const reset = () => { saved = { schema: 3, revision: 0, shuffle: false, switchSeconds: 0, dashboardDim: 66, screensaverDim: 72, videos: [] }; unconfirmed = false; loadFail = false; };
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
    socket.onmessage = event => { const message = JSON.parse(event.data); if (message.id) pending.get(message.id)?.(message); };
    const call = async (method, params) => {
      const id = ++sequence;
      const message = await new Promise(resolve => { pending.set(id, resolve); socket.send(JSON.stringify({ id, method, params })); });
      pending.delete(id); if (message.error) throw new Error(JSON.stringify(message.error)); return message.result;
    };
    for (const width of [1280, 420]) {
      await call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
      await call('Page.navigate', { url: origin + '/?width=' + width });
      await until(async () => (await call('Runtime.evaluate', { expression: `location.search === '?width=${width}' && document.readyState === 'complete'` })).result.value);
      const result = await call('Runtime.evaluate', { expression: '(' + browserChecks + ')()', awaitPromise: true, returnByValue: true });
      assert.equal(result.result.value, 'passed', result.exceptionDetails?.exception?.description);
      await call('Runtime.evaluate', { expression: 'document.getElementById("video-fields").scrollIntoView()' });
      const png = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
      await fs.writeFile(path.resolve(`build/video-editor-${width}.png`), Buffer.from(png.data, 'base64'));
      console.log(`Video library checks passed at ${width}px`);
    }
    await call('Browser.close', {});
  } finally { socket?.close(); browser.kill(); await new Promise(resolve => server.close(resolve)); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
