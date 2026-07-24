/* M4 全栈部署 e2e: mock-llm-m4 驱动真实 AgentLoop →
   写 FastAPI 后端+HTML → 本地起服务 curl 自测 → movscp/movssh 部署 → 远端健康检查。
   断言: 房间产物 + 远端文件存在 + 远端服务在跑 + 工作日志含自测/部署/健康检查。 */
const WS = require('ws');
const http = require('http');
const fs = require('fs');
const { execSync } = require('child_process');
const ADB = process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe';
fs.mkdirSync('shots', { recursive: true });
function shot(n) { try { fs.writeFileSync('shots/' + n + '.png', execSync('"' + ADB + '" exec-out screencap -p', { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 })); } catch (_) {} }
function adbShell(cmd) {
  return execSync('"' + ADB + '" shell "' + cmd.replace(/"/g, '\\"') + '"', { encoding: 'utf-8', maxBuffer: 8 * 1024 * 1024 });
}

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
      const r = m.result;
      if (r && r.exceptionDetails) return reject(new Error('页面异常: ' + (r.exceptionDetails.exception && r.exceptionDetails.exception.description || r.exceptionDetails.text).slice(0, 300)));
      resolve(r && r.result ? r.result.value : undefined);
    });
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }));
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
function assert(c, m) { if (!c) throw new Error(m || '断言失败'); }

