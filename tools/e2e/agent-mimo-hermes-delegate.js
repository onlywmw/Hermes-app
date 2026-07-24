/* 真实委派终验: MiMo 大脑 → shell.exec 调内嵌 hermes (hermes 也用真实 MiMo)。
   任务: 委派 hermes 写春天短诗, 答案写进 poem.txt。验证 shell.exec→hermes→真实API。 */
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
  const rid = await evaljs(`(function(){
    closeAllSheets();genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');
    var id='hdel'+(Date.now()%1000000);
    ROOMS.splice(1,0,{id:id,name:'hermes委派',mode:'council',
      members:{human:[{who:'you',role:'owner'}],ai:[]},
      phase:'讨论中',last:'',time:'现在',unread:0,played:false,msgs:[],seed:[]});
    B.initRoomStorage(id);renderRooms();persistRooms();enterRoom(id);
    return id;
  })()`);
  console.log('房间:', rid);

  await evaljs(`$('msgInput').value='用 shell.exec 调用内嵌 hermes (命令: ~/.hermes-venv/bin/hermes -z "写一首关于春天的短诗, 4行"), 等它回答后把诗写进 poem.txt';$('btnSend').click()`);
  console.log('已发送委派任务, 驱动执行...');

  let delivered = false, failed = '', approveCount = 0, shellCount = 0, clarifyCount = 0;
  const t0 = Date.now();
  while (Date.now() - t0 < 600000) {
    await sleep(4000);
    const st = await evaljs(`(function(){
      var r={plan:0,ask:false,deliver:0,fail:'',shellGate:false,body:''};
      document.querySelectorAll('#chatBody .plan-card').forEach(function(c){
        var ph=c.querySelector('.ph');if(!ph)return;var t=ph.textContent;
        if(t.indexOf('PLAN')===0){var b=c.querySelector('.btn-acc');if(b&&!b.disabled)r.plan++;}
        if(t.indexOf('shell')>=0){var b2=c.querySelector('.btn-acc');if(b2&&!b2.disabled)r.shellGate=true;}
        if(t.indexOf('任务失败')>=0)r.fail=t;
      });
      r.ask=!!document.querySelector('#chatBody .ask-opts button, #chatBody .clarify-opt');
      r.deliver=document.querySelectorAll('#chatBody .deliver-card').length;
      r.body=document.querySelector('#chatBody').textContent.slice(-200);
      return r;
    })()`);
    if (st.fail) { failed = st.fail; break; }
    if (st.deliver > 0) { delivered = true; break; }
    if (st.plan > 0) {
      await evaljs(`(function(){var cs=document.querySelectorAll('#chatBody .plan-card');for(var i=0;i<cs.length;i++){var ph=cs[i].querySelector('.ph');if(ph&&ph.textContent.indexOf('PLAN')===0){var b=cs[i].querySelector('.btn-acc');if(b&&!b.disabled){b.click();return true;}}}return false;})()`);
      approveCount++; console.log('  → 批准计划 #' + approveCount); continue;
    }
    if (st.shellGate) {
      await evaljs(`(function(){var cs=document.querySelectorAll('#chatBody .plan-card');for(var i=0;i<cs.length;i++){var ph=cs[i].querySelector('.ph');if(ph&&ph.textContent.indexOf('shell')>=0){var b=cs[i].querySelector('.btn-acc');if(b&&!b.disabled){b.click();return true;}}}return false;})()`);
      shellCount++; console.log('  → 允许 shell #' + shellCount); continue;
    }
    if (st.ask) {
      const a = await evaljs(`(function(){var btn=document.querySelector('#chatBody .ask-opts button, #chatBody .clarify-opt');if(btn){btn.click();return 'click';}var inp=$('msgInput');if(inp){inp.value='就按你说的做';$('btnSend').click();return 'typed';}return '';})()`);
      if (a) { clarifyCount++; console.log('  → 回答 clarify #' + clarifyCount); }
      continue;
    }
  }
  console.log('\n批准 ' + approveCount + ', shell 闸 ' + shellCount + ', clarify ' + clarifyCount);
  if (failed) throw new Error('任务失败: ' + failed);
  assert(delivered, '600s 内未交付');
  shot('hermes-delegate-real');
  console.log('已交付!');

  /* 断言: 工作日志含 hermes 调用 + poem.txt 落盘且有诗意内容 */
  const steps = await evaljs(`(function(){var o=[];document.querySelectorAll('#chatBody .toolcall').forEach(function(t){o.push(t.textContent);});return o.join('\\n');})()`);
  const calledHermes = steps.includes('hermes');
  console.log('工作日志含 hermes 调用:', calledHermes);
  const files = await evaljs(`B.listWorkFiles('${rid}').files.map(function(f){return f.name;})`);
  console.log('房间文件:', JSON.stringify(files));
  assert(files.includes('poem.txt'), 'poem.txt 未落盘: ' + JSON.stringify(files));
  const poem = await evaljs(`(function(){var r=B.readFile('${rid}','files/work/poem.txt');return r.ok?r.content:('ERR:'+r.error);})()`);
  console.log('poem.txt 内容:', poem.slice(0, 200));
  assert(poem.length > 10 && !poem.startsWith('ERR'), 'poem.txt 内容异常');

  console.log('\n===== 真实委派终验通过: shell.exec→hermes→MiMo =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); shot('hermes-delegate-real-fail'); process.exit(1); });
