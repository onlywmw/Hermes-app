/* 回归复测: 首轮失败项 + 修复验证 */
const WS = require('ws');
const http = require('http');
const fs = require('fs');
const { execSync } = require('child_process');
const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
fs.mkdirSync('shots', { recursive: true });
function adb(a) { return execSync('"' + ADB + '" ' + a, { encoding: 'utf8' }); }
function shot(n) { fs.writeFileSync('shots/' + n + '.png', execSync('"' + ADB + '" exec-out screencap -p', { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 })); }
function topActivity() { try { return adb('shell "dumpsys activity activities | grep -i topResumedActivity"'); } catch (e) { return ''; } }

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
  await evaljs(`(function(){closeAllSheets();genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');renderRooms();return true;})()`);
  await sleep(400);

  console.log('== 回归: C 区 ==');
  await t('C5r 新 push 的消息可长按删除 (修复验证)', async () => {
    await evaljs(`(function(){var r=ROOMS.find(function(x){return x.name==='TestV2';});enterRoom(r.id);return true;})()`); await sleep(400);
    await evaljs(`$('msgInput').value='长按删除测试';$('btnSend').click()`); await sleep(1500);
    const before = await evaljs(`(function(){var r=ROOMS.find(function(x){return x.id===curRoomId;});return (r.msgData||[]).length;})()`);
    await evaljs(`(function(){var m=$('chatBody').querySelectorAll('.msg');var el=m[m.length-1];el.dispatchEvent(new MouseEvent('mousedown',{clientX:200,clientY:200,bubbles:true}));window.__lpEl=el;return true;})()`);
    await sleep(700);
    await evaljs(`window.__lpEl.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}))`);
    assert(await evaljs(`$('msgActions').classList.contains('show')`), '删除操作条未出现');
    await evaljs(`$('msgActions').click()`); await sleep(300);
    const after = await evaljs(`(function(){var r=ROOMS.find(function(x){return x.id===curRoomId;});return (r.msgData||[]).length;})()`);
    assert(after === before - 1, 'msgData ' + before + '→' + after);
    return before + '→' + after;
  });
  await t('C7r 房间内 BACK 键 → 回列表 (先收键盘)', async () => {
    adb('shell input keyevent KEYCODE_BACK'); await sleep(500); // 收 IME
    adb('shell input keyevent KEYCODE_BACK'); await sleep(800); // 触发返回
    const inList = await evaljs(`$('view-rooms').classList.contains('act')`);
    if (!inList) { adb('shell input keyevent KEYCODE_BACK'); await sleep(800); }
    assert(await evaljs(`$('view-rooms').classList.contains('act')`), '未回列表');
  });

  console.log('== 回归: D 区 ==');
  await t('D2r 文件 FAB 点击 → 系统选择器 (导入链路)', async () => {
    await evaljs(`(function(){var r=ROOMS.find(function(x){return x.name==='TestV2';});enterRoom(r.id);return true;})()`); await sleep(300);
    await evaljs(`document.querySelector('[data-subtab="files"]').click()`); await sleep(400);
    await evaljs(`$('fileFabAdd').click()`); await sleep(2000);
    const top = topActivity();
    assert(/documentsui|picker|files/i.test(top), '选择器未打开: ' + top.slice(0, 120));
    adb('shell input keyevent KEYCODE_BACK'); await sleep(1000);
    return '选择器打开并取消';
  });
  await t('D2b 长按 FAB → 新建文件 sheet → 创建落盘', async () => {
    await evaljs(`(function(){var el=$('fileFabAdd');el.dispatchEvent(new MouseEvent('mousedown',{clientX:200,clientY:200,bubbles:true}));return true;})()`);
    await sleep(700);
    await evaljs(`$('fileFabAdd').dispatchEvent(new MouseEvent('mouseup',{bubbles:true}))`); await sleep(300);
    assert(await evaljs(`$('fileNewSheet').classList.contains('open')`), '新建文件 sheet 未开');
    await evaljs(`$('fileNewName').value='验收笔记.md';$('fileNewContent').value='# 验收 v1';`);
    await evaljs(`$('btnFileNewCreate').click()`); await sleep(600);
    const list = await evaljs(`B.listWorkFiles(curRoomId).files.map(function(f){return f.name;})`);
    assert((list || []).indexOf('验收笔记.md') >= 0, '文件未落盘: ' + JSON.stringify(list));
    return '已创建: ' + JSON.stringify(list);
  });
  await t('D4r 版本快照: 再保存 → 历史版本 → 恢复', async () => {
    assert(await evaljs(`B.saveWorkFile(curRoomId,'验收笔记.md','# 验收 v2','tester').ok`) === true, '第二次保存失败');
    const vers = await evaljs(`B.listVersions(curRoomId,'验收笔记.md').versions.length`);
    assert(vers >= 1, '无历史版本');
    const vname = await evaljs(`B.listVersions(curRoomId,'验收笔记.md').versions[0].name`);
    assert(await evaljs('B.restoreVersion(curRoomId,"验收笔记.md",' + JSON.stringify(vname) + ').ok') === true, '恢复失败');
    const content = await evaljs(`B.readFile(curRoomId,'验收笔记.md').content`);
    assert(content === '# 验收 v1', '恢复内容不符: ' + content);
    return 'versions=' + vers + ', 恢复内容正确';
  });
  await t('D5r 模板页签: 新建模板 → 保存', async () => {
    await evaljs(`document.querySelector('[data-stype="template"]').click()`); await sleep(400);
    const clicked = await evaljs(`(function(){var btns=document.querySelectorAll('#storageList *');for(var i=0;i<btns.length;i++){if(btns[i].textContent.trim().length<12&&/新建|＋|\\+/.test(btns[i].textContent)){btns[i].click();return btns[i].textContent.trim();}}return null;})()`);
    assert(clicked !== null || await evaljs(`$('templateSheet').classList.contains('open')`), '模板新建入口未找到');
    await sleep(400);
    assert(await evaljs(`$('templateSheet').classList.contains('open')`), '模板 sheet 未开 (入口=' + clicked + ')');
    await evaljs(`$('templateName').value='验收模板.md';$('templateContent').value='模板内容';`);
    await evaljs(`$('btnTemplateOk').click()`); await sleep(500);
    const tpls = await evaljs(`B.listTemplates().files.map(function(f){return f.name;})`);
    assert((tpls || []).indexOf('验收模板.md') >= 0, '模板未保存: ' + JSON.stringify(tpls));
    return '模板已保存';
  });

  console.log('== 回归: F 区 ==');
  await t('F8r 模型行点击 → 打开设置', async () => {
    await evaljs(`(function(){closeAllSheets();genCounter++;curRoomId=null;setTab('run');return true;})()`); await sleep(600);
    await evaljs(`document.querySelector('.model-row:not([data-model="__native"]):not([data-model="__add"])').click()`); await sleep(3000);
    const top = topActivity();
    assert(/HermesSettingsActivity/i.test(top), '设置页未打开: ' + top.slice(0, 150));
    adb('shell input keyevent KEYCODE_BACK'); await sleep(800);
  });
  await t('F11r Cron 创建 (合法设备指令)', async () => {
    await evaljs(`(function(){setTab('run');return true;})()`); await sleep(400);
    const n0 = await evaljs(`B.listCron().length`);
    await evaljs(`$('cronInput').value='每天 8:30 打开手电筒';`);
    await evaljs(`$('btnCronCreate').click()`); await sleep(800);
    const jobs = await evaljs(`B.listCron()`);
    assert(jobs.length === n0 + 1, '任务数未增加 (res 应 toast)');
    const j = jobs.find(function (x) { return x.command.indexOf('手电筒') >= 0; });
    assert(j, '未找到任务');
    assert(j.cron === '30 8 * * *', 'cron 表达式: ' + j.cron);
    globalThis.__cronId = j.id;
    return 'cron=' + j.cron;
  });
  await t('F11b Cron 创建 (非法指令 → 明确拒绝)', async () => {
    const n0 = await evaljs(`B.listCron().length`);
    await evaljs(`$('cronInput').value='每天 8:30 汇总邮箱';`);
    await evaljs(`$('btnCronCreate').click()`); await sleep(500);
    assert(await evaljs(`B.listCron().length`) === n0, '非法指令不应创建');
    return '拒绝生效';
  });
  await t('F12r Cron 开关切换', async () => {
    const e0 = await evaljs('B.listCron().find(function(j){return j.id===' + JSON.stringify(globalThis.__cronId) + ';}).enabled');
    await evaljs('document.querySelector(\'[data-toggle="' + globalThis.__cronId + '"]\').click()'); await sleep(600);
    const e1 = await evaljs('B.listCron().find(function(j){return j.id===' + JSON.stringify(globalThis.__cronId) + ';}).enabled');
    assert(e0 !== e1, '开关未切换: ' + e0 + '→' + e1);
    return e0 + '→' + e1;
  });
  await t('F13r Cron 删除 (原生 confirm 对话框)', async () => {
    await evaljs('document.querySelector(\'[data-del="' + globalThis.__cronId + '"]\').click()'); await sleep(1200);
    // 原生 AlertDialog 应弹出 → 点确定
    const btn = adb('shell "uiautomator dump //sdcard/ui.xml >/dev/null && cat /sdcard/ui.xml"');
    const hasDialog = /android\.widget\.Button[^>]*text="(确定|OK)"/i.test(btn) || /确定/.test(btn);
    if (hasDialog) {
      const m = btn.match(/text="(确定|OK)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) || btn.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="(确定|OK)"/);
      if (m) {
        const nums = m.slice(2).map(Number);
        if (nums.length >= 4) adb('shell input tap ' + ((nums[0] + nums[2]) / 2 | 0) + ' ' + ((nums[1] + nums[3]) / 2 | 0));
      } else adb('shell input keyevent KEYCODE_ENTER');
    } else {
      adb('shell input keyevent KEYCODE_ENTER');
    }
    await sleep(800);
    const jobs = await evaljs(`B.listCron()`);
    assert(!jobs.some(function (j) { return j.id === globalThis.__cronId; }), '任务仍在');
    return 'confirm 对话框点确定后删除成功';
  });

  const pass = results.filter(r => r.ok).length;
  console.log('\n===== 回归结果: ' + pass + '/' + results.length + ' 通过 =====');
  fs.writeFileSync('results2.json', JSON.stringify(results, null, 2));
  process.exit(0);
})().catch(e => { console.error('驱动崩溃:', e); fs.writeFileSync('results2.json', JSON.stringify(results, null, 2)); process.exit(1); });
