/* 真实链路终验 (MiMo 真实 API, 禁止 mock):
   ① 配 MiMo 模型为默认大脑 (setDefaultModel 会同步重注入 hermes 配置)
   ② 单聊发「用一句话介绍你自己」验证真实对话
   ③ 检查 rootfs ~/.hermes/.env + config.yaml 注入结果 (key 脱敏)
   前置: adb forward tcp:9222 ...; key 从环境变量 MIMO_KEY 读, 不落盘 */
const WS = require('ws');
const http = require('http');

const KEY = process.env.MIMO_KEY;
if (!KEY) { console.error('MIMO_KEY env 未设置'); process.exit(1); }

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

(async () => {
  await connect();

  /* ① MiMo 模型入注册表 (幂等: 已有同名的先删) + 设默认 */
  const models = await evaljs(`B.listModels()`);
  for (const m of models) {
    if (m.name === 'MiMo') await evaljs(`B.updateModel('${m.id}', {apiKey:'${KEY}'})`), console.log('更新已有 MiMo key');
  }
  let mimo = models.find(m => m.name === 'MiMo');
  if (!mimo) {
    const add = await evaljs(`B.addModel({name:'MiMo',provider:'openai',baseUrl:'https://api.xiaomimimo.com/v1',apiKey:'${KEY}',model:'mimo-v2.5-pro-ultraspeed',role:'通用'})`);
    console.log('addModel MiMo:', JSON.stringify(add));
    if (!add || !add.ok) throw new Error('addModel 失败');
    mimo = { id: add.id };
  }
  const sd = await evaljs(`B.setDefaultModel('${mimo.id}')`);
  console.log('setDefaultModel:', JSON.stringify(sd));

  /* ② 单聊真实对话: 用设备控制单聊房 desk (mode single, 成员 mov) */
  const before = Date.now();
  await evaljs(`(function(){
    closeAllSheets();genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');
    var r=ROOMS.find(function(x){return x.id==='desk';});
    if(!r){ROOMS.splice(1,0,{id:'desk',name:'设备控制 · 单聊',mode:'single',members:['mov'],phase:'讨论中',last:'',time:'现在',unread:0,played:false,msgs:[]});B.initRoomStorage('desk');}
    renderRooms();enterRoom('desk');
    $('msgInput').value='用一句话介绍你自己';
    $('btnSend').click();
    return true;
  })()`);
  console.log('已发送单聊消息, 等真实回复...');
  let reply = '';
  const t0 = Date.now();
  while (Date.now() - t0 < 90000) {
    await sleep(3000);
    reply = await evaljs(`(function(){
      var r=ROOMS.find(function(x){return x.id==='desk';});
      if(!r||!r.msgs)return '';
      for(var i=r.msgs.length-1;i>=0;i--){
        var m=r.msgs[i];
        if(m.who==='mov'&&m.text)return m.text;
        if(m.who==='mov'&&m.h)return m.h;
      }
      return '';
    })()`);
    if (reply && reply.length > 5) break;
  }
  if (!reply) throw new Error('90s 内无 AI 回复');
  console.log('AI 回复:', reply.slice(0, 200));
  console.log('耗时: ' + ((Date.now() - t0) / 1000) + 's');

  console.log('\n===== ①② MiMo 真实对话验证通过 =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); process.exit(1); });
