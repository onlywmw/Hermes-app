/* M2 委派 e2e 验收: mock-llm-m2 驱动真实 AgentLoop →
   计划批准 → 计划外 shell 闸(允许) → shell.exec hermes --version →
   shell.exec hermes -z (内嵌 Hermes 真实跑通 oneshot) → 交付断言。
   前置: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
         adb reverse tcp:8787 tcp:8787 + python mock-llm-m2.py 8787
         内嵌 Hermes 已安装 (hermes-state.json READY) */
const WS = require('ws');
const http = require('http');
const fs = require('fs');
const { execSync } = require('child_process');
const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
fs.mkdirSync('shots', { recursive: true });
function shot(n) { fs.writeFileSync('shots/' + n + '.png', execSync('"' + ADB + '" exec-out screencap -p', { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 })); }

let ws, msgId = 0; const pending = new Map();
function connect() {
  return new Promise((resolve, reject) => {
    http.get('http://localhost:9222/json', res => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => {
        const page = JSON.parse(d).find(t => t.url.includes('hermes-shell'));
        if (!page) return reject(new Error('page not found'));
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
      if (r && r.exceptionDetails) return reject(new Error('页面异常: ' + (r.exceptionDetails.exception && r.exceptionDetails.exception.description || r.exceptionDetails.text).slice(0, 300)));
      resolve(r && r.result ? r.result.value : undefined);
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
function assert(c, m) { if (!c) throw new Error(m || '断言失败'); }

(async () => {
  await connect();

  /* mock 模型设为默认 (幂等: 已有就直接用) */
  const models = await evaljs(`B.listModels()`);
  let mock = models.find(m => m.name === 'mock-llm');
  if (!mock) {
    const add = await evaljs(`B.addModel({name:'mock-llm',provider:'ollama',baseUrl:'http://127.0.0.1:8787/v1',apiKey:'',model:'mock',role:'通用'})`);
    assert(add && add.ok, 'addModel 失败');
    mock = { id: add.id };
  }
  await evaljs(`B.setDefaultModel('${mock.id}')`);

  /* 验收房 (唯一 id) */
  const rid = await evaljs(`(function(){
    closeAllSheets();genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');
    var id='hx'+(Date.now()%1000000);
    ROOMS.splice(1,0,{id:id,name:'Hermes委派验收',mode:'council',
      members:{human:[{who:'you',role:'owner'}],ai:[]},
      phase:'讨论中',last:'',time:'现在',unread:0,played:false,msgs:[],seed:[]});
    B.initRoomStorage(id);renderRooms();persistRooms();enterRoom(id);
    return id;
  })()`);
  console.log('房间:', rid);

  await evaljs(`$('msgInput').value='用内嵌 Hermes 跑个版本检查和小任务, 结果写进 answer2.txt';$('btnSend').click()`);
  console.log('已发送任务, 等计划卡...');

  const t0 = Date.now();
  let planOk = false;
  while (Date.now() - t0 < 60000) {
    await sleep(2000);
    const st = await evaljs(`(function(){
      var plan=0, fail='';
      document.querySelectorAll('#chatBody .plan-card .ph').forEach(function(ph){
        if(ph.textContent.indexOf('PLAN')===0)plan++;
        if(ph.textContent.indexOf('任务失败')>=0)fail=ph.textContent;
      });
      return {plan:plan, fail:fail};
    })()`);
    if (st.fail) throw new Error('任务失败: ' + st.fail);
    if (st.plan > 0) { planOk = true; break; }
  }
  assert(planOk, '60s 内未出计划卡');
  await evaljs(`(function(){
    var cards=document.querySelectorAll('#chatBody .plan-card');
    for(var i=0;i<cards.length;i++){
      var ph=cards[i].querySelector('.ph');
      if(ph&&ph.textContent.indexOf('PLAN')===0){cards[i].querySelector('.btn-acc').click();return true;}
    }
    return false;
  })()`);
  console.log('已批准计划, 等 shell 闸...');

  /* 计划外 shell.exec → 确认卡 → 允许 (本任务不再询问) */
  const t1 = Date.now();
  let gate = null;
  while (Date.now() - t1 < 60000) {
    await sleep(1500);
    gate = await evaljs(`(function(){
      var cards=document.querySelectorAll('#chatBody .plan-card');
      for(var i=0;i<cards.length;i++){
        var ph=cards[i].querySelector('.ph');
        if(ph&&ph.textContent.indexOf('shell')>=0){
          return {cmd:(cards[i].querySelector('.pvw')||{}).textContent||''};
        }
      }
      return null;
    })()`);
    if (gate) break;
  }
  assert(gate, '60s 内未出现 shell 确认卡');
  console.log('确认卡命令:', gate.cmd);
  assert(gate.cmd.includes('hermes'), '确认卡命令不含 hermes');
  await evaljs(`(function(){
    var cards=document.querySelectorAll('#chatBody .plan-card');
    for(var i=0;i<cards.length;i++){
      var ph=cards[i].querySelector('.ph');
      if(ph&&ph.textContent.indexOf('shell')>=0){cards[i].querySelector('.btn-acc').click();return true;}
    }
    return false;
  })()`);
  console.log('已允许, 等执行与交付 (hermes oneshot 可能 1-3 分钟)...');

  const t2 = Date.now();
  let delivered = false;
  while (Date.now() - t2 < 360000) {
    await sleep(4000);
    const st = await evaljs(`(function(){
      var txt=$('chatBody').textContent;
      return {deliver:document.querySelectorAll('#chatBody .deliver-card').length,
              fail:txt.indexOf('任务失败')>=0};
    })()`);
    if (st.deliver > 0) { delivered = true; break; }
    if (st.fail) break;
  }
  assert(delivered, '360s 内未交付');
  shot('hermes-delegate');

  /* 断言: hermes --version 回显 + oneshot 真实回答 */
  const steps = await evaljs(`(function(){
    var out=[];
    document.querySelectorAll('#chatBody .toolcall').forEach(function(t){out.push(t.textContent);});
    return out.join('\\n');
  })()`);
  assert(steps.includes('Hermes Agent v0.19.0'), '缺 hermes --version 回显: ' + steps.slice(-300));
  assert(steps.includes('DELEGATION-OK'), '缺 oneshot 回答 DELEGATION-OK: ' + steps.slice(-300));
  console.log('hermes --version 回显 ✓  oneshot DELEGATION-OK ✓');

  const files = await evaljs(`B.listWorkFiles('${rid}').files.map(function(f){return f.name;})`);
  assert(files.includes('answer2.txt'), 'answer2.txt 未落盘');
  console.log('房间文件:', JSON.stringify(files));

  console.log('\n===== M2 委派 e2e 验收通过 =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); try { shot('hermes-delegate-fail'); } catch (_) {} process.exit(1); });