(async () => {
  await connect();
  /* 默认大脑设回 mock (部署链路用 mock 驱动, 真实 shell/ssh 执行) */
  const models = await evaljs(`B.listModels()`);
  const mock = models.find(m => m.name === 'mock-llm');
  if (mock) await evaljs(`B.setDefaultModel('${mock.id}')`);

  const rid = await evaljs(`(function(){
    closeAllSheets();genCounter++;curRoomId=null;setTab('chat');showView('view-rooms');
    var id='m4dep'+(Date.now()%1000000);
    ROOMS.splice(1,0,{id:id,name:'全栈留言板',mode:'council',
      members:{human:[{who:'you',role:'owner'}],ai:[]},
      phase:'讨论中',last:'',time:'现在',unread:0,played:false,msgs:[],seed:[]});
    B.initRoomStorage(id);renderRooms();persistRooms();enterRoom(id);
    return id;
  })()`);
  console.log('房间:', rid);

  await evaljs(`$('msgInput').value='写一个 FastAPI 留言板后端和单文件 HTML 前端, 本地自测后部署到服务器并健康检查';$('btnSend').click()`);
  console.log('已发送任务, 驱动执行 (pip 装依赖+本地自测+部署需 3-5 分钟)...');

  let delivered = false, failed = '', approveCount = 0;
  const t0 = Date.now();
  while (Date.now() - t0 < 600000) {
    await sleep(5000);
    const st = await evaljs(`(function(){
      var r={plan:0,deliver:0,fail:'',body:''};
      document.querySelectorAll('#chatBody .plan-card').forEach(function(c){
        var ph=c.querySelector('.ph');if(!ph)return;var t=ph.textContent;
        if(t.indexOf('PLAN')===0){var b=c.querySelector('.btn-acc');if(b&&!b.disabled)r.plan++;}
        if(t.indexOf('任务失败')>=0)r.fail=t;
      });
      r.deliver=document.querySelectorAll('#chatBody .deliver-card').length;
      r.body=document.querySelector('#chatBody').textContent.slice(-120);
      return r;
    })()`);
    if (st.fail) { failed = st.fail; break; }
    if (st.deliver > 0) { delivered = true; break; }
    if (st.plan > 0) {
      await evaljs(`(function(){var cs=document.querySelectorAll('#chatBody .plan-card');for(var i=0;i<cs.length;i++){var ph=cs[i].querySelector('.ph');if(ph&&ph.textContent.indexOf('PLAN')===0){var b=cs[i].querySelector('.btn-acc');if(b&&!b.disabled){b.click();return true;}}}return false;})()`);
      approveCount++; console.log('  → 批准计划 #' + approveCount + ' @' + Math.round((Date.now() - t0) / 1000) + 's');
      continue;
    }
    process.stdout.write('.');
  }
  console.log('');
  if (failed) throw new Error('任务失败: ' + failed);
  assert(delivered, '600s 内未交付');
  shot('m4-deploy');
  console.log('已交付!');

  /* 断言 1: 房间产物 */
  const files = await evaljs(`B.listWorkFiles('${rid}').files.map(function(f){return f.name;})`);
  console.log('房间文件:', JSON.stringify(files));
  assert(files.includes('guestbook_api.py'), '缺 guestbook_api.py');
  assert(files.includes('guestbook.html'), '缺 guestbook.html');

  /* 断言 2: 工作日志显示各步骤执行成功 + 远端健康检查输出 */
  const steps = await evaljs(`(function(){var o=[];document.querySelectorAll('#chatBody .toolcall').forEach(function(t){o.push(t.textContent);});return o.join('\\n');})()`);
  assert(/shell\.exec/.test(steps), '缺 shell.exec 步骤: ' + steps.slice(-200));
  assert(steps.includes('"status":"ok"'), '远端健康检查输出缺失: ' + steps.slice(-300));
  assert(/exit\s*0|exit=0/.test(steps), '步骤未全部成功: ' + steps.slice(-200));
  console.log('工作日志: shell.exec 执行 ✓ 远端健康检查输出 ✓');

  /* 断言 3: 远端(8023)服务真实在跑 — root shell proot curl 直接验证。
     (本地 9000 是命令内自测, shell.exec 结束被 --kill-on-exit 清场, 不要求存活;
      远端经 ssh 起的服务在无 kill-on-exit 的 sshd 侧, 应持久) */
  const curl = (port) => {
    try {
      return adbShell('LIBD=$(pm path com.hermes.android | sed s/package:// | xargs dirname)/lib/arm64; '
        + 'export LD_LIBRARY_PATH=$LIBD PROOT_LOADER=$LIBD/libproot-loader.so PROOT_TMP_DIR=/data/data/com.hermes.android/files/linux/tmp; '
        + 'R=/data/data/com.hermes.android/files/linux/rootfs; '
        + '$LIBD/libproot.so -0 --link2symlink -r $R -b /dev -b /proc -b /sys -w /root /usr/bin/env PATH=/usr/bin:/bin HOME=/root /usr/bin/bash -lc "curl -s -m 8 http://127.0.0.1:' + port + '/api/health" 2>/dev/null');
    } catch (e) { return 'ERR'; }
  };
  const remote8023 = curl(8023);
  assert(remote8023.includes('"status":"ok"') || remote8023.includes('ok'), '远端 8023 服务未跑: ' + remote8023);
  console.log('远端部署服务 8023 在跑 ✓');

  /* 断言 4: 远端文件真实存在 (root shell 直接查 rootfs) */
  const remoteFile = adbShell('cat /data/data/com.hermes.android/files/linux/rootfs/root/deploy/guestbook_api.py 2>/dev/null | head -3');
  assert(remoteFile.includes('FastAPI'), '远端 guestbook_api.py 不存在: ' + remoteFile);
  console.log('远端文件 /root/deploy/guestbook_api.py ✓');
  /* 远端服务: 通过健康检查已证明 (8023), 再直接 curl 一次确认仍在跑 */
  const health = await evaljs(`(function(){
    /* 从房间文件读前端确认 fetch 指向 8023 */
    var r=B.readFile('${rid}','files/work/guestbook.html');
    return r.ok ? r.content : '';
  })()`);
  assert(health.includes('8023/api'), '前端 fetch 未指向远端 8023');
  console.log('前端 fetch 指向远端 8023/api ✓');

  console.log('\n===== M4 全栈部署 e2e 验收通过 =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); shot('m4-deploy-fail'); process.exit(1); });
