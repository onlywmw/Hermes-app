/* e2e: 注册表动作类 — agent 开手电+查电量 (device.cmd ACTION/READONLY 分级) */
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
    if(!r)return null;
    enterRoom(r.id);return r.id;
  })()`);
  assert(rid, 'Agent验收房不存在');
  await evaljs(`clearRoomHistory('${rid}')`);  // 清空历史卡片, 防点到旧计划卡
  await sleep(300);
  await evaljs(`$('msgInput').value='打开手电筒, 然后报告当前电量';$('btnSend').click()`);
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
  /* 点最后一张计划卡的批准 (历史里可能有多张) */
  await evaljs(`(function(){var c=document.querySelectorAll('#chatBody .plan-card');c[c.length-1].querySelector('.btn-acc').click();return true;})()`);
  console.log('已批准, 等执行 (≤180s)...');
  let done = false, failInfo = '';
  const t1 = Date.now();
  while (Date.now() - t1 < 180000) {
    await sleep(3000);
    const st = await evaljs(`(function(){
      var cards=document.querySelectorAll('#chatBody .toolcall');
      var dev=0,bat=false,torchOk=false;
      cards.forEach(function(c){
        var t=c.textContent;
        if(t.indexOf('device.cmd')>=0){
          dev++;
          if(t.indexOf('手电筒')>=0||t.indexOf('torch')>=0)torchOk=t.indexOf('exit 0')>=0;
          if(t.indexOf('电量')>=0&&t.indexOf('%')>=0)bat=true;
        }
      });
      var txt=$('chatBody').textContent;
      return {dev:dev,bat:bat,torchOk:torchOk,
        deliver:document.querySelectorAll('#chatBody .deliver-card').length,
        fail:txt.indexOf('任务失败')>=0||txt.indexOf('已停止')>=0};
    })()`);
    process.stdout.write('\r  device.cmd 卡=' + st.dev + ' torchOk=' + st.torchOk + ' 电量=' + st.bat + ' 交付=' + st.deliver + '   ');
    if (st.fail) { failInfo = '任务失败/停止'; break; }
    if (st.deliver > 0) { done = true; break; }
  }
  console.log('');
  assert(failInfo === '', failInfo);
  assert(done, '180s 内未交付');
  const fin = await evaljs(`(function(){
    var cards=document.querySelectorAll('#chatBody .toolcall');
    var dev=0,torchOk=false,bat=false;
    cards.forEach(function(c){
      var t=c.textContent;
      if(t.indexOf('device.cmd')>=0){
        dev++;
        if((t.indexOf('手电筒')>=0||t.indexOf('torch')>=0||t.indexOf('已打开')>=0)&&t.indexOf('exit 0')>=0)torchOk=true;
        if(t.indexOf('电量')>=0&&t.indexOf('%')>=0)bat=true;
      }
    });
    return {dev:dev,torchOk:torchOk,bat:bat};
  })()`);
  assert(fin.dev >= 1, '大脑未调用 device.cmd (仍在绕 HTML?)');
  assert(fin.torchOk, '手电动作未成功执行');
  assert(fin.bat, '未报告电量');
  // 验手电已关闭? (任务里没要求关, 仅验证开过; 实测 logcat 应该有 torch 记录)
  const log = execSync('"' + ADB + '" shell logcat -d -s MOV:D', { encoding: 'utf8' });
  console.log('logcat torch 痕迹:', /手电|torch/i.test(log) ? '有' : '无');
  console.log('\n===== 动作类 e2e 通过 =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); process.exit(1); });
