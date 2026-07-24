/* 贪吃蛇任务修复后全链路真机验证 (不提交)
   流程: 建 council 房(默认模型+智谱) → 发"写一款贪吃蛇小游戏" → 等讨论/汇总/计划卡
        → 批准 → 逐张确认 file.write 预览卡 → 等交付卡 → 校验文件落盘 → 截图 */
const http = require('http');
const fs = require('fs');
const { execSync } = require('child_process');
const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
const SHOT_DIR = 'C:\\Users\\Administrator\\Documents\\kimi\\workspace';
function adb(a) { return execSync('"' + ADB + '" -s 21770d7d ' + a, { encoding: 'utf8' }); }
function shot(name) { execSync('bash -c "\'' + ADB + '\' -s 21770d7d exec-out screencap -p > \'' + SHOT_DIR + '\\' + name + '\'"'); console.log('[截图] ' + name); }
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
  /* 0. 模型注册表: 默认模型 + 智谱 */
  const models = await evaljs("B.listModels().map(function(m){return {id:m.id,name:m.name,provider:m.provider,isDefault:!!m.isDefault};})");
  console.log('模型注册表:', JSON.stringify(models));
  const def = models.find(m => m.isDefault) || models[0];
  const zp = models.find(m => m.provider === 'zhipu');
  const aiIds = zp && zp.id !== def.id ? [def.id, zp.id] : [def.id];
  console.log('建房成员: ' + aiIds.join(' + ') + ' (默认=' + def.name + ')');
  /* 1. 建 council 房并进入 */
  const rid = await evaljs(
    "(function(){var id='r'+Date.now();var ai=" + JSON.stringify(aiIds) + ";" +
    "var seed=[{t:'sys',h:'COUNCIL 已召开 · 多模型 AI 团队 · MOV 主持'},{t:'agent',who:'mov',h:'AI 团队已就绪。'}];" +
    "ROOMS.splice(1,0,{id:id,name:'贪吃蛇 v2',mode:'council',members:{human:[{who:'you',role:'owner'}],ai:ai}," +
    "phase:'讨论中',last:'Council 已就绪',time:'现在',unread:0,played:false,msgs:[],seed:seed});" +
    "B.initRoomStorage(id);B.initRoom(id,'贪吃蛇 v2','',ai);renderRooms();persistRooms();enterRoom(id);return id;})()");
  console.log('房间已建: ' + rid);
  await sleep(1500);
  shot('mov-snake-1.png');
  /* 2. 发任务 */
  await evaljs("$('msgInput').value='写一款贪吃蛇小游戏';sendMsg();'sent'");
  console.log('已发送任务, 等待 Council 讨论 + 汇总 (豆包较慢, 最长 300s)…');
  /* 3. 等计划卡 */
  let planOk = false, lastLog = '';
  for (let i = 0; i < 100; i++) {
    await sleep(3000);
    const st = await evaljs(
      "(function(){var r=ROOMS.find(function(x){return x.id==='" + rid + "';});" +
      "var pc=document.querySelector('#chatBody .plan-card');" +
      "var n=(r.msgData||[]).length;" +
      "var last=n?String(r.msgData[n-1].h||'').slice(0,80):'';" +
      "return {phase:r.phase,msgN:n,plan:!!pc,last:last};})()");
    const line = JSON.stringify(st);
    if (line !== lastLog) { console.log('  [' + (i * 3) + 's] ' + line); lastLog = line; }
    if (st.plan) { planOk = true; break; }
    if (st.phase === '待评审' && !st.plan) { console.log('FAIL: 汇总未产出有效步骤 JSON (待评审无计划卡)'); break; }
  }
  if (!planOk) { shot('mov-snake-fail-noplan.png'); process.exit(3); }
  shot('mov-snake-2.png');
  /* 4. 批准计划 */
  await evaljs("(function(){var b=document.querySelector('#chatBody .plan-card .btn-acc');b.click();return b.textContent;})()");
  console.log('已批准, 等待 file.write 预览卡…');
  /* 5. 逐张确认预览卡, 直到交付卡出现 */
  let confirmed = 0, delivered = false;
  for (let i = 0; i < 60; i++) {
    await sleep(2000);
    const st = await evaljs(
      "(function(){var btns=[].slice.call(document.querySelectorAll('#chatBody button'));" +
      "var ok=btns.find(function(b){return !b.disabled&&b.textContent.indexOf('确认写入')>=0;});" +
      "var r=ROOMS.find(function(x){return x.id==='" + rid + "';});" +
      "return {hasConfirm:!!ok,phase:r.phase};})()");
    if (st.hasConfirm) {
      const fname = await evaljs(
        "(function(){var btns=[].slice.call(document.querySelectorAll('#chatBody button'));" +
        "var ok=btns.find(function(b){return !b.disabled&&b.textContent.indexOf('确认写入')>=0;});" +
        "var card=ok.closest('.msg');var tt=card?card.querySelector('.tt'):null;" +
        "ok.click();return tt?tt.textContent:'?';})()");
      confirmed++;
      console.log('  确认写入 #' + confirmed + ': ' + fname);
      if (confirmed === 1) { await sleep(800); shot('mov-snake-3.png'); }
      continue;
    }
    if (st.phase === '已交付') { delivered = true; break; }
  }
  console.log(delivered ? ('已交付 · 共确认 ' + confirmed + ' 个文件') : 'FAIL: 未交付 (确认 ' + confirmed + ' 个后卡住)');
  if (!delivered) { shot('mov-snake-fail-exec.png'); process.exit(4); }
  /* 6. 校验文件落盘 */
  const files = await evaljs("B.listRoomFiles('" + rid + "','files/work')");
  console.log('房间 work 目录:', JSON.stringify(files));
  const list = Array.isArray(files) ? files : (files && files.files) || [];
  const game = list.find(f => /\.html?$/i.test(f.name)) || list[0];
  if (game) {
    const head = await evaljs("(function(){var r=B.readFile('" + rid + "','files/work/" + game.name + "');return r.ok?r.content.slice(0,200):('ERR '+r.error);})()");
    console.log('文件头 200 字 (' + game.name + '): ' + String(head).replace(/\n/g, '⏎'));
  }
  /* 7. 文件 tab 可见性 */
  await evaljs("setSubtab('files');'ok'");
  await sleep(1200);
  shot('mov-snake-4.png');
  await evaljs("setSubtab('chat');'ok'");
  await sleep(600);
  shot('mov-snake-5.png');
  console.log('PASS: 全链路完成 (房间 ' + rid + ')');
  process.exit(0);
})().catch(e => { console.error('ERR: ' + e.message); try { shot('mov-snake-fail-err.png'); } catch (e2) {} process.exit(2); });
