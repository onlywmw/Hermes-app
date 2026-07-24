/* stage C: 断网 → 失败卡三问 → 恢复 */
const http = require('http');
const WS = require('ws');
const { execSync } = require('child_process');
const ADB = 'C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 21770d7d';
let ws, msgId = 0; const pending = new Map();
function connect(){return new Promise((resolve,reject)=>{
  http.get('http://localhost:9222/json',res=>{let d='';res.on('data',c=>d+=c);res.on('end',()=>{
    const page=JSON.parse(d).find(t=>t.url.includes('hermes-shell'));
    ws=new WS(page.webSocketDebuggerUrl,{perMessageDeflate:false});
    ws.on('open',resolve);ws.on('error',reject);
    ws.on('message',raw=>{const m=JSON.parse(raw);if(m.id&&pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id);}});
  });}).on('error',reject);
});}
function evaljs(expression){return new Promise((resolve,reject)=>{
  const id=++msgId;
  pending.set(id,m=>{ if(m.error)return reject(new Error(m.error.message));
    const r=m.result;
    if(r&&r.exceptionDetails)return reject(new Error('页面异常: '+JSON.stringify(r.exceptionDetails).slice(0,300)));
    resolve(r&&r.result?r.result.value:undefined); });
  ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,returnByValue:true,awaitPromise:true}}));
});}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const sh=cmd=>execSync(cmd,{encoding:'utf8',timeout:60000}).trim();
function assert(c,m){if(!c)throw new Error(m||'断言失败');}

(async()=>{
  await connect();
  /* 恢复 MOV 前台 + 断网 */
  sh(`${ADB} shell am start -n com.hermes.android/.HermesActivity`);
  await sleep(1500);
  sh(`${ADB} shell svc wifi disable`);
  sh(`${ADB} shell svc data disable`);
  console.log('✓ 已断网');
  try{
    await evaljs(`(function(){closeAllSheets();genCounter++;enterRoom('agente2e');clearRoomHistory('agente2e');return true;})()`);
    await sleep(500);
    await evaljs(`$('msgInput').value='写一个 hello.html 页面';$('btnSend').click()`);
    console.log('✓ 任务已下达 (断网中), 等失败卡...');
    let found=false;
    const t0=Date.now();
    while(Date.now()-t0<180000){
      await sleep(3000);
      const st=await evaljs(`(function(){
        var cards=document.querySelectorAll('#chatBody .plan-card');
        var failCard=null;
        cards.forEach(function(c){if(c.textContent.indexOf('任务失败')>=0)failCard=c;});
        if(!failCard)return {fail:false};
        return {
          fail:true,
          hasReason:(failCard.querySelector('.fail-reason')||{}).textContent||'',
          meta:(failCard.querySelector('.fail-meta')||{}).textContent||'',
          retry:!!Array.prototype.find.call(failCard.querySelectorAll('button'),function(b){return b.textContent.indexOf('重开任务')>=0;}),
          sendBtn:$('btnSend').textContent
        };
      })()`);
      process.stdout.write(`\r  等待失败卡... ${((Date.now()-t0)/1000)|0}s   `);
      if(st.fail){
        console.log('\n✓ 失败卡出现');
        console.log('  原因:', st.hasReason);
        console.log('  进度:', st.meta);
        console.log('  重开按钮:', st.retry?'✓ 有':'✗ 无');
        console.log('  发送键状态:', st.sendBtn, '(终态化, 无假卡死)');
        assert(st.hasReason.length>0,'缺人话原因');
        assert(st.retry,'缺重开按钮');
        assert(st.sendBtn.indexOf('■')<0,'发送键仍卡在停止态 = 假卡死');
        found=true;break;
      }
    }
    assert(found,'180s 内未出现失败卡');
    sh(`${ADB} exec-out screencap -p > C:/tmp/fail-real.png`);
    console.log('✓ 真机截图 C:/tmp/fail-real.png');
  } finally {
    sh(`${ADB} shell svc wifi enable`);
    sh(`${ADB} shell svc data enable`);
    console.log('✓ 网络已恢复');
  }
  console.log('\n===== 失败路径 e2e 通过 =====');
  process.exit(0);
})().catch(e=>{console.error('\n✗',e.message);process.exit(1);});
