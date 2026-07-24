/* 发送到桌面 — 真机验证第 1 步: 安全回归 + 进房间文件 tab + 触发 pin (不提交) */
const http = require('http');
const { execSync } = require('child_process');
const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
function adb(a) { return execSync('"' + ADB + '" -s 21770d7d ' + a, { encoding: 'utf8' }); }
function getJson(u) { return new Promise((res, rej) => { http.get(u, r => { let d = ''; r.on('data', c => d += c); r.on('end', () => res(JSON.parse(d))); }).on('error', rej); }); }
let ws, msgId = 0;
const pending = new Map();
function connect() {
  return new Promise((resolve, reject) => {
    const socks = adb('shell cat /proc/net/unix');
    const m = socks.match(/webview_devtools_remote_(\d+)/);
    if (!m) return reject(new Error('no webview socket'));
    try { adb('forward tcp:9222 localabstract:webview_devtools_remote_' + m[1]); } catch (e) {}
    http.get('http://localhost:9222/json', res => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => {
        const page = JSON.parse(d).find(t => t.url.includes('hermes-shell.html'));
        if (!page) return reject(new Error('page not found'));
        ws = new WebSocket(page.webSocketDebuggerUrl);
        ws.onopen = resolve; ws.onerror = reject;
        ws.onmessage = ev => { const mm = JSON.parse(ev.data); if (mm.id && pending.has(mm.id)) { pending.get(mm.id)(mm); pending.delete(mm.id); } };
      });
    }).on('error', reject);
  });
}
function evaljs(expression) {
  return new Promise((resolve, reject) => {
    const id = ++msgId;
    pending.set(id, m => {
      if (m.error) return reject(new Error(m.error.message));
      const r = m.result;
      if (r && r.exceptionDetails) return reject(new Error((r.exceptionDetails.exception && r.exceptionDetails.exception.description || r.exceptionDetails.text).slice(0, 600)));
      resolve(r && r.result ? r.result.value : undefined);
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  await connect();
  /* 安全回归: 路径遍历 / 不存在文件 / 非法 roomId */
  console.log('[安全] pinFileShortcut("desk","../../x","t") =',
    await evaljs("JSON.stringify(JSON.parse(HermesBridge.pinFileShortcut('desk','../../x','t')))"));
  console.log('[安全] 不存在文件 =',
    await evaljs("JSON.stringify(JSON.parse(HermesBridge.pinFileShortcut('r1784738681371','nope.html','t')))"));
  console.log('[安全] 非法 roomId =',
    await evaljs("JSON.stringify(JSON.parse(HermesBridge.pinFileShortcut('../etc','snake.html','t')))"));
  /* 进贪吃蛇 v2 房间 → 文件 tab */
  const rid = await evaljs(
    "(function(){var r=ROOMS.find(function(x){return x.name==='贪吃蛇 v2';});" +
    "if(!r)return null;enterRoom(r.id);return r.id;})()");
  if (!rid) { console.log('FAIL: 找不到贪吃蛇 v2 房间'); process.exit(3); }
  await sleep(800);
  await evaljs("setSubtab('files');'ok'");
  await sleep(1200);
  const files = await evaljs("JSON.stringify(B.listWorkFiles('" + rid + "').files||[])");
  console.log('房间 work 文件: ' + files);
  /* 模拟长按 → 操作菜单 → 发送到桌面 */
  const sheetOk = await evaljs(
    "(function(){openFileOpsSheet('snake.html','work');" +
    "var s=document.getElementById('fileOpsSheet');" +
    "var pin=document.getElementById('fopsPin');" +
    "var vis=s.classList.contains('open')&&pin.style.display!=='none';" +
    "return vis;})()");
  console.log('操作菜单弹出且含「发送到桌面」: ' + sheetOk);
  if (!sheetOk) { console.log('FAIL: 菜单未弹出'); process.exit(4); }
  /* 点「发送到桌面」→ 系统弹窗 */
  const pinRes = await evaljs(
    "(function(){document.getElementById('fopsPin').click();return 'clicked';})()");
  console.log('已点击发送到桌面: ' + pinRes);
  await sleep(2500);
  process.exit(0);
})().catch(e => { console.error('ERR: ' + e.message); process.exit(2); });
