/* MOV 真机 e2e: 烧烤摊点单 → 打包 APK → 安装 → 覆盖安装 */
const http = require('http');
const WS = require('ws');
const { execSync } = require('child_process');
const ADB = 'C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 21770d7d';
let ws, msgId = 0; const pending = new Map();
function connect() {
  return new Promise((resolve, reject) => {
    http.get('http://localhost:9222/json', res => {
      let d=''; res.on('data',c=>d+=c);
      res.on('end',()=>{
        const page = JSON.parse(d).find(t=>t.url.includes('hermes-shell'));
        if(!page) return reject(new Error('MOV 页面未找到'));
        ws = new WS(page.webSocketDebuggerUrl, {perMessageDeflate:false});
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
      if (r && r.exceptionDetails) return reject(new Error('页面异常: '+JSON.stringify(r.exceptionDetails).slice(0,200)));
      resolve(r && r.result ? r.result.value : undefined);
    });
    ws.send(JSON.stringify({ id, method:'Runtime.evaluate', params:{ expression, returnByValue:true } }));
  });
}
const sleep = ms => new Promise(r=>setTimeout(r,ms));
const sh = cmd => execSync(cmd, {encoding:'utf8', timeout:120000}).trim();
function assert(c,m){ if(!c) throw new Error(m||'断言失败'); }

(async () => {
  await connect();
  console.log('✓ CDP 已连接');

  /* ---- 1. 进房间, 发任务 ---- */
  const rid = await evaljs(`(function(){
    closeAllSheets();genCounter++;
    enterRoom('agente2e');clearRoomHistory('agente2e');return 'agente2e';
  })()`);
  await sleep(500);
  await evaljs(`$('msgInput').value='做一个烧烤摊点单应用: 羊肉串5块 牛肉串6块 板筋3块 烤鸡翅8块 烤韭菜8块 烤茄子10块 啤酒6块 可乐4块。要求: 单个 HTML 文件(所有代码内联, 不引用外部文件), 三个页签(点单/订单记录/经营统计), 数据用 localStorage 持久化。写完后打包成 APK。';$('btnSend').click()`);
  console.log('✓ 任务已下达, 等计划卡...');

  /* ---- 2. 自动批准: 计划卡 + 写入预览卡 ---- */
  let deliver=false, fail=false, apkClicked=false;
  const t0 = Date.now();
  while (Date.now()-t0 < 420000) {
    await sleep(3000);
    const st = await evaljs(`(function(){
      var out={};
      /* 批准计划 */
      var plans=document.querySelectorAll('#chatBody .plan-card');
      plans.forEach(function(c){
        var b=c.querySelector('.btn-acc');
        if(b&&!b.disabled&&c.textContent.indexOf('写入预览')<0){b.click();out.planApproved=true;}
      });
      /* 确认写入预览 (标题含 写入预览 的卡片) */
      var cards=document.querySelectorAll('#chatBody .msg.wide');
      cards.forEach(function(c){
        if(c.textContent.indexOf('写入预览')>=0){
          var btns=c.querySelectorAll('button');
          btns.forEach(function(b){if(b.textContent.indexOf('确认写入')>=0&&!b.disabled){b.click();out.previewApproved=true;}});
        }
      });
      var txt=$('chatBody').textContent;
      out.fail=txt.indexOf('任务失败')>=0;
      out.deliver=document.querySelectorAll('#chatBody .deliver-card').length>0;
      out.steps=document.querySelectorAll('#chatBody .toolcall').length;
      return out;
    })()`);
    process.stdout.write(`\r  步骤=${st.steps} 计划批准=${!!st.planApproved} 预览确认=${!!st.previewApproved} 交付=${st.deliver}   `);
    if (st.fail) { fail=true; break; }
    if (st.deliver) { deliver=true; break; }
  }
  console.log('');
  assert(!fail, '任务失败');
  assert(deliver, '420s 内未交付');
  console.log('✓ 已交付');

  /* ---- 3. 验证房间产出 ---- */
  const files = await evaljs(`(function(){
    var r=B.listWorkFiles('${rid}');
    var list=(typeof r==='string'?JSON.parse(r):r);
    return (list.files||list).map(function(f){return f.name||f;});
  })()`);
  console.log('房间产出:', JSON.stringify(files));
  const html = files.find(f=>String(f).indexOf('.html')>=0);
  const apk  = files.find(f=>String(f).indexOf('.apk')>=0);
  assert(html, '无 HTML 产出');
  assert(apk, '无 APK 产出');
  console.log('✓ HTML + APK 双产出:', html, '/', apk);

  /* ---- 4. 拉出 APK 安装 ---- */
  const base='/sdcard/Android/data/com.hermes.android/files/mov/rooms/agente2e/files/work';
  const apkName = String(apk).replace(/\(.*$/,'');
  sh(`${ADB} pull "${base}/${apkName}" C:/tmp/bbq.apk`);
  const pkg = sh(`${ADB} shell "pm list packages | grep movgen" || true`);
  console.log('现有 movgen 包:', pkg||'(无)');
  sh(`${ADB} install -r C:/tmp/bbq.apk`);
  const pkgLine = sh(`${ADB} shell "pm list packages | grep movgen"`);
  const pkgName = pkgLine.replace('package:','').trim();
  console.log('✓ 已安装:', pkgName);
  assert(/com\.movgen\./.test(pkgName), '包名异常: '+pkgName);

  /* ---- 5. 启动生成的应用, 截图 ---- */
  sh(`${ADB} shell "monkey -p ${pkgName} -c android.intent.category.LAUNCHER 1"`);
  await sleep(3000);
  sh(`${ADB} exec-out screencap -p > C:/tmp/bbq-app.png`);
  console.log('✓ 应用已启动, 截图 C:/tmp/bbq-app.png');

  /* ---- 6. 覆盖安装: 同文件重新打包, 包名必须一致 ---- */
  const r2 = await evaljs(`(function(){
    var r=B.buildApk('${rid}','${html}','烧烤摊点单');
    return typeof r==='string'?r:JSON.stringify(r);
  })()`);
  console.log('重打包结果:', r2);
  await sleep(2000);
  const files2 = await evaljs(`(function(){
    var r=B.listWorkFiles('${rid}');
    var list=(typeof r==='string'?JSON.parse(r):r);
    return (list.files||list).map(function(f){return f.name||f;});
  })()`);
  const apk2 = files2.filter(f=>String(f).indexOf('.apk')>=0).pop();
  sh(`${ADB} pull "${base}/${String(apk2).replace(/\(.*$/,'')}" C:/tmp/bbq2.apk`);
  sh(`${ADB} install -r C:/tmp/bbq2.apk`);
  const pkgLine2 = sh(`${ADB} shell "pm list packages | grep movgen"`);
  assert(pkgLine2.trim().split('\n').length===1, '出现了第二个 movgen 包! '+pkgLine2);
  assert(pkgLine2.replace('package:','').trim()===pkgName, '包名变了, 不是覆盖升级: '+pkgLine2);
  console.log('✓ 覆盖安装验证通过: 同包名', pkgName, ', 桌面无第二个图标');

  console.log('\n===== 烧烤摊 e2e 全部通过 =====');
  process.exit(0);
})().catch(e => { console.error('\n✗', e.message); process.exit(1); });
