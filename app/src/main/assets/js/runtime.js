/* ============================================================
   runtime.js — 运行 tab: 全部真数据 + i18n
   v3: 技能已移至 skills.js, Cron 从自动 tab 移入
   ============================================================ */

/* ---------- 主刷新 ---------- */
function refreshRuntime(){
  refreshHealth();
  refreshProcess();
  refreshChannels();
  refreshModel();
  renderPermissions();
  renderCronJobs();
  renderSkillPage();
  ev('运行页数据已刷新');
}

/* ---------- 健康总结条 ---------- */
function refreshHealth(){
  var info=B.aiInfo();
  var aiOn=info.enabled&&info.configured;
  var perms=B.permState();
  var chOn=(B.present?1:0)+(aiOn?1:0)+(perms.NOTIFY?1:0);
  var jobs=B.listCron?B.listCron():[];
  var failedJobs=jobs.filter(function(j){return j.lastStatus&&j.lastStatus.indexOf('FAIL')===0;}).length;
  var problems=(aiOn?0:1)+failedJobs;
  var card=$('healthCard');
  if(problems===0){
    card.className='health ok';
    $('healthTitle').textContent=t('rt.healthOk');
  }else{
    card.className='health bad';
    $('healthTitle').textContent=problems+' '+t('rt.healthBad');
  }
  var sub=t('rt.healthSub')
    .replace('{a}',(aiOn?'1/1':'0/1'))
    .replace('{b}',jobs.length+'')
    .replace('{c}',chOn+'/3');
  if(failedJobs>0)sub+=' · '+failedJobs+' FAIL';
  $('healthSub').textContent=sub;
}

/* ---------- PROCESS ---------- */
function refreshProcess(){
  var s=B.runtimeStats();
  if(s.pid!=null)$('rtPid').textContent=s.pid;
  if(s.uptimeMs!=null)$('rtUptime').textContent=formatUptime(s.uptimeMs);
  if(s.memUsedMb!=null&&s.memMaxMb!=null){
    $('rtMem').innerHTML=s.memUsedMb+'<i> / '+s.memMaxMb+' MB</i>';
    var pct=Math.min(100,Math.round(s.memUsedMb/s.memMaxMb*100));
    $('rtMemBar').style.width=pct+'%';
  }
  if(s.cmdCount!=null){
    $('rtCmds').innerHTML=s.cmdCount+'<i> '+t('rt.times')+'</i>';
    var cp=s.cmdCount>0?Math.min(100,Math.round(Math.log10(s.cmdCount+1)/2*100)):0;
    $('rtCmdBar').style.width=cp+'%';
  }
  if(s.lastCmdMs!=null)$('rtLastCmd').textContent=s.lastCmdMs;
  if(s.lastCmdName!=null)$('rtLastCmdName').textContent=s.lastCmdName||'--';
  $('runSub').textContent=t('rt.sub')+' · pid '+(s.pid||'--');
}

function formatUptime(ms){
  var s=Math.floor(ms/1000);
  if(s<60)return s+'s';
  var m=Math.floor(s/60);s=s%60;
  if(m<60)return m+'m '+s+'s';
  var h=Math.floor(m/60);m=m%60;
  return h+'h '+m+'m';
}

/* ---------- CHANNELS ---------- */
function refreshChannels(){
  $('chShell').className='st '+(B.present?'on':'off');
  var w=B.widgetInfo();
  $('chWidgetLabel').textContent=t('rt.widget')+' ×'+w.count;
  $('chWidget').className='st '+(w.count>0?'on':'off');
  var info=B.aiInfo();
  $('chanAiDot').className='st '+(info.enabled&&info.configured?'on':'off');
  var perms=B.permState();
  $('chNotify').className='st '+(perms.NOTIFY?'on':'off');
}

