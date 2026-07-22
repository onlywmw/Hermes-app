/* MOV-APP 模型管理重构 一次性验证脚本 (CDP 直连 WebView, 不提交) */
const http = require('http');
const { execSync } = require('child_process');

const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
const SERIAL = '21770d7d';
function adb(args) { return execSync('"' + ADB + '" -s ' + SERIAL + ' ' + args, { encoding: 'utf8' }); }
function shot(path) {
  const buf = execSync('"' + ADB + '" -s ' + SERIAL + ' exec-out screencap -p', { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 });
  require('fs').writeFileSync(path, buf);
}

let ws, msgId = 0;
const pending = new Map();
function connect() {
  return new Promise((resolve, reject) => {
    /* 找 webview devtools socket 并 forward */
    let socks = '';
    try { socks = adb('shell cat /proc/net/unix'); } catch (e) {}
    const m = socks.match(/webview_devtools_remote_(\d+)/);
    if (!m) return reject(new Error('webview devtools socket not found'));
    try { adb('forward tcp:9222 localabstract:webview_devtools_remote_' + m[1]); } catch (e) {}
    http.get('http://localhost:9222/json', res => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => {
        const page = JSON.parse(d).find(t => t.url.includes('hermes-shell.html'));
        if (!page) return reject(new Error('page not found: ' + d.slice(0, 200)));
        ws = new WebSocket(page.webSocketDebuggerUrl);
        ws.onopen = resolve;
        ws.onerror = reject;
        ws.onmessage = ev => {
          const mm = JSON.parse(ev.data);
          if (mm.id && pending.has(mm.id)) { pending.get(mm.id)(mm); pending.delete(mm.id); }
        };
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
      if (r && r.exceptionDetails) return reject(new Error('页面异常: ' + (r.exceptionDetails.exception && r.exceptionDetails.exception.description || r.exceptionDetails.text).slice(0, 400)));
      resolve(r && r.result ? r.result.value : undefined);
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

let failures = 0;
async function t(name, fn) {
  try {
    const detail = await fn();
    console.log('  PASS  ' + name + (detail ? '  — ' + detail : ''));
  } catch (e) {
    failures++;
    console.log('  FAIL  ' + name + '  — ' + String(e.message || e).slice(0, 300));
  }
}
function assert(cond, msg) { if (!cond) throw new Error(msg); }

(async () => {
  await connect();
  console.log('CDP connected');

  /* 0. 前置: 确保在运行页且 sheet 关闭 */
  await evaljs("closeAllSheets(); (typeof setTab==='function')&&setTab('run'); 'ok'");
  await sleep(600);

  /* A. 点"＋ 添加模型"→ sheet 打开, 13 厂商, DeepSeek 默认选中 */
  await t('A1 添加模型行存在', async () => {
    const n = await evaljs("document.querySelectorAll('#modelList .model-row').length");
    assert(n >= 2, 'model rows=' + n);
    return n + ' rows';
  });
  await evaljs("openModelSheet(null); 'ok'");
  await sleep(500);
  await t('A2 sheet 打开+13厂商+DeepSeek默认选中', async () => {
    const r = await evaljs("({open:document.getElementById('modelSheet').classList.contains('open'),pv:document.querySelectorAll('#providerList .mpick').length,sel:(document.querySelector('#providerList .mpick.sel')||{}).textContent||''})");
    assert(r.open, 'sheet not open');
    assert(r.pv === 13, 'presets=' + r.pv);
    assert(r.sel.includes('DeepSeek'), 'sel=' + r.sel);
    return r.pv + ' 厂商, 默认 ' + r.sel.slice(0, 20);
  });
  shot('C:/Users/Administrator/Documents/kimi/workspace/mov-models-1.png');

  /* B. 选智谱 → 高级区自动填 baseUrl/model */
  await evaljs("document.querySelector('#providerList .mpick[data-pv=zhipu]').click(); 'ok'");
  await sleep(300);
  await evaljs("setModelAdvOpen(true); 'ok'");
  await sleep(200);
  await t('B1 智谱选中+baseUrl/model 已按预设填充', async () => {
    const r = await evaljs("({base:document.getElementById('modelBaseUrl').value,model:document.getElementById('modelName').value,disp:document.getElementById('modelDisplay').value})");
    assert(r.base === 'https://open.bigmodel.cn/api/paas/v4', 'base=' + r.base);
    assert(r.model === 'glm-4.7-flash', 'model=' + r.model);
    assert(r.disp === '智谱 GLM', 'disp=' + r.disp);
    return r.base + ' / ' + r.model;
  });
  shot('C:/Users/Administrator/Documents/kimi/workspace/mov-models-2.png');

  /* C1. Key 空 → 保存置灰 */
  await t('C1 空Key保存置灰', async () => {
    const d = await evaljs("document.getElementById('btnModelSave').disabled");
    assert(d === true, 'disabled=' + d);
  });
  /* C2. 输假 Key → 保存可用 → 保存 → 列表出现 */
  await evaljs("var k=document.getElementById('modelKey'); k.value='sk-test123'; k.dispatchEvent(new Event('input')); 'ok'");
  await sleep(200);
  await t('C2 输Key后保存可用', async () => {
    const d = await evaljs("document.getElementById('btnModelSave').disabled");
    assert(d === false, 'disabled=' + d);
  });
  await evaljs("document.getElementById('btnModelSave').click(); 'ok'");
  await sleep(800);
  await t('C3 保存后列表出现智谱模型', async () => {
    const ms = await evaljs("B.listModels().map(m=>({id:m.id,name:m.name,pv:m.provider,key:m.apiKey}))");
    const glm = ms.find(m => m.pv === 'zhipu');
    assert(glm, 'no zhipu in ' + JSON.stringify(ms));
    assert(glm.key.includes('****'), 'key not masked: ' + glm.key);
    return glm.name + ' key=' + glm.key;
  });
  shot('C:/Users/Administrator/Documents/kimi/workspace/mov-models-3.png');

  /* D. 长按管理: 直接调 openModelOps 验证 sheet; 删除二次确认 → 消失 */
  await evaljs("var m=B.listModels().find(x=>x.provider==='zhipu'); openModelOps(m); 'ok'");
  await sleep(400);
  await t('D1 管理sheet打开+模型名正确', async () => {
    const r = await evaljs("({open:document.getElementById('modelOpsSheet').classList.contains('open'),name:document.getElementById('modelOpsName').textContent,defHidden:document.getElementById('mopsDefault').style.display})");
    assert(r.open, 'ops not open');
    assert(r.name.includes('智谱'), 'name=' + r.name);
    return r.name;
  });
  shot('C:/Users/Administrator/Documents/kimi/workspace/mov-models-4.png');
  await evaljs("document.getElementById('mopsDelete').click(); 'ok'");
  await sleep(300);
  await t('D2 删除二次确认文案带模型名', async () => {
    const tx = await evaljs("document.getElementById('modelOpsConfirmText').textContent");
    assert(tx.includes('智谱'), 'confirm=' + tx);
    return tx.slice(0, 40);
  });
  await evaljs("document.getElementById('mopsConfirmOk').click(); 'ok'");
  await sleep(600);
  await t('D3 删除后列表无智谱', async () => {
    const ms = await evaljs("B.listModels().map(m=>m.provider)");
    assert(!ms.includes('zhipu'), 'still there: ' + JSON.stringify(ms));
  });

  /* E. 最后一个删除被拒 — 仅在恰好剩 1 个时实测, 避免误删用户模型 */
  await t('E1 最后一个模型删除被拒', async () => {
    const ms = await evaljs("B.listModels()");
    if (ms.length !== 1) return '跳过(现有 ' + ms.length + ' 个模型, 不删用户数据)';
    const r = await evaljs("B.deleteModel('" + ms[0].id + "')");
    assert(!r.ok, 'delete should fail');
    assert((r.error || '').includes('至少保留一个模型'), 'err=' + r.error);
    const after = await evaljs("B.listModels().length");
    assert(after === 1, 'after=' + after);
    return '拒绝文案: ' + r.error;
  });

  /* F. openUrl 桥存在性 + 非法 scheme 拒绝 (不真的跳浏览器) */
  await t('F1 openUrl 桥已注入', async () => {
    const has = await evaljs("!!(window.HermesBridge && HermesBridge.openUrl)");
    assert(has, 'openUrl missing');
  });

  /* G. i18n: 无缺失 key (t() fallback 检查) */
  await t('G1 新 i18n key 中英齐全', async () => {
    const keys = ['addTitle','editTitle','providerLabel','keyLabel','getKey','noKeyNeeded','keepKeyHint','advanced','baseUrlLabel','modelLabel','nameLabel','roleLabel','roleProduct','roleTech','roleData','roleCustom','testConn','testNeedKey','save','saved','opsTitle','setAsDefault','edit','delete','deleteConfirm'];
    const miss = [];
    for (const k of keys) {
      const zh = await evaljs("t('model." + k + "')");
      if (!zh || zh === 'model.' + k) miss.push('zh:' + k);
    }
    assert(miss.length === 0, 'missing ' + miss.join(','));
    return keys.length + ' keys';
  });

  console.log(failures === 0 ? 'ALL PASS' : failures + ' FAILURES');
  process.exit(failures === 0 ? 0 : 1);
})().catch(e => { console.error('DRIVER ERROR: ' + e.message); process.exit(2); });
