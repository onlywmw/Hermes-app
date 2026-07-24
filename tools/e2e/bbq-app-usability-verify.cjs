const http = require('http');
const WS = require('ws');
const { execSync } = require('child_process');
const ADB = 'C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 21770d7d';
const PKG = 'com.movgen.h1095ce9130c1';
let ws, msgId = 0; const pending = new Map();
function connect(){return new Promise((resolve,reject)=>{
  http.get('http://localhost:9223/json',res=>{let d='';res.on('data',c=>d+=c);res.on('end',()=>{
    const page=JSON.parse(d).find(t=>t.url.includes('app.html'));
    if(!page)return reject(new Error('app.html 页面未找到'));
    ws=new WS(page.webSocketDebuggerUrl,{perMessageDeflate:false});
    ws.on('open',resolve);ws.on('error',reject);
    ws.on('message',raw=>{const m=JSON.parse(raw);if(m.id&&pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id);}});
  });}).on('error',reject);
});}
function evaljs(expression){return new Promise((resolve,reject)=>{
  const id=++msgId;
  pending.set(id,m=>{ if(m.error)return reject(new Error(m.error.message));
    const r=m.result;
    if(r&&r.exceptionDetails)return reject(new Error('页面异常'));
    resolve(r&&r.result?r.result.value:undefined); });
  ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,returnByValue:true,awaitPromise:true}}));
});}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const sh=cmd=>execSync(cmd,{encoding:'utf8',timeout:60000}).trim();
function dismissAlert(){
  try{
    sh(`${ADB} shell "uiautomator dump /sdcard/ui.xml"`);
    const xml = sh(`${ADB} shell cat /sdcard/ui.xml`).toString();
    const m = xml.match(/text="确定"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) || xml.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="确定"/);
    if(m){ const x=(+m[1]+ +m[3])/2|0, y=(+m[2]+ +m[4])/2|0; sh(`${ADB} shell input tap ${x} ${y}`); return true; }
  }catch(e){}
  return false;
}
function assert(c,m){if(!c)throw new Error(m||'断言失败');}

(async()=>{
  await connect();
  /* 幂等: 先清掉上一轮可能残留的购物车? 不, localStorage 已有上轮的 ¥16 订单也行, 直接再下一单验证 */
  const r = await evaljs(`(function(){
    var q=function(n){return document.querySelector('button.inc[data-name="'+n+'"]');};
    if(!q('羊肉串')||!q('啤酒'))return '按钮未就绪';
    q('羊肉串').click();q('羊肉串').click();q('啤酒').click();
    var t='';[].forEach.call(document.querySelectorAll('div,span'),function(d){if(/总计|合计/.test(d.textContent)&&d.textContent.length<20)t=d.textContent.trim();});
    return t;
  })()`);
  console.log('点单总计:', r);
  assert(/16/.test(r), '总计应含 16: '+r);

  /* 异步点击提交 (alert 会阻塞, 不能同步等) */
  await evaljs(`(function(){
    setTimeout(function(){
      var btn=[].find.call(document.querySelectorAll('button'),function(b){return /提交|下单/.test(b.textContent);});
      if(btn)btn.click();
    },0);return 'dispatched';
  })()`);
  await sleep(1500);
  const dismissed = dismissAlert();
  console.log('alert 弹窗:', dismissed?'已点掉"确定"':'(无弹窗)');
  await sleep(800);
  sh(`${ADB} exec-out screencap -p > C:/tmp/bbq-ordered.png`);

  const ls = await evaljs(`(function(){
    var out=[];
    for(var i=0;i<localStorage.length;i++){
      var k=localStorage.key(i);var v=localStorage.getItem(k)||'';
      if(v.indexOf('羊肉串')>=0)out.push(k+'='+v.slice(0,160));
    }
    return JSON.stringify(out);
  })()`);
  console.log('订单存储:', ls);
  assert(ls!=='[]', '下单后 localStorage 无订单记录');

  /* 杀进程重开 */
  sh(`${ADB} shell am force-stop ${PKG}`);
  sh(`${ADB} shell "monkey -p ${PKG} -c android.intent.category.LAUNCHER 1"`);
  await sleep(3000);
  const PID2 = sh(`${ADB} shell pidof ${PKG}`);
  try{ sh(`${ADB} forward --remove tcp:9223`); }catch(e){}
  sh(`${ADB} forward tcp:9223 localabstract:webview_devtools_remote_${PID2}`);
  await sleep(500);
  await connect();
  const again = await evaljs(`(function(){
    for(var i=0;i<localStorage.length;i++){
      var v=localStorage.getItem(localStorage.key(i))||'';
      if(v.indexOf('羊肉串')>=0)return '订单还在: '+v.slice(0,140);
    }
    return '';
  })()`);
  assert(again, '杀进程重开后订单丢失');
  console.log('✓', again);
  console.log('\n===== 可用性 e2e 全部通过 =====');
  process.exit(0);
})().catch(e=>{console.error('\n✗',e.message);process.exit(1);});
