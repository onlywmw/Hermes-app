/* shell.exec e2e 验收: mock-llm 驱动真实 AgentLoop →
   计划卡批准 → 计划外 shellPreview 闸(点击允许) → shell.exec 真跑 →
   /exchange 产物 file.pull 取回 → 交付断言。
   前置: adb forward tcp:9222 localabstract:chrome_devtools_remote
         adb reverse tcp:8787 tcp:8787  +  python mock-llm.py 8787 */
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

  /* ① 配置 mock 模型 (ollama 预设允许自定义 baseUrl 且无需 Key) */
  const add = await evaljs(`B.addModel({name:'mock-llm',provider:'ollama',baseUrl:'http://127.0.0.1:8787/v1',apiKey:'',model:'mock',role:'通用'})`);
  console.log('addModel:', JSON.stringify(add));
  assert(add && add.ok, 'addModel 失败');
  await evaljs(`B.setDefaultModel('${add.id}')`);

  /* ② 建/进 council 验收房 (每次唯一 id, 避免历史失败卡干扰选择器) */
  const rid = await evaljs(`(function(){
    closeAllSheets();genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');
    var id='lxe'+(Date.now()%1000000);
    ROOMS.splice(1,0,{id:id,name:'Linux验收',mode:'council',
      members:{human:[{who:'you',role:'owner'}],ai:[]},
      phase:'讨论中',last:'',time:'现在',unread:0,played:false,msgs:[],seed:[]});
    B.initRoomStorage(id);renderRooms();persistRooms();enterRoom(id);
    return id;
  })()`);
  console.log('房间:', rid);

  /* ③ 发任务 */
  await evaljs(`$('msgInput').value='用 shell 跑 uname -a 和 python3 --version, 把结果写进 answer.txt';$('btnSend').click()`);
  console.log('已发送任务, 等计划卡...');

  /* 注意: 失败卡也用 .plan-card 类, 必须用 .ph 前缀 'PLAN' 区分 */
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
  console.log('计划卡:', await evaljs(`(function(){
    var t='';document.querySelectorAll('#chatBody .plan-card .ph').forEach(function(ph){
      if(ph.textContent.indexOf('PLAN')===0)t=ph.textContent;});return t;})()`));
  await evaljs(`(function(){
    var cards=document.querySelectorAll('#chatBody .plan-card');
    for(var i=0;i<cards.length;i++){
      var ph=cards[i].querySelector('.ph');
      if(ph&&ph.textContent.indexOf('PLAN')===0){cards[i].querySelector('.btn-acc').click();return true;}
    }
    return false;
  })()`);
  console.log('已批准计划, 等 shellPreview 闸...');

  /* ④ 计划外 shell.exec → 确认卡必须出现, 展示完整 cmd, 点击「允许」 */
  const t1 = Date.now();
  let gate = null;
  while (Date.now() - t1 < 60000) {
    await sleep(1500);
    gate = await evaljs(`(function(){
      var cards=document.querySelectorAll('#chatBody .plan-card');
      for(var i=0;i<cards.length;i++){
        var ph=cards[i].querySelector('.ph');
        if(ph&&ph.textContent.indexOf('shell')>=0){
          return {title:ph.textContent,
                  cmd:(cards[i].querySelector('.pvw')||{}).textContent||'',
                  btn:(cards[i].querySelector('.btn-acc')||{}).textContent||''};
        }
      }
      return null;
    })()`);
    if (gate) break;
  }
  assert(gate, '60s 内未出现 shellPreview 确认卡');
  console.log('确认卡标题:', gate.title);
  console.log('确认卡命令:', gate.cmd);
  console.log('确认卡按钮:', gate.btn);
  assert(gate.cmd.includes('uname -a') && gate.cmd.includes('python3 --version'), '确认卡未展示完整命令');
  assert(gate.btn.includes('不再询问'), '按钮文案不符');
  shot('shell-preview-gate');
  await evaljs(`(function(){
    var cards=document.querySelectorAll('#chatBody .plan-card');
    for(var i=0;i<cards.length;i++){
      var ph=cards[i].querySelector('.ph');
      if(ph&&ph.textContent.indexOf('shell')>=0){cards[i].querySelector('.btn-acc').click();return true;}
    }
    return false;
  })()`);
  console.log('已点击允许, 等执行与交付...');

  /* ⑤ 等交付, 收集步骤日志 */
  const t2 = Date.now();
  let delivered = false;
  while (Date.now() - t2 < 180000) {
    await sleep(3000);
    const st = await evaljs(`(function(){
      var txt=$('chatBody').textContent;
      return {deliver:document.querySelectorAll('#chatBody .deliver-card').length,
              fail:txt.indexOf('任务失败')>=0};
    })()`);
    if (st.deliver > 0) { delivered = true; break; }
    if (st.fail) break;
  }
  assert(delivered, '180s 内未交付');
  shot('shell-deliver');

  /* ⑥ 断言: 步骤回显含真实 uname/python 输出; 文件落盘; /exchange 产物取回 */
  const steps = await evaljs(`(function(){
    var out=[];
    document.querySelectorAll('#chatBody .toolcall').forEach(function(t){out.push(t.textContent);});
    return out.join('\\n');
  })()`);
  assert(/Linux localhost|Linux .*aarch64/.test(steps), '步骤回显缺 uname 输出');
  assert(steps.includes('Python 3.12'), '步骤回显缺 python3 版本');
  assert(steps.includes('hello-from-linux'), '步骤回显缺 /exchange 写入回显');
  console.log('步骤回显含 uname / Python 3.12 / hello-from-linux ✓');

  const files = await evaljs(`B.listWorkFiles('${rid}').files.map(function(f){return f.name;})`);
  console.log('房间文件:', JSON.stringify(files));
  assert(files.includes('answer.txt'), 'answer.txt 未落盘');
  assert(files.includes('hello.txt'), 'hello.txt 未被 file.pull 取回');

  const hello = await evaljs(`(function(){var r=B.readFile('${rid}','files/work/hello.txt');return r.ok?r.content:('ERR:'+r.error);})()`);
  assert(hello.includes('hello-from-linux'), 'hello.txt 内容不对: ' + hello);
  console.log('file.pull 取回内容:', hello.trim(), '✓');

  console.log('\n===== shell.exec e2e 验收通过 =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); try { shot('shell-e2e-fail'); } catch (_) {} process.exit(1); });
