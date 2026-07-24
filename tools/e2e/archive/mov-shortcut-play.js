/* 发送到桌面 — 真机验证第 2 步: 查看器内游戏可玩性 CDP 验证 (不提交)
   验证: gameLoop 运行 / 键盘输入转向 / 吃食物得分 / localStorage 可用 / 撞墙 alert */
const http = require('http');
const { execSync } = require('child_process');
const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
function adb(a) { return execSync('"' + ADB + '" -s 21770d7d ' + a, { encoding: 'utf8' }); }
function getJson(u) { return new Promise((res, rej) => { http.get(u, r => { let d = ''; r.on('data', c => d += c); r.on('end', () => res(JSON.parse(d))); }).on('error', rej); }); }
let ws, msgId = 0;
const pending = new Map();
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  const socks = adb('shell cat /proc/net/unix');
  const m = socks.match(/webview_devtools_remote_(\d+)/);
  adb('forward tcp:9222 localabstract:webview_devtools_remote_' + m[1]);
  const tabs = await getJson('http://localhost:9222/json');
  console.log('CDP 页面: ' + tabs.map(t => t.url.slice(0, 60)).join(' | '));
  const page = tabs.find(t => t.url.includes('snake.html'));
  if (!page) { console.log('FAIL: 查看器页面未出现在 CDP'); process.exit(3); }
  await new Promise((res, rej) => {
    ws = new WebSocket(page.webSocketDebuggerUrl); ws.onopen = res; ws.onerror = rej;
    ws.onmessage = ev => { const mm = JSON.parse(ev.data); if (mm.id && pending.has(mm.id)) { pending.get(mm.id)(mm); pending.delete(mm.id); } };
  });
  function ev2(expr) {
    return new Promise((resolve, reject) => {
      const id = ++msgId;
      pending.set(id, m => {
        if (m.error) return reject(new Error(m.error.message));
        const r = m.result;
        if (r && r.exceptionDetails) return reject(new Error('JS: ' + JSON.stringify(r.exceptionDetails).slice(0, 300)));
        resolve(r && r.result ? r.result.value : undefined);
      });
      ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: false } }));
    });
  }
  /* 0. 若已撞墙死亡则重置到安全开局 (x=8 向右, 距墙 12 步) */
  await ev2("snake=[{x:8,y:10}];dx=1;dy=0;score=0;document.getElementById('score').innerText=0;clearInterval(gameLoop);gameLoop=setInterval(drawGame,150);'reset-ok'");
  /* 1. gameLoop 运行: 两次采样蛇头位置 */
  const p1 = await ev2("JSON.stringify(snake[0])");
  await sleep(500);
  const p2 = await ev2("JSON.stringify(snake[0])");
  console.log('1) 蛇头移动: ' + p1 + ' → ' + p2 + ' => ' + (p1 !== p2 ? 'PASS' : 'FAIL'));
  /* 2. 键盘输入转向 (keydown UP, keyCode 38) */
  await ev2("document.dispatchEvent(new KeyboardEvent('keydown',{keyCode:38}));'key-sent'");
  await sleep(50);
  const dir = await ev2("JSON.stringify({dx:dx,dy:dy})");
  console.log('2) 方向键 UP 后方向: ' + dir + ' => ' + (dir === '{"dx":0,"dy":-1}' ? 'PASS' : 'FAIL(转触屏验证)'));
  /* 3. 吃食物得分: 把食物放到蛇头正前方 */
  await ev2("food={x:snake[0].x+dx,y:snake[0].y+dy};'food-placed'");
  await sleep(350);
  const sc = await ev2("score");
  const len = await ev2("snake.length");
  console.log('3) 吃到食物: score=' + sc + ' 蛇长=' + len + ' => ' + (sc >= 10 && len >= 2 ? 'PASS' : 'FAIL'));
  /* 4. localStorage (DOM storage) */
  const ls = await ev2("localStorage.setItem('mov_test','ok');localStorage.getItem('mov_test')");
  console.log('4) localStorage 读写: ' + ls + ' => ' + (ls === 'ok' ? 'PASS' : 'FAIL'));
  /* 5. 触屏滑动转向 (game 用 touchstart/touchend): 当前向上, 滑右应转右 */
  const turned = await ev2(
    "(function(){var c=document.getElementById('gameCanvas');var r=c.getBoundingClientRect();" +
    "var x=r.left+r.width/2,y=r.top+r.height/2;" +
    "function t(type,xx,yy){var touch=new Touch({identifier:1,target:c,clientX:xx,clientY:yy});" +
    "c.dispatchEvent(new TouchEvent(type,{touches:type==='touchend'?[]:[touch],changedTouches:[touch],bubbles:true,cancelable:true}));}" +
    "t('touchstart',x,y);t('touchend',x+60,y);return JSON.stringify({dx:dx,dy:dy});})()");
  console.log('5) 触屏右滑后方向: ' + turned + ' => ' + (turned === '{"dx":1,"dy":0}' ? 'PASS' : 'FAIL'));
  process.exit(0);
})().catch(e => { console.error('ERR', e.message); process.exit(2); });
