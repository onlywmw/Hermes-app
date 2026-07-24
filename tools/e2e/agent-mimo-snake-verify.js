/* 真实链路终验: MiMo 大脑跑「做贪吃蛇并打包 APK」完整 agent 流程。
   驱动: 轮询处理 计划卡(批准)/clarify卡(自动回答)/shell闸(允许) → 交付断言 APK。
   全程真实 MiMo, 不用 mock。key 经 MIMO_KEY env (仅用于确认模型已配, 不重配)。 */
const WS = require('ws');
const http = require('http');
const fs = require('fs');
const { execSync } = require('child_process');
const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
fs.mkdirSync('shots', { recursive: true });
function shot(n) { try { fs.writeFileSync('shots/' + n + '.png', execSync('"' + ADB + '" exec-out screencap -p', { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 })); } catch (_) {} }

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

  /* 确认默认大脑是 MiMo (不重配, 上一步已配好) */
  const def = await evaljs(`(B.listModels()||[]).find(function(m){return m.isDefault;})`);
  console.log('默认大脑:', def ? def.name + ' / ' + def.model : 'NONE');
  assert(def && def.name === 'MiMo', '默认大脑不是 MiMo');

  /* 验收房 */
  const rid = await evaljs(`(function(){
    closeAllSheets();genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');
    var id='snake'+(Date.now()%1000000);
    ROOMS.splice(1,0,{id:id,name:'贪吃蛇APK',mode:'council',
      members:{human:[{who:'you',role:'owner'}],ai:[]},
      phase:'讨论中',last:'',time:'现在',unread:0,played:false,msgs:[],seed:[]});
    B.initRoomStorage(id);renderRooms();persistRooms();enterRoom(id);
    return id;
  })()`);
  console.log('房间:', rid);

  await evaljs(`$('msgInput').value='做一个贪吃蛇小游戏并打包成 APK';$('btnSend').click()`);
  console.log('已发送任务, 驱动执行 (计划批准 / clarify 自动答 / shell 闸允许)...');

  let delivered = false, failed = '', lastLog = '', approveCount = 0, clarifyCount = 0, shellCount = 0;
  const t0 = Date.now();
  while (Date.now() - t0 < 600000) {
    await sleep(4000);
    const st = await evaljs(`(function(){
      var r={plan:0,planApproved:0,ask:false,deliver:0,fail:'',shellGate:false,body:''};
      document.querySelectorAll('#chatBody .plan-card').forEach(function(c){
        var ph=c.querySelector('.ph');
        if(!ph)return;
        var t=ph.textContent;
        if(t.indexOf('PLAN')===0){
          var btn=c.querySelector('.btn-acc');
          if(btn&&!btn.disabled)r.plan++; else r.planApproved++;
        }
        if(t.indexOf('shell')>=0){
          var b2=c.querySelector('.btn-acc');
          if(b2&&!b2.disabled)r.shellGate=true;
        }
        if(t.indexOf('任务失败')>=0)r.fail=t;
      });
      /* clarify/ask 卡: 有选项按钮或待输入 */
      var askBtn=document.querySelector('#chatBody .ask-opts button, #chatBody .clarify-opt');
      r.ask=!!askBtn;
      r.deliver=document.querySelectorAll('#chatBody .deliver-card').length;
      r.body=document.querySelector('#chatBody').textContent.slice(-250);
      return r;
    })()`);

    if (st.fail) { failed = st.fail; break; }
    if (st.deliver > 0) { delivered = true; break; }

    if (st.plan > 0) {
      await evaljs(`(function(){
        var cards=document.querySelectorAll('#chatBody .plan-card');
        for(var i=0;i<cards.length;i++){
          var ph=cards[i].querySelector('.ph');
          if(ph&&ph.textContent.indexOf('PLAN')===0){
            var btn=cards[i].querySelector('.btn-acc');
            if(btn&&!btn.disabled){btn.click();return true;}
          }
        }
        return false;
      })()`);
      approveCount++;
      console.log('  → 批准计划 #' + approveCount);
      continue;
    }
    if (st.shellGate) {
      await evaljs(`(function(){
        var cards=document.querySelectorAll('#chatBody .plan-card');
        for(var i=0;i<cards.length;i++){
          var ph=cards[i].querySelector('.ph');
          if(ph&&ph.textContent.indexOf('shell')>=0){
            var btn=cards[i].querySelector('.btn-acc');
            if(btn&&!btn.disabled){btn.click();return true;}
          }
        }
        return false;
      })()`);
      shellCount++;
      console.log('  → 允许 shell #' + shellCount);
      continue;
    }
    if (st.ask) {
      /* clarify: 自动点第一个选项, 没有则在输入框回答 */
      const answered = await evaljs(`(function(){
        var btn=document.querySelector('#chatBody .ask-opts button, #chatBody .clarify-opt');
        if(btn){btn.click();return 'click';}
        var inp=$('msgInput');
        if(inp){inp.value='做一个简单的 HTML5 贪吃蛇, 键盘方向键控制, 打包成 APK 就行';$('btnSend').click();return 'typed';}
        return '';
      })()`);
      if (answered) { clarifyCount++; console.log('  → 回答 clarify #' + clarifyCount + ' (' + answered + ')'); }
      continue;
    }
    if (st.body !== lastLog) { lastLog = st.body; console.log('  … ' + st.body.slice(-90).replace(/\n/g, ' ')); }
  }

  console.log('\n批准计划 ' + approveCount + ' 次, clarify ' + clarifyCount + ' 次, shell 闸 ' + shellCount + ' 次');
  if (failed) throw new Error('任务失败: ' + failed);
  assert(delivered, '600s 内未交付');
  shot('snake-deliver');
  console.log('已交付!');

  /* 断言产物: 房间文件有 .apk */
  const files = await evaljs(`B.listWorkFiles('${rid}').files.map(function(f){return f.name+'('+(f.size||0)+'B)';})`);
  console.log('房间文件:', JSON.stringify(files));
  const hasApk = files.some(f => f.toLowerCase().includes('.apk'));
  assert(hasApk, '无 APK 产物: ' + JSON.stringify(files));
  console.log('APK 产物确认 ✓');

  console.log('\n===== 真实链路终验通过: 贪吃蛇 APK =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); shot('snake-fail'); process.exit(1); });