/* ---------- MODEL ---------- */
function refreshModel(){
  var info=B.aiInfo();
  var rows=[
    {key:'native',pv:t('rt.nativeEngine'),md:t('rt.nativeDesc'),on:true,
     action:function(){B.toast(t('rt.nativeEngine')+': torch/battery/brightness/volume/wifi/vibrate/tts/clipboard/notify/location/sms/contacts/call/screen/app/input/network/process/file');}},
    {key:'ai',pv:info.displayName||t('rt.aiGateway'),
     md:(info.enabled&&info.configured)?(info.model+' · '+t('rt.model')):(info.enabled?t('ai.off'):t('ai.nokey')),
     on:info.enabled&&info.configured,
     action:function(){B.openSettings();}}
  ];
  var mh='';
  rows.forEach(function(r){
    if(r.key==='native'){
      /* 原生引擎: 永远在线, 不是可选项 → 在线徽章, 无 radio */
      mh+='<div class="model-row" data-model="'+r.key+'"><div><div class="pv">'+esc(r.pv)+'</div><div class="md">'+esc(r.md)+'</div></div><span class="badge ok"><span class="dot"></span>'+t('rt.online')+'</span></div>';
    }else{
      /* AI 模型: radio 单选 */
      mh+='<div class="model-row'+(r.on?' sel':'')+'" data-model="'+r.key+'"><div><div class="pv">'+esc(r.pv)+'</div><div class="md">'+esc(r.md)+'</div></div><span class="radio"></span></div>';
    }
  });
  $('modelList').innerHTML=mh;
  document.querySelectorAll('#modelList .model-row').forEach(function(el){
    el.addEventListener('click',function(){
      var key=el.getAttribute('data-model');
      var row=rows.find(function(r){return r.key===key;});
      if(row&&row.action)row.action();
    });
  });
}

/* ---------- PERMISSIONS 单行横滚 ---------- */
var PERM_KEYS=['CAMERA','LOCATION','CONTACTS','SMS','CALL','NOTIFY','SETTINGS'];
function renderPermissions(){
  var perms=B.permState();
  var h='';
  PERM_KEYS.forEach(function(key){
    var granted=perms[key];
    h+='<div class="perm'+(granted?'':' denied')+'" data-perm="'+key+'" title="'+key+': '+(granted?'✓':'✗')+'">'+t('perm.'+key)+'</div>';
  });
  $('permGrid').innerHTML=h;
  document.querySelectorAll('#permGrid .perm').forEach(function(el){
    el.addEventListener('click',function(){B.openAppSettings();});
  });
}

/* ============ CRON (v3: 移入运行页) ============ */
function renderCronJobs(){
  var jobs=B.listCron();
  $('cronSub').textContent=jobs.length+' '+t('rt.task')+' · '+t('rt.unattended');
  var h='';
  jobs.forEach(function(j){
    var lastStr=j.lastStatus?(t('skill.lastUsed')+' '+esc(j.lastStatus)):'--';
    h+='<div class="job" data-id="'+esc(j.id)+'">'
      +'<div class="r1"><b>'+esc(j.name)+'</b><span class="switch'+(j.enabled?'':' off')+'" data-toggle="'+esc(j.id)+'"></span></div>'
      +'<div class="r2"><span class="cron">'+esc(j.cron)+'</span><span>'+esc(j.command)+'</span></div>'
      +'<div class="r3"><span>'+lastStr+'</span><span class="del-cron" data-del="'+esc(j.id)+'" style="cursor:pointer;color:var(--err)">'+t('rt.delete')+'</span></div>'
      +'</div>';
  });
  if(jobs.length===0)h='<div style="margin:0 12px 8px;padding:16px;text-align:center;font-family:var(--font-mono);font-size:10px;color:var(--ink-4)">'+t('rt.cronNone')+'</div>';
  $('cronJobList').innerHTML=h;
  document.querySelectorAll('[data-toggle]').forEach(function(el){
    el.addEventListener('click',function(){
      var id=el.getAttribute('data-toggle');
      var job=jobs.find(function(j){return j.id===id;});
      if(job){B.toggleCron(id,!job.enabled);setTimeout(renderCronJobs,200);}
    });
  });
  document.querySelectorAll('[data-del]').forEach(function(el){
    el.addEventListener('click',function(){
      var id=el.getAttribute('data-del');
      var job=jobs.find(function(j){return j.id===id;});
      if(confirm(t('cron.confirmDel'))){B.deleteCron(id);B.toast(t('cron.deleted')+(job?' · '+job.name:''));setTimeout(renderCronJobs,200);}
    });
  });
}
