/* M4 部署配置 + 连接测试 (localhost sshd 夹具 127.0.0.1:8022) */
const WS = require('ws');
const http = require('http');
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
      resolve(m.result && m.result.result ? m.result.result.value : undefined);
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  });
}
(async () => {
  await connect();
  const save = await evaljs(`B.saveDeployConfig({host:"127.0.0.1",port:8022,user:"root",authType:"password",secret:"mov123"})`);
  console.log('save:', JSON.stringify(save));
  const cfg = await evaljs(`B.getDeployConfig()`);
  console.log('config:', JSON.stringify(cfg));
  const r = await evaljs(`new Promise(function(res){
    B.testDeployConnection(function(v){res(v);});
    setTimeout(function(){res({timeout:true});}, 40000);
  })`);
  console.log('test:', JSON.stringify(r));
  process.exit(r && r.ok ? 0 : 1);
})().catch(e => { console.error('✗', e.message); process.exit(1); });
