/* M-QUALITY 返工 e2e: mock 大脑写死按钮+谎称真实现, 真实 MiMo 评审团(91e3dc44)证伪。
   期望: 评审看产物内容投 fail 指出死按钮 → 返工 → 修正演示挂牌 → 复审通过 →
   交付卡 reviewState=reworked + ⚠️ 演示清单。 */
const WS = require('ws');
const http = require('http');
const fs = require('fs');
const { execSync } = require('child_process');
const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
fs.mkdirSync('shots', { recursive: true });
function shot(n) { try { fs.writeFileSync('shots/' + n + '.png', execSync('"' + ADB + '" exec-out screencap -p', { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 })); } catch (_) {} }
const REVIEW_MODEL_ID = '91e3dc44';

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
  /* 大脑设回 mock (评审团仍是真实 MiMo-评审, 房间 ai 成员决定) */
  const models = await evaljs(`B.listModels()`);
  const mock = models.find(m => m.name === 'mock-llm');
  if (mock) await evaljs(`B.setDefaultModel('${mock.id}')`);

  const rid = await evaljs(`(function(){
    closeAllSheets();genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');
    var id='qre'+(Date.now()%1000000);
    ROOMS.splice(1,0,{id:id,name:'评审证伪',mode:'council',
      members:{human:[{who:'you',role:'owner'}],ai:['${REVIEW_MODEL_ID}']},
      phase:'讨论中',last:'',time:'现在',unread:0,played:false,msgs:[],seed:[]});
    B.initRoomStorage(id);renderRooms();persistRooms();enterRoom(id);
    return id;
  })()`);
  console.log('房间:', rid);

  await evaljs(`$('msgInput').value='做一个二维码工具 HTML, 输入文本生成二维码, 加扫一扫功能';$('btnSend').click()`);
  console.log('已发送 (mock 大脑写死按钮, 真实 MiMo 评审证伪中)...');

  let delivered = false, failed = '';
  const t0 = Date.now();
  while (Date.now() - t0 < 600000) {
    await sleep(4000);
    const st = await evaljs(`(function(){
      var r={plan:0,deliver:0,fail:''};
      document.querySelectorAll('#chatBody .plan-card').forEach(function(c){
        var ph=c.querySelector('.ph');if(!ph)return;var t=ph.textContent;
        if(t.indexOf('PLAN')===0){var b=c.querySelector('.btn-acc');if(b&&!b.disabled)r.plan++;}
        if(t.indexOf('任务失败')>=0)r.fail=t;
      });
      r.deliver=document.querySelectorAll('#chatBody .deliver-card').length;
      return r;
    })()`);
    if (st.fail) { failed = st.fail; break; }
    if (st.deliver > 0) { delivered = true; break; }
    if (st.plan > 0) {
      await evaljs(`(function(){var cs=document.querySelectorAll('#chatBody .plan-card');for(var i=0;i<cs.length;i++){var ph=cs[i].querySelector('.ph');if(ph&&ph.textContent.indexOf('PLAN')===0){var b=cs[i].querySelector('.btn-acc');if(b&&!b.disabled){b.click();return true;}}}return false;})()`);
      console.log('  → 批准计划 @' + Math.round((Date.now() - t0) / 1000) + 's');
      continue;
    }
    process.stdout.write('.');
  }
  console.log('');
  if (failed) throw new Error('任务失败: ' + failed);
  assert(delivered, '600s 内未交付');
  shot('quality-rework');
  console.log('已交付!');

  /* 取证: 评审意见 + 返工 + 交付卡清单 */
  const info = await evaljs(`(function(){
    var checks=[], sys=[];
    document.querySelectorAll('#chatBody .dl-check').forEach(function(c){checks.push({cls:c.className,txt:c.textContent});});
    document.querySelectorAll('#chatBody .msg').forEach(function(m){
      var t=m.textContent;
      if(t.indexOf('评审')>=0||t.indexOf('返工')>=0||t.indexOf('复审')>=0)sys.push(t.slice(0,200));
    });
    return {checks:checks, sys:sys};
  })()`);
  console.log('--- 评审/返工日志 ---');
  info.sys.forEach(s => console.log('  ' + s));
  console.log('--- 交付卡清单 ---');
  info.checks.forEach(c => console.log('  [' + (c.cls.includes('demo') ? '⚠️演示' : '✅真实') + '] ' + c.txt));

  /* 断言: 有返工(评审抓死按钮) + 清单标演示 */
  const allSys = info.sys.join(' ');
  assert(/返工|✗/.test(allSys), '评审未投 fail/未返工: ' + allSys.slice(0, 200));
  console.log('\n评审抓死按钮投 fail 并触发返工 ✓');
  const hasDemo = info.checks.some(c => c.cls.includes('demo'));
  assert(hasDemo, '交付卡清单无 ⚠️ 演示项');
  console.log('交付卡 ⚠️ 演示项 ✓');

  console.log('\n===== M-QUALITY 评审证伪返工 e2e 通过 =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); shot('quality-rework-fail'); process.exit(1); });
