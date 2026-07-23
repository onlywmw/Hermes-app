/* e2e: app.package — 贪吃蛇 → HTML → 签名 APK 落房间目录 */
const WS = require('ws');
const http = require('http');
const { execSync } = require('child_process');
const ADB = 'C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe';
let ws, msgId = 0; const pending = new Map();
function connect() {
  return new Promise((resolve, reject) => {
    http.get('http://localhost:9222/json', res => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => {
        const page = JSON.parse(d).find(t => t.url.includes('hermes-shell'));
        ws = new WS(page.webSocketDebuggerUrl, { perMessageDeflate: false });
        ws.on('open', resolve); ws.on('error', reject);
        ws.on('message', raw => { const m = JSON.parse(raw); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } });
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
      if (r && r.exceptionDetails) return reject(new Error('页面异常'));
      resolve(r && r.result ? r.result.value : undefined);
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
function assert(c, m) { if (!c) throw new Error(m || '断言失败'); }

(async () => {
  await connect();
  const rid = await evaljs(`(function(){
    closeAllSheets();genCounter++;
    var r=ROOMS.find(function(x){return x.id==='agente2e';});
    enterRoom(r.id);clearRoomHistory(r.id);return r.id;
  })()`);
  await sleep(300);
  await evaljs(`$('msgInput').value='做一个贪吃蛇网页游戏, 单文件 HTML, 最后打包成 APK';$('btnSend').click()`);
  console.log('任务已发, 等计划卡...');
  let plan = false;
  const t0 = Date.now();
  while (Date.now() - t0 < 90000) {
    await sleep(3000);
    plan = await evaljs(`document.querySelectorAll('#chatBody .plan-card').length>0`);
    if (plan) break;
  }
  assert(plan, '90s 无计划卡');
  console.log('计划卡:', await evaljs(`(function(){var c=document.querySelectorAll('#chatBody .plan-card');return c[c.length-1].querySelector('.ph').textContent;})()`));
  await evaljs(`(function(){var c=document.querySelectorAll('#chatBody .plan-card');c[c.length-1].querySelector('.btn-acc').click();return true;})()`);
  console.log('已批准, 等执行+打包 (≤300s, 签名耗时)...');
  let done = false;
  const t1 = Date.now();
  while (Date.now() - t1 < 300000) {
    await sleep(4000);
    const st = await evaljs(`(function(){
      var txt=$('chatBody').textContent;
      return {
        steps:document.querySelectorAll('#chatBody .toolcall').length,
        pkg:txt.indexOf('app.package')>=0,
        deliver:document.querySelectorAll('#chatBody .deliver-card').length,
        fail:txt.indexOf('任务失败')>=0
      };
    })()`);
    process.stdout.write('\r  步骤=' + st.steps + ' app.package=' + st.pkg + ' 交付=' + st.deliver + '   ');
    if (st.fail) break;
    if (st.deliver > 0) { done = true; break; }
  }
  console.log('');
  assert(done, '300s 内未交付');
  const files = await evaljs(`B.listWorkFiles('${rid}').files.map(function(f){return f.name+'('+f.size+')';})`);
  console.log('房间产出:', JSON.stringify(files));
  assert((files || []).some(f => f.indexOf('snake.html') >= 0), '无 HTML 产出');
  const apk = (files || []).find(f => f.indexOf('.apk') >= 0);
  assert(apk, '无 APK 产出 (app.package 未执行?)');
  console.log('✓ APK 已产出:', apk);
  console.log('\n===== app.package e2e 通过 =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); process.exit(1); });
