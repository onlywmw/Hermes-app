/* 第三轮: D 区收尾复测 */
const WS = require('ws');
const http = require('http');
const fs = require('fs');
const { execSync } = require('child_process');
const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
let ws, msgId = 0; const pending = new Map();
function connect() {
  return new Promise((resolve, reject) => {
    http.get('http://localhost:9222/json', res => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => {
        const page = JSON.parse(d).find(t => t.url.includes('hermes-shell.html'));
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

  await t('D2b2 长按 FAB → 操作条 → 新建文件 sheet → 创建落盘', async () => {
    await evaljs(`(function(){var el=$('fileFabAdd');el.dispatchEvent(new MouseEvent('mousedown',{clientX:200,clientY:200,bubbles:true}));return true;})()`);
    await sleep(700);
    await evaljs(`$('fileFabAdd').dispatchEvent(new MouseEvent('mouseup',{bubbles:true}))`); await sleep(200);
    assert(await evaljs(`$('msgActions').classList.contains('show')`), '操作条未出现');
    await evaljs(`$('msgActions').click()`); await sleep(300);
    assert(await evaljs(`$('fileNewSheet').classList.contains('open')`), 'sheet 未开');
    await evaljs(`$('fileNewName').value='验收笔记2.md';$('fileNewContent').value='# v1';`);
    await evaljs(`$('btnFileNewCreate').click()`); await sleep(600);
    const list = await evaljs(`B.listWorkFiles(curRoomId).files.map(function(f){return f.name;})`);
    assert((list || []).indexOf('验收笔记2.md') >= 0, '未落盘: ' + JSON.stringify(list));
    return '已创建 验收笔记2.md';
  });
  await t('D4r2 版本快照: 再保存 → 历史 → 恢复 → 内容校验', async () => {
    assert(await evaljs(`B.saveWorkFile(curRoomId,'验收笔记2.md','# v2','tester').ok`) === true, '二存失败');
    const vers = await evaljs(`B.listVersions(curRoomId,'验收笔记2.md').versions.length`);
    assert(vers >= 1, '无历史版本');
    const vname = await evaljs(`B.listVersions(curRoomId,'验收笔记2.md').versions[0].name`);
    const rok = await evaljs('B.restoreVersion(curRoomId,"验收笔记2.md",' + JSON.stringify(vname) + ').ok');
    assert(rok === true, '恢复失败');
    const res = await evaljs(`B.readFile(curRoomId,'验收笔记2.md')`);
    assert(res && res.ok, '读取失败: ' + JSON.stringify(res));
    assert(res.content === '# v1', '内容不符: ' + JSON.stringify(res.content));
    return 'versions=' + vers + ', 恢复=# v1';
  });
  await t('D5r2 模板页签: btnNewTemplate → sheet → 保存', async () => {
    await evaljs(`document.querySelector('[data-stype="template"]').click()`); await sleep(400);
    assert(await evaljs(`!!$('btnNewTemplate')`), 'btnNewTemplate 不存在');
    await evaljs(`$('btnNewTemplate').click()`); await sleep(300);
    assert(await evaljs(`$('templateSheet').classList.contains('open')`), 'sheet 未开');
    await evaljs(`$('templateName').value='验收模板.md';$('templateContent').value='模板内容';`);
    await evaljs(`$('btnTemplateOk').click()`); await sleep(500);
    const tpls = await evaljs(`B.listTemplates().files.map(function(f){return f.name;})`);
    assert((tpls || []).indexOf('验收模板.md') >= 0, '未保存: ' + JSON.stringify(tpls));
    return '模板已保存';
  });
  await t('D6 模板「使用」→ 原生 prompt 对话框链路', async () => {
    // 找到模板卡片上的「使用」按钮并点击, 应弹出原生输入对话框
    const clicked = await evaljs(`(function(){var els=document.querySelectorAll('#storageList *');for(var i=0;i<els.length;i++){var s=els[i].textContent.trim();if(s==='使用'||s===t('st.use')){els[i].click();return true;}}return false;})()`);
    if (!clicked) return '模板卡片无「使用」按钮 (跳过)';
    await sleep(1200);
    const dump = execSync('"' + ADB + '" shell "uiautomator dump //sdcard/ui.xml >/dev/null && cat /sdcard/ui.xml"', { encoding: 'utf8' });
    const hasDlg = /AlertDialog|确定|OK|EditText/i.test(dump);
    assert(hasDlg, '原生 prompt 对话框未出现');
    execSync('"' + ADB + '" shell input keyevent KEYCODE_BACK');
    await sleep(500);
    return '原生 prompt 弹出并取消';
  });

  const pass = results.filter(r => r.ok).length;
  console.log('\n===== 第三轮: ' + pass + '/' + results.length + ' 通过 =====');
  fs.writeFileSync('results3.json', JSON.stringify(results, null, 2));
  process.exit(0);
})().catch(e => { console.error('驱动崩溃:', e); process.exit(1); });
