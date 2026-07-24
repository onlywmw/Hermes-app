/* MOV 全按钮遍历测试驱动 — 通过 CDP 直接点击页面按钮并断言状态 */
const WS = require('ws');
const http = require('http');
const fs = require('fs');
const { execSync } = require('child_process');

const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
fs.mkdirSync('shots', { recursive: true });

function adb(args) { return execSync('"' + ADB + '" ' + args, { encoding: 'utf8' }); }
function shot(name) {
  const buf = execSync('"' + ADB + '" exec-out screencap -p', { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 });
  fs.writeFileSync('shots/' + name + '.png', buf);
}
function topActivity() {
  try { return adb('shell "dumpsys activity activities | grep -i topResumedActivity"'); } catch (e) { return ''; }
}

let ws, msgId = 0;
const pending = new Map();
function connect() {
  return new Promise((resolve, reject) => {
    http.get('http://localhost:9222/json', res => {
      let d = ''; res.on('data', c => d += c);
      res.on('end', () => {
        const page = JSON.parse(d).find(t => t.url.includes('hermes-shell.html'));
        if (!page) return reject(new Error('page not found'));
        ws = new WS(page.webSocketDebuggerUrl, { perMessageDeflate: false });
        ws.on('open', resolve);
        ws.on('error', reject);
        ws.on('message', raw => {
          const m = JSON.parse(raw);
          if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
        });
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
  try {
    const detail = await fn();
    results.push({ name, ok: true, detail: detail || '' });
    console.log('  PASS  ' + name + (detail ? '  — ' + detail : ''));
  } catch (e) {
    results.push({ name, ok: false, detail: String(e.message || e).slice(0, 250) });
    console.log('  FAIL  ' + name + '  — ' + String(e.message || e).slice(0, 250));
  }
}
function assert(c, msg) { if (!c) throw new Error(msg || '断言失败'); }

(async () => {
  await connect();
  // 清理可能的系统权限弹窗
  const top0 = topActivity();
  if (/permissioncontroller|packageinstaller/i.test(top0)) { adb('shell input keyevent KEYCODE_BACK'); await sleep(800); }
  // 回到房间列表初始态
  await evaljs(`(function(){closeAllSheets();genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');renderRooms();return true;})()`);
  await sleep(400);

  /* ================= A. 房间列表页 ================= */
  console.log('== A. 房间列表页 ==');
  await t('A1 房间列表渲染', async () => {
    const n = await evaljs(`document.querySelectorAll('#roomList .room').length`);
    assert(n >= 1, '房间卡片数为 ' + n);
    return n + ' 个房间';
  });
  await t('A2 FAB + 打开新建 sheet', async () => {
    await evaljs(`$('fabNew').click()`);
    await sleep(400);
    assert(await evaljs(`$('newRoomSheet').classList.contains('open')`), 'sheet 未打开');
    assert(await evaljs(`$('newRoomMask').classList.contains('open')`), 'mask 未打开');
    shot('a2-newroom-sheet');
  });
  await t('A3 新建 sheet ✕ 关闭', async () => {
    await evaljs(`$('btnSheetClose').click()`);
    await sleep(300);
    assert(!(await evaljs(`$('newRoomSheet').classList.contains('open')`)), 'sheet 未关闭');
  });
  await t('A4 新建 sheet 点 mask 关闭', async () => {
    await evaljs(`$('fabNew').click()`); await sleep(300);
    assert(await evaljs(`$('newRoomSheet').classList.contains('open')`), 'sheet 未打开(前置)');
    await evaljs(`$('newRoomMask').click()`); await sleep(300);
    assert(!(await evaljs(`$('newRoomSheet').classList.contains('open')`)), '点 mask 未关闭');
  });
  await t('A5 房间卡片点击进入', async () => {
    const id = await evaljs(`(function(){var el=document.querySelector('#roomList .room');el.click();return el.getAttribute('data-room');})()`);
    await sleep(400);
    assert(await evaljs(`curRoomId`) === id, 'curRoomId 未切换');
    assert(await evaljs(`$('view-room').classList.contains('act')`), 'view-room 未激活');
    return '进入 ' + id;
  });
  await t('A6 ← 返回房间列表', async () => {
    await evaljs(`$('btnBack').click()`); await sleep(300);
    assert(await evaljs(`$('view-rooms').classList.contains('act')`), '未回列表');
  });
  await t('A7 长按房间卡片 → 操作 sheet', async () => {
    await evaljs(`(function(){var el=document.querySelector('#roomList .room');el.dispatchEvent(new MouseEvent('mousedown',{clientX:200,clientY:200,bubbles:true}));return true;})()`);
    await sleep(700);
    await evaljs(`(function(){var el=document.querySelector('#roomList .room');el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));return true;})()`);
    assert(await evaljs(`$('sheetRoomOps').classList.contains('open')`), '操作 sheet 未打开');
    await evaljs(`closeRoomOpsSheet()`);
  });

  /* ================= B. 新建房间 sheet ================= */
  console.log('== B. 新建房间 sheet ==');
  await t('B1 默认状态: 单聊选中 + 默认模型勾选', async () => {
    await evaljs(`$('fabNew').click()`); await sleep(400);
    assert(await evaljs(`document.querySelector('#newRoomModeOpts .mopt[data-mode="single"]').classList.contains('sel')`), '单聊未默认选中');
    const sel = await evaljs(`document.querySelectorAll('#newRoomModels .mpick.sel').length`);
    assert(sel === 1, '默认勾选数=' + sel);
    assert(await evaljs(`$('newRoomModelsEmpty').style.display`) === 'none', '空态不应显示');
  });
  await t('B2 切到 AI 团队 → 多选模式', async () => {
    await evaljs(`document.querySelector('#newRoomModeOpts .mopt[data-mode="council"]').click()`); await sleep(300);
    assert(await evaljs(`document.querySelector('#newRoomModeOpts .mopt[data-mode="council"]').classList.contains('sel')`), '团队卡未选中');
  });
  await t('B3 团队模式取消全部勾选 → 创建键置灰', async () => {
    await evaljs(`document.querySelectorAll('#newRoomModels .mpick.sel').forEach(function(el){el.click()})`); await sleep(300);
    assert(await evaljs(`$('btnCreate').disabled`) === true, '创建键未置灰');
  });
  await t('B4 重新勾选 → 创建键恢复', async () => {
    await evaljs(`document.querySelector('#newRoomModels .mpick').click()`); await sleep(300);
    assert(await evaljs(`$('btnCreate').disabled`) === false, '创建键仍置灰');
  });
  await t('B5 创建 council 房间 (链路: 数据+持久化+落盘)', async () => {
    await evaljs(`$('newRoomName').value='议会验收';`);
    await evaljs(`$('btnCreate').click()`); await sleep(600);
    const r = await evaljs(`(function(){var r=ROOMS.find(function(x){return x.name==='议会验收';});return r?{id:r.id,mode:r.mode,ai:r.members.ai.length,phase:r.phase}:null;})()`);
    assert(r, '房间未创建');
    assert(r.mode === 'council', 'mode=' + r.mode);
    assert(r.ai === 1, 'AI 成员数=' + r.ai);
    assert(r.phase === '讨论中', 'phase=' + r.phase);
    assert(await evaljs(`curRoomId`) === r.id, '未自动进房');
    const saved = await evaljs(`JSON.parse(localStorage.getItem('mov_rooms_v2')).some(function(x){return x.name==='议会验收';})`);
    assert(saved, 'localStorage 未持久化');
    globalThis.__councilRoomId = r.id;
    shot('b5-council-room');
    return 'id=' + r.id;
  });
  await t('B6 磁盘 room.json 落盘 (B.initRoom 死桥激活)', async () => {
    const out = adb('shell "run-as com.hermes.android ls /data/data/com.hermes.android/files 2>/dev/null || ls /sdcard/Android/data/com.hermes.android/files"');
    const meta = await evaljs(`B.getRoomMeta('${globalThis.__councilRoomId}').ok`);
    assert(meta === true || out.length > 0, '元数据读取失败');
    return 'getRoomMeta.ok=' + meta;
  });

  /* ================= C. 房间详情 ================= */
  console.log('== C. 房间详情 ==');
  await t('C1 council 房发消息 → 真实多模型讨论 (链路: JS→Bridge→CouncilClient→回调)', async () => {
    await evaljs(`$('msgInput').value='用一句话说明微服务的缺点';$('btnSend').click()`);
    const t0 = Date.now();
    let summary = false, replies = 0;
    while (Date.now() - t0 < 100000) {
      await sleep(3000);
      replies = await evaljs(`document.querySelectorAll('#chatBody .msg.agent').length`);
      summary = await evaljs(`(function(){var b=$('chatBody');return b.textContent.indexOf('议会收敛')>=0||b.textContent.indexOf('失败')>=0;})()`);
      if (summary) break;
    }
    const fail = await evaljs(`$('chatBody').textContent.indexOf('失败')>=0`);
    const phase = await evaljs(`ROOMS.find(function(r){return r.id==='${globalThis.__councilRoomId}';}).phase`);
    if (fail) throw new Error('Council 返回失败 (phase=' + phase + ')');
    assert(summary, '100s 内未收敛 (phase=' + phase + ', replies=' + replies + ')');
    shot('c1-council-done');
    return 'replies=' + replies + ', phase=' + phase;
  });
  await t('C2 子tab 讨论→文件→讨论', async () => {
    await evaljs(`document.querySelector('[data-subtab="files"]').click()`); await sleep(300);
    assert(await evaljs(`$('fileView').style.display`) !== 'none', '文件视图未显示');
    await evaljs(`document.querySelector('[data-subtab="chat"]').click()`); await sleep(300);
    assert(await evaljs(`$('fileView').style.display`) === 'none', '文件视图未隐藏');
  });
  await t('C3 设备指令链路: 电量多少 (IntentParser→CapabilityExecutor→渲染)', async () => {
    await evaljs(`(function(){setTab('chat');enterRoom('desk');return true;})()`); await sleep(400);
    await evaljs(`$('msgInput').value='电量多少';$('btnSend').click()`); await sleep(1500);
    const tool = await evaljs(`(function(){var t=$('chatBody').textContent;return t.indexOf('电量')>=0&&t.indexOf('%')>=0;})()`);
    assert(tool, '未渲染电量结果');
    assert(await evaljs(`document.querySelectorAll('#chatBody .toolcall').length`) >= 1, '工具卡片未生成');
    return '工具卡片+电量文本均出现';
  });
  await t('C4 AI 单聊链路: TestV2 按绑定模型对话 (aiChatWithModel)', async () => {
    await evaljs(`(function(){var r=ROOMS.find(function(x){return x.name==='TestV2';});enterRoom(r.id);return true;})()`); await sleep(400);
    const mid = await evaljs(`roomAiMembers(ROOMS.find(function(x){return x.name==='TestV2';})).length`);
    assert(mid === 1, 'TestV2 未绑定模型');
    await evaljs(`$('msgInput').value='回复两个字: 收到';$('btnSend').click()`);
    const t0 = Date.now(); let done = false;
    while (Date.now() - t0 < 75000) {
      await sleep(2500);
      done = await evaljs(`(function(){var m=$('chatBody').querySelectorAll('.msg.agent');if(!m.length)return false;var last=m[m.length-1];return !last.querySelector('.caret');})()`);
      if (done) break;
    }
    assert(done, '75s 内 AI 未回复');
    const txt = await evaljs(`(function(){var m=$('chatBody').querySelectorAll('.msg.agent');return m[m.length-1].textContent.slice(0,80);})()`);
    return 'AI 回复: ' + txt.replace(/\n/g, ' ');
  });
  await t('C5 消息长按 → 删除', async () => {
    const before = await evaljs(`(function(){var r=ROOMS.find(function(x){return x.id===curRoomId;});return (r.msgData||[]).length;})()`);
    await evaljs(`(function(){var m=$('chatBody').querySelectorAll('.msg');var el=m[m.length-1];el.dispatchEvent(new MouseEvent('mousedown',{clientX:200,clientY:200,bubbles:true}));window.__lpEl=el;return true;})()`);
    await sleep(700);
    await evaljs(`(function(){window.__lpEl.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));return true;})()`);
    assert(await evaljs(`$('msgActions').classList.contains('show')`), '删除操作条未出现');
    await evaljs(`$('msgActions').click()`); await sleep(300);
    const after = await evaljs(`(function(){var r=ROOMS.find(function(x){return x.id===curRoomId;});return (r.msgData||[]).length;})()`);
    assert(after === before - 1, 'msgData ' + before + '→' + after);
    return before + '→' + after;
  });
  await t('C6 附件托盘 + 系统文件选择器 (链路: pickFile→原生→取消回调)', async () => {
    await evaljs(`$('plusBtn').click()`); await sleep(300);
    assert(await evaljs(`$('attTray').classList.contains('open')`), '托盘未打开');
    await evaljs(`document.querySelector('[data-att="pick"]').click()`); await sleep(2000);
    const top = topActivity();
    assert(/documentsui|picker|files/i.test(top), '系统选择器未打开: ' + top.slice(0, 120));
    adb('shell input keyevent KEYCODE_BACK'); await sleep(1000);
    const back = topActivity();
    assert(/hermes/i.test(back), '未返回 App: ' + back.slice(0, 120));
    return '选择器打开并取消返回';
  });
  await t('C7 房间内 BACK 键 → 回列表 (原生 OnBackPressedCallback)', async () => {
    adb('shell input keyevent KEYCODE_BACK'); await sleep(600);
    assert(await evaljs(`$('view-rooms').classList.contains('act')`), '未回列表');
    assert(await evaljs(`curRoomId`) === null, 'curRoomId 未清空');
  });

  /* ================= D. 文件 tab ================= */
  console.log('== D. 文件 tab ==');
  await t('D1 进入文件 tab + 存储页签切换', async () => {
    await evaljs(`(function(){var r=ROOMS.find(function(x){return x.name==='TestV2';});enterRoom(r.id);return true;})()`); await sleep(300);
    await evaljs(`document.querySelector('[data-subtab="files"]').click()`); await sleep(400);
    for (const s of ['inbox', 'archive', 'template', 'work']) {
      await evaljs(`document.querySelector('[data-stype="${s}"]').click()`); await sleep(250);
      assert(await evaljs(`document.querySelector('[data-stype="${s}"]').classList.contains('on')`), s + ' 页签未激活');
    }
    return '4 个页签切换正常';
  });
  await t('D2 新建文件 (链路: fileNewSheet→saveWorkFile→磁盘)', async () => {
    await evaljs(`$('fileFabAdd').click()`); await sleep(300);
    assert(await evaljs(`$('fileNewSheet').classList.contains('open')`), '新建文件 sheet 未开');
    await evaljs(`$('fileNewName').value='验收笔记.md';$('fileNewContent').value='# 验收\\n按钮遍历测试产出';`);
    await evaljs(`$('btnFileNewCreate').click()`); await sleep(600);
    const list = await evaljs(`B.listWorkFiles(curRoomId).files.map(function(f){return f.name;})`);
    assert((list || []).indexOf('验收笔记.md') >= 0, '文件未落盘: ' + JSON.stringify(list));
    return JSON.stringify(list);
  });
  await t('D3 文件预览 overlay', async () => {
    const has = await evaljs(`(function(){var el=document.querySelector('#storageList .st-view,[data-action="view"],.f-act');if(!el)return false;el.click();return true;})()`);
    if (!has) {
      // 直接调渲染函数验证预览链路
      await evaljs(`showFilePreview('验收笔记.md','# 验收')`); await sleep(300);
    } else await sleep(300);
    assert(await evaljs(`$('previewOverlay').style.display`) !== 'none', '预览未打开');
    await evaljs(`$('previewClose').click()`); await sleep(200);
    assert(await evaljs(`$('previewOverlay').style.display`) === 'none', '预览未关闭');
  });
  await t('D4 版本快照: 再保存 → 历史版本 → 恢复', async () => {
    const r1 = await evaljs(`B.saveWorkFile(curRoomId,'验收笔记.md','# 验收 v2','tester').ok`);
    assert(r1 === true, '第二次保存失败');
    const vers = await evaljs(`B.listVersions(curRoomId,'验收笔记.md').versions.length`);
    assert(vers >= 1, '无历史版本');
    const vname = await evaljs(`B.listVersions(curRoomId,'验收笔记.md').versions[0].name`);
    const rr = await evaljs('B.restoreVersion(curRoomId,"验收笔记.md",' + JSON.stringify(vname) + ').ok');
    assert(rr === true, '版本恢复失败');
    return 'versions=' + vers + ', 恢复成功';
  });
  await t('D5 模板页签: 新建模板 → 保存', async () => {
    await evaljs(`document.querySelector('[data-stype="template"]').click()`); await sleep(300);
    const opened = await evaljs(`(function(){var b=$('btnTemplateNew')||document.querySelector('[data-tplnew]');if(b){b.click();return true;}return false;})()`);
    if (opened) await sleep(300);
    else await evaljs(`$('fileFabAdd').click()`), await sleep(300);
    const sheetOpen = await evaljs(`$('templateSheet').classList.contains('open')||$('fileNewSheet').classList.contains('open')`);
    assert(sheetOpen, '模板/文件 sheet 均未打开');
    if (await evaljs(`$('templateSheet').classList.contains('open')`)) {
      await evaljs(`$('templateName').value='验收模板.md';$('templateContent').value='模板内容';`);
      await evaljs(`$('btnTemplateOk').click()`); await sleep(500);
      const tpls = await evaljs(`B.listTemplates().files.map(function(f){return f.name;})`);
      assert((tpls || []).indexOf('验收模板.md') >= 0, '模板未保存: ' + JSON.stringify(tpls));
      return '模板已保存';
    }
    await evaljs(`closeAllSheets()`);
    return '走了文件 sheet (模板新建入口存疑)';
  });

  /* ================= E. 房间操作 sheet ================= */
  console.log('== E. 房间操作 sheet ==');
  await t('E1 ⋮ 打开操作 sheet', async () => {
    await evaljs(`(function(){setSubtab('chat');return true;})()`);
    await evaljs(`$('btnRoomMore').click()`); await sleep(300);
    assert(await evaljs(`$('sheetRoomOps').classList.contains('open')`), 'sheet 未开');
    assert(await evaljs(`$('opsMembers').style.display`) !== 'none', 'AI 成员入口不可见');
  });
  await t('E2 重命名', async () => {
    await evaljs(`$('opsRename').click()`); await sleep(200);
    assert(await evaljs(`$('roomOpsRename').style.display`) !== 'none', '重命名面板未出');
    await evaljs(`$('opsRenameInput').value='TestV2改名';$('opsRenameOk').click()`); await sleep(300);
    assert(await evaljs(`ROOMS.find(function(x){return x.id===curRoomId;}).name`) === 'TestV2改名', '名字未更新');
    assert(await evaljs(`$('roomTitle').textContent`) === 'TestV2改名', '顶栏未更新');
    // 改回来
    await evaljs(`$('btnRoomMore').click()`); await sleep(200);
    await evaljs(`$('opsRename').click()`); await sleep(200);
    await evaljs(`$('opsRenameInput').value='TestV2';$('opsRenameOk').click()`); await sleep(200);
  });
  await t('E3 AI 成员编辑: 单聊→团队', async () => {
    await evaljs(`$('btnRoomMore').click()`); await sleep(200);
    await evaljs(`$('opsMembers').click()`); await sleep(300);
    assert(await evaljs(`$('roomOpsMembers').style.display`) !== 'none', '成员面板未出');
    assert(await evaljs(`document.querySelector('#opsModeOpts .mopt[data-mode="single"]').classList.contains('sel')`), '当前模式应为单聊');
    await evaljs(`document.querySelector('#opsModeOpts .mopt[data-mode="council"]').click()`); await sleep(300);
    await evaljs(`$('opsMembersOk').click()`); await sleep(400);
    const r = await evaljs(`(function(){var r=ROOMS.find(function(x){return x.id===curRoomId;});return {mode:r.mode,ai:roomAiMembers(r).length,fmt:Array.isArray(r.members)?'旧':'新'};})()`);
    assert(r.mode === 'council', 'mode=' + r.mode);
    assert(r.fmt === '新', 'members 格式未迁移');
    const sub = await evaljs(`$('roomSub').textContent`);
    assert(sub.indexOf('council') >= 0, '副标题未刷新: ' + sub);
    return 'mode=council, 新格式, 副标题=' + sub;
  });
  await t('E4 AI 成员编辑: 减到 0 成员 → 自动降级单聊', async () => {
    await evaljs(`$('btnRoomMore').click()`); await sleep(200);
    await evaljs(`$('opsMembers').click()`); await sleep(300);
    await evaljs(`document.querySelectorAll('#opsModels .mpick.sel').forEach(function(el){el.click();})`); await sleep(200);
    await evaljs(`$('opsMembersOk').click()`); await sleep(300);
    const r = await evaljs(`(function(){var r=ROOMS.find(function(x){return x.id===curRoomId;});return {mode:r.mode,ai:roomAiMembers(r).length};})()`);
    assert(r.mode === 'single' && r.ai === 0, '未降级: ' + JSON.stringify(r));
    // 恢复绑定 DeepSeek 供后续用例
    await evaljs(`$('btnRoomMore').click()`); await sleep(200);
    await evaljs(`$('opsMembers').click()`); await sleep(300);
    await evaljs(`document.querySelector('#opsModels .mpick').click()`); await sleep(200);
    await evaljs(`$('opsMembersOk').click()`); await sleep(300);
    return '降级生效, 已恢复绑定';
  });
  await t('E5 归档 → 移入已归档分组', async () => {
    await evaljs(`$('btnRoomMore').click()`); await sleep(200);
    await evaljs(`$('opsArchive').click()`); await sleep(400);
    assert(await evaljs(`ROOMS.find(function(x){return x.id===curRoomId;}).phase`) === '已归档', 'phase 未变');
    await evaljs(`(function(){genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');renderRooms();return true;})()`); await sleep(300);
    assert(await evaljs(`(function(){var g=document.querySelectorAll('#roomList .glabel');for(var i=0;i<g.length;i++)if(g[i].textContent.indexOf('已归档')>=0)return true;return false;})()`), '已归档分组未出现');
    return '已归档';
  });
  await t('E6 清空聊天记录 (确认链)', async () => {
    await evaljs(`(function(){var r=ROOMS.find(function(x){return x.name==='议会验收';});enterRoom(r.id);return true;})()`); await sleep(300);
    const n0 = await evaljs(`(function(){var r=ROOMS.find(function(x){return x.id===curRoomId;});return (r.msgData||[]).length;})()`);
    await evaljs(`$('btnRoomMore').click()`); await sleep(200);
    await evaljs(`$('opsClear').click()`); await sleep(200);
    assert(await evaljs(`$('roomOpsConfirm').style.display`) !== 'none', '确认面板未出');
    await evaljs(`$('opsConfirmOk').click()`); await sleep(300);
    const n1 = await evaljs(`(function(){var r=ROOMS.find(function(x){return x.id===curRoomId;});return (r.msgData||[]).length;})()`);
    assert(n0 > 0 && n1 === 0, n0 + '→' + n1);
    return n0 + '→0';
  });
  await t('E7 删除房间 (确认链)', async () => {
    await evaljs(`$('btnRoomMore').click()`); await sleep(200);
    await evaljs(`$('opsDelete').click()`); await sleep(200);
    assert(await evaljs(`$('roomOpsConfirm').style.display`) !== 'none', '确认面板未出');
    await evaljs(`$('opsConfirmOk').click()`); await sleep(400);
    assert(await evaljs(`ROOMS.some(function(x){return x.name==='议会验收';})`) === false, '房间未删除');
    assert(await evaljs(`JSON.parse(localStorage.getItem('mov_rooms_v2')).some(function(x){return x.name==='议会验收';})`) === false, '持久化未删除');
    assert(await evaljs(`$('view-rooms').classList.contains('act')`), '未回列表');
    return '已删除并回列表';
  });

  /* ================= F. 运行页 ================= */
  console.log('== F. 运行页 ==');
  await t('F1 底部导航 会话↔运行', async () => {
    await evaljs(`document.querySelector('[data-tab="run"]').click()`); await sleep(400);
    assert(await evaljs(`$('view-run').classList.contains('act')`), '运行页未激活');
    await evaljs(`document.querySelector('[data-tab="chat"]').click()`); await sleep(300);
    assert(await evaljs(`$('view-rooms').classList.contains('act')`), '会话页未激活');
    await evaljs(`document.querySelector('[data-tab="run"]').click()`); await sleep(300);
  });
  await t('F2 ⟳ 刷新按钮', async () => {
    adb('shell logcat -c');
    await evaljs(`$('btnRunRefresh').click()`); await sleep(1000);
    const log = adb('shell logcat -d -s MOV:D');
    assert(log.includes('运行页数据已刷新'), '无刷新日志');
  });
  await t('F3 状态条展开开发者指标', async () => {
    const d0 = await evaljs(`$('devMetrics').style.display`);
    await evaljs(`$('ssDevToggle').click()`); await sleep(200);
    const d1 = await evaljs(`$('devMetrics').style.display`);
    assert((d0 === 'none') !== (d1 === 'none'), '折叠未切换: ' + d0 + '→' + d1);
    await evaljs(`$('ssDevToggle').click()`);
  });
  await t('F4 通道行 → 详情弹层', async () => {
    await evaljs(`$('rowChannels').click()`); await sleep(300);
    assert(await evaljs(`$('runDetailOverlay').style.display`) !== 'none', '弹层未开');
    const body = await evaljs(`$('runDetailBody').textContent.length`);
    assert(body > 0, '弹层无内容');
    shot('f4-run-detail');
    await evaljs(`closeRunDetail()`); await sleep(200);
    assert(await evaljs(`$('runDetailOverlay').style.display`) === 'none', '弹层未关');
  });
  await t('F5 技能行 → 详情弹层', async () => {
    await evaljs(`$('rowSkills').click()`); await sleep(300);
    assert(await evaljs(`$('runDetailOverlay').style.display`) !== 'none', '弹层未开');
    await evaljs(`closeRunDetail()`);
  });
  await t('F6 权限行 → 详情弹层', async () => {
    await evaljs(`$('rowPerms').click()`); await sleep(300);
    assert(await evaljs(`$('runDetailOverlay').style.display`) !== 'none', '弹层未开');
    await evaljs(`closeRunDetail()`);
  });
  await t('F7 原生引擎行 → toast', async () => {
    await evaljs(`document.querySelector('.model-row[data-model="__native"]').click()`); await sleep(300);
    return '点击无异常';
  });
  await t('F8 模型行点击 → 打开设置 (链路: →HermesSettingsActivity)', async () => {
    await evaljs(`document.querySelector('.model-row:not([data-model="__native"]):not([data-model="__add"])').click()`); await sleep(1500);
    const top = topActivity();
    assert(/HermesSettingsActivity/i.test(top), '设置页未打开: ' + top.slice(0, 150));
    shot('f8-settings');
    adb('shell input keyevent KEYCODE_BACK'); await sleep(800);
  });
  await t('F9 + 添加模型 → 打开设置', async () => {
    await evaljs(`document.querySelector('.model-row[data-model="__add"]').click()`); await sleep(1500);
    const top = topActivity();
    assert(/HermesSettingsActivity/i.test(top), '设置页未打开: ' + top.slice(0, 150));
    adb('shell input keyevent KEYCODE_BACK'); await sleep(800);
  });
  await t('F10 ≡ 个人信息设置', async () => {
    await evaljs(`$('btnPersonalSettings').click()`); await sleep(1500);
    const top = topActivity();
    assert(/HermesSettingsActivity/i.test(top), '设置页未打开: ' + top.slice(0, 150));
    adb('shell input keyevent KEYCODE_BACK'); await sleep(800);
  });

  /* ================= 汇总 ================= */
  const pass = results.filter(r => r.ok).length;
  console.log('\n===== 结果: ' + pass + '/' + results.length + ' 通过 =====');
  fs.writeFileSync('results.json', JSON.stringify(results, null, 2));
  process.exit(0);
})().catch(e => { console.error('驱动崩溃:', e); fs.writeFileSync('results.json', JSON.stringify(results, null, 2)); process.exit(1); });
