/* 多选删除功能验证: 真实点击链路 (长按入口函数 → 多选删除 → 勾选 → 确认删除) */
const WS = require('ws');
const http = require('http');
let ws, msgId = 0;
const pending = {};
function ev(expression) {
  return new Promise((res, rej) => {
    const id = ++msgId;
    pending[id] = res;
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  });
}
function connect() {
  return new Promise((resolve, reject) => {
    http.get('http://localhost:9222/json', res => {
      let d = ''; res.on('data', c => d += c); res.on('end', () => {
        const page = JSON.parse(d).find(t => t.type === 'page');
        ws = new WS(page.webSocketDebuggerUrl, { perMessageDeflate: false });
        ws.on('open', resolve); ws.on('error', reject);
        ws.on('message', raw => { const m = JSON.parse(raw); if (m.id && pending[m.id]) { pending[m.id](m.result && m.result.result ? m.result.result.value : undefined); delete pending[m.id]; } });
      });
    }).on('error', reject);
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  await connect();
  // 回到会话列表
  await ev(`setTab('chat'); showView('view-rooms'); renderRooms(); 'ok'`);
  const before = await ev(`ROOMS.length`);
  console.log('房间数(前):', before);
  // 1. 模拟长按入口: 打开第二个房间的操作 sheet (desk 是第一个)
  const rid = await ev(`ROOMS[1].id`);
  await ev(`openRoomOpsSheet('${rid}'); 'ok'`);
  await sleep(300);
  // 2. 点「多选删除」
  await ev(`document.getElementById('opsMultiSel').click(); 'ok'`);
  await sleep(400);
  const barVisible = await ev(`document.getElementById('selBar').style.display`);
  const preSel = await ev(`_selIds.length`);
  const selmodeClass = await ev(`document.getElementById('roomList').className`);
  console.log('选择条显示:', barVisible, '| 预选:', preSel, '| roomList class:', selmodeClass);
  // 3. 再勾一个房间 (点第三张卡)
  const rid2 = await ev(`ROOMS[2].id`);
  await ev(`document.querySelector('#roomList .room[data-room="${rid2}"]').click(); 'ok'`);
  await sleep(200);
  console.log('勾选两个后:', await ev(`_selIds.length`), '| 计数文本:', await ev(`document.getElementById('selCount').textContent`));
  // 4. 点删除 → 确认条出现
  await ev(`document.getElementById('selDel').click(); 'ok'`);
  await sleep(200);
  console.log('确认条可见:', await ev(`document.getElementById('selBtnsConfirm').style.display`), '| 确认文案:', await ev(`document.getElementById('selCount').textContent`));
  // 5. 确定删除
  await ev(`document.getElementById('selConfirmOk').click(); 'ok'`);
  await sleep(400);
  const after = await ev(`ROOMS.length`);
  const barGone = await ev(`document.getElementById('selBar').style.display`);
  console.log('房间数(后):', after, '| 选择条已隐藏:', barGone === 'none');
  // 6. 全选/取消路径回归: 再进一次, 全选, 取消
  await ev(`openRoomOpsSheet(ROOMS[1].id); 'ok'`); await sleep(200);
  await ev(`document.getElementById('opsMultiSel').click(); 'ok'`); await sleep(300);
  await ev(`document.getElementById('selAll').click(); 'ok'`); await sleep(200);
  const selAllCount = await ev(`_selIds.length`);
  const total = await ev(`ROOMS.length`);
  await ev(`document.getElementById('selCancel').click(); 'ok'`); await sleep(200);
  const exitOk = await ev(`document.getElementById('selBar').style.display==='none' && !document.getElementById('roomList').classList.contains('selmode')`);
  console.log('全选数量:', selAllCount, '/', total, '| 取消退出干净:', exitOk);
  const pass = (before - after === 2) && barGone === 'none' && selAllCount === total && exitOk;
  console.log(pass ? '✅ 多选删除验证通过' : '❌ 验证失败');
  process.exit(pass ? 0 : 1);
})();
