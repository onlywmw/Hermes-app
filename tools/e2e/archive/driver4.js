/* 最终轮: D4 修正路径 + 预览修复验证 + D6 prompt */
const WS = require('ws');
const http = require('http');
const fs = require('fs');
let ws, msgId = 0; const pending = new Map();
function connect() {
  return new Promise((resolve, reject) => {
    http.get('http://localhost:9222/json', res => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => {
        const page = JSON.parse(d).find(t => t.url.includes('hermes-shell'));
        ws = new WS(page.webSocketDebuggerUrl, { perMessageDeflate: false });
        ws.on('open', resolve); ws.on('error', reject);
        ws.on('message', raw => { const m = JSON.parse(raw); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } });
      });
    }).on('error', reject);
  });
}
function evaljs(expression, noAwait) {
  return new Promise((resolve, reject) => {
    const id = ++msgId;
    pending.set(id, m => {
      if (m.error) return reject(new Error(m.error.message));
      const r = m.result;
      if (r && r.exceptionDetails) return reject(new Error('页面异常'));
      resolve(r && r.result ? r.result.value : undefined);
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
    if (noAwait) resolve('__fired__');
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
async function t(name, fn) {
  try { const d = await fn(); results.push({ name, ok: true, detail: d || '' }); console.log('  PASS  ' + name + (d ? '  — ' + d : '')); }
  catch (e) { results.push({ name, ok: false, detail: String(e.message || e).slice(0, 250) }); console.log('  FAIL  ' + name + '  — ' + String(e.message || e).slice(0, 250)); }
}
function assert(c, m) { if (!c) throw new Error(m || '断言失败'); }

(async () => {
  await connect();
  await evaljs(`(function(){closeAllSheets();genCounter++;var r=ROOMS.find(function(x){return x.name==='TestV2';});enterRoom(r.id);document.querySelector('[data-subtab="files"]').click();return true;})()`);
  await sleep(500);

  await t('D4f 版本快照恢复 → 内容校验 (正确前缀)', async () => {
    assert(await evaljs(`B.saveWorkFile(curRoomId,'验收笔记2.md','# v3','tester').ok`) === true, '保存失败');
    const vers = await evaljs(`B.listVersions(curRoomId,'验收笔记2.md').versions.length`);
    assert(vers >= 1, '无历史版本');
    const vname = await evaljs(`B.listVersions(curRoomId,'验收笔记2.md').versions[0].name`);
    assert(await evaljs('B.restoreVersion(curRoomId,"验收笔记2.md",' + JSON.stringify(vname) + ').ok') === true, '恢复失败');
    const res = await evaljs(`B.readFile(curRoomId,'files/work/验收笔记2.md')`);
    assert(res.ok && res.content === '# v1', '内容不符: ' + JSON.stringify(res).slice(0, 100));
    return 'versions=' + vers + ', 恢复内容=# v1';
  });
  await t('D3f 文件卡片点击 → 预览打开 (修复验证)', async () => {
    await evaljs(`(function(){var el=document.querySelector('#storageList .st-card,[data-file]');if(!el)return 'NOCARD';el.click();return 'ok';})()`);
    await sleep(600);
    const open = await evaljs(`$('previewOverlay').style.display`);
    const body = await evaljs(`$('previewBody').textContent.slice(0,30)`);
    assert(open !== 'none', '预览未打开');
    assert(body.length > 0, '预览无内容');
    await evaljs(`$('previewClose').click()`);
    return '预览内容: ' + body.replace(/\n/g, ' ');
  });
  await t('D6f 模板「使用」→ 原生 prompt 对话框', async () => {
    await evaljs(`document.querySelector('[data-stype="template"]').click()`); await sleep(400);
    evaljs(`(function(){var el=document.querySelector('#storageList [data-act="use"]');if(el)el.click();return true;})()`, true);
    await sleep(1500);
    const { execSync } = require('child_process');
    const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
    const dump = execSync('"' + ADB + '" shell "uiautomator dump //sdcard/ui.xml >/dev/null && cat /sdcard/ui.xml"', { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
    const hasDlg = /目标文件名|EditText|确定/.test(dump);
    assert(hasDlg, '原生 prompt 未弹出');
    execSync('"' + ADB + '" shell input keyevent KEYCODE_BACK');
    await sleep(600);
    return 'prompt 弹出并取消';
  });

  const pass = results.filter(r => r.ok).length;
  console.log('\n===== 最终轮: ' + pass + '/' + results.length + ' 通过 =====');
  fs.writeFileSync('results4.json', JSON.stringify(results, null, 2));
  process.exit(0);
})().catch(e => { console.error('驱动崩溃:', e); process.exit(1); });
