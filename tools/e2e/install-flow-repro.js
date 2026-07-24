/* 安装流程复现: 造房间 → 写 HTML → 打包 → 调 installApk → 观察系统反应 */
const WS = require('ws');
const http = require('http');
let ws, msgId = 0;
const pending = {};
function ev(expression) {
  return new Promise((res) => {
    const id = ++msgId;
    pending[id] = res;
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
http.get('http://localhost:9222/json', res => {
  let d = ''; res.on('data', c => d += c); res.on('end', () => {
    const page = JSON.parse(d).find(t => t.type === 'page');
    ws = new WS(page.webSocketDebuggerUrl, { perMessageDeflate: false });
    ws.on('message', raw => { const m = JSON.parse(raw); if (m.id && pending[m.id]) { pending[m.id](m.result && m.result.result ? m.result.result.value : undefined); delete pending[m.id]; } });
    ws.on('open', async () => {
      // 找一个有 t.html 的房间, 没有就用 desk
      const roomId = await ev(`(function(){
        B.initRoomStorage('instest');
        var w = B.writeFile('instest','t.html','<!DOCTYPE html><html><body><h1>install-test</h1></body></html>');
        return 'instest';
      })()`);
      console.log('room:', roomId, 'write ok');
      // 打包 (走 agent 同款 app.package 桥: BridgeAi.packageApk 经 B 包装?)
      const hasPkg = await ev(`typeof B.packageApk`);
      console.log('B.packageApk type:', hasPkg);
      const bMethods = await ev(`Object.keys(B).filter(k=>/pack|inst/i.test(k)).join(',')`);
      console.log('B methods:', bMethods);
      process.exit(0);
    });
  });
});
