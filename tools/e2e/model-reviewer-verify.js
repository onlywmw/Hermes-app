/* 大脑/评审身份设置验证 */
const WS = require('ws');
const http = require('http');
let ws, msgId = 0;
const pending = {};
function ev(expression) {
  return new Promise((res) => {
    const id = ++msgId;
    pending[id] = res;
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true } }));
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
http.get('http://localhost:9222/json', res => {
  let d = ''; res.on('data', c => d += c); res.on('end', () => {
    const page = JSON.parse(d).find(t => t.type === 'page');
    ws = new WS(page.webSocketDebuggerUrl, { perMessageDeflate: false });
    ws.on('message', raw => { const m = JSON.parse(raw); if (m.id && pending[m.id]) { pending[m.id](m.result && m.result.result ? m.result.result.value : undefined); delete pending[m.id]; } });
    ws.on('open', async () => {
      const dsId = await ev(`(function(){var m=B.listModels().find(function(x){return x.name==='DeepSeek评审'});return m?m.id:null})()`);
      console.log('DeepSeek评审 id:', dsId);
      console.log('setReviewer:', JSON.stringify(await ev(`B.setReviewer("${dsId}")`)));
      console.log('isReviewer now:', await ev(`(function(){var m=B.listModels().find(function(x){return x.id==="${dsId}"});return m&&m.isReviewer})()`));
      // 新建房间预选
      await ev(`document.getElementById('fabNew').click();'ok'`);
      await sleep(600);
      const defId = await ev(`(function(){var m=B.listModels().find(function(x){return x.isDefault});return m?m.id:null})()`);
      const sel1 = await ev(`JSON.stringify(_nrSel)`);
      console.log('预选(有评审标记):', sel1, '| 含DeepSeek:', await ev(`_nrSel.indexOf("${dsId}")>=0`), '| 含大脑(应false):', await ev(`_nrSel.indexOf("${defId}")>=0`));
      await ev(`closeAllSheets();'ok'`);
      // 回退路径
      await ev(`B.setReviewer("${dsId}")`);
      await ev(`document.getElementById('fabNew').click();'ok'`);
      await sleep(600);
      const sel2 = await ev(`JSON.stringify(_nrSel)`);
      console.log('预选(无评审标记, 应为大脑):', sel2, '| 大脑id:', defId, '| 回退正确:', await ev(`_nrSel.length===1&&_nrSel[0]==="${defId}"`));
      await ev(`closeAllSheets();'ok'`);
      console.log('恢复标记:', JSON.stringify(await ev(`B.setReviewer("${dsId}")`)));
      process.exit(0);
    });
  });
});
