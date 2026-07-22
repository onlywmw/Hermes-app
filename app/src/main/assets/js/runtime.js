/* ============================================================
   runtime.js — 运行 tab: 全部真数据 + i18n
   v3: 技能已移至 skills.js, Cron 从自动 tab 移入
   ============================================================ */

/* ---------- 主刷新 ---------- */
function refreshRuntime(){
  refreshProcess();
  refreshChannels();
  refreshModel();
  renderPermissions();
  renderCronJobs();
  renderSkillPage();
  ev('运行页数据已刷新');
}

/* ---------- PROCESS (DESIGN_OPTIMIZE §1: 精简 + 个人信息) ---------- */
function refreshProcess(){
  var s=B.runtimeStats();
  /* 状态条: MOV 运行正常 · 已运行 Xh Xm */
  if(s.uptimeMs!=null){
    $('ssUptime').textContent=formatUptime(s.uptimeMs);
    $('ssText').textContent='MOV 运行正常';
    $('ssDot').style.background='var(--ok-dot)';
  }
  /* 折叠区: pid / 内存 / 指令计数 (开发者信息) */
  if(s.pid!=null)$('rtPid').textContent=s.pid;
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
  $('runSub').textContent=t('rt.sub');
  /* 渲染个人信息 */
  renderPersonalRow();
}

/* 个人信息: 默认显示"本地用户", 颜色用金色 */
function renderPersonalRow(){
  var avatar=$('prAvatar');
  if(avatar){
    avatar.style.background='var(--acc-live)';
    avatar.textContent='●';
  }
  var name=$('prName');
  if(name)name.textContent='本地用户';
}

function formatUptime(ms){
  var s=Math.floor(ms/1000);
  if(s<60)return s+'s';
  var m=Math.floor(s/60);s=s%60;
  if(m<60)return m+'m '+s+'s';
  var h=Math.floor(m/60);m=m%60;
  return h+'h '+m+'m';
}

/* ---------- CHANNELS (摘要 + 详情) ---------- */
function getChannelState(){
  var info=B.aiInfo();
  var w=B.widgetInfo();
  var perms=B.permState();
  return [
    {name:t('rt.shell'), on:B.present},
    {name:t('rt.widget')+' ×'+w.count, on:w.count>0},
    {name:t('rt.aiGateway'), on:info.enabled&&info.configured},
    {name:t('rt.notify'), on:perms.NOTIFY}
  ];
}
function refreshChannels(){
  var chs=getChannelState();
  var on=chs.filter(function(c){return c.on;}).length;
  var el=$('chanSummary');
  el.textContent=on+'/'+chs.length+' '+t('rt.online');
  el.style.color=on===chs.length?'var(--ok)':(on===0?'var(--err)':'var(--warn)');
}

/* ---------- PERMISSIONS (摘要 + 详情) ---------- */
var PERM_KEYS=['CAMERA','LOCATION','CONTACTS','SMS','CALL','NOTIFY','SETTINGS'];
function getPermState(){
  var perms=B.permState();
  return PERM_KEYS.map(function(key){return {key:key,name:t('perm.'+key),granted:perms[key]};});
}
function renderPermissions(){
  var ps=getPermState();
  var granted=ps.filter(function(p){return p.granted;}).length;
  var el=$('permSummary');
  el.textContent=granted+'/'+ps.length+' '+t('rt.granted');
  el.style.color=granted===ps.length?'var(--ok)':(granted===0?'var(--err)':'var(--warn)');
}

/* ---------- 详情弹层 (通道/权限/技能共用) ---------- */
function openRunDetail(type){
  var title='',body='';
  if(type==='channels'){
    title=t('rt.channels');
    var chs=getChannelState();
    body='<div class="rd-list">'+chs.map(function(c){
      return '<div class="rd-row"><span class="rd-dot '+(c.on?'on':'off')+'"></span>'
        +'<span class="rd-name">'+esc(c.name)+'</span>'
        +'<span class="rd-st '+(c.on?'ok':'bad')+'">'+(c.on?t('rt.online'):t('rt.offline'))+'</span></div>';
    }).join('')+'</div>';
  }else if(type==='perms'){
    title=t('rt.perms2');
    var ps=getPermState();
    body='<div class="rd-list">'+ps.map(function(p){
      return '<div class="rd-row rd-perm" data-perm="'+p.key+'"><span class="rd-dot '+(p.granted?'on':'off')+'"></span>'
        +'<span class="rd-name">'+esc(p.name)+'</span>'
        +'<span class="rd-st '+(p.granted?'ok':'bad')+'">'+(p.granted?'✓':'✗')+'</span></div>';
    }).join('')+'</div><div class="rd-hint">'+t('rt.permHint')+'</div>';
  }else if(type==='skills'){
    title=t('skill.title');
    body=skillCardsHtml('');
  }
  $('runDetailTitle').textContent=title;
  $('runDetailBody').innerHTML=body;
  $('runDetailMask').style.display='';
  $('runDetailOverlay').style.display='';
  if(type==='perms'){
    document.querySelectorAll('#runDetailBody .rd-perm').forEach(function(el){
      el.addEventListener('click',function(){B.openAppSettings();});
    });
  }
  if(type==='skills'){bindSkillCards($('runDetailBody'));}
}
function closeRunDetail(){
  $('runDetailMask').style.display='none';
  $('runDetailOverlay').style.display='none';
}

/* ---------- MODEL (多模型注册表) ---------- */
/* 状态点: on=已配置可用 / off=未配置 / disabled=已停用 */
function modelStatusDot(m){
  if(!m.enabled)return '<span class="st off"></span>';
  var ready=(m.apiKey&&m.apiKey.length>0)||(m.provider==='ollama');
  return '<span class="st '+(ready?'on':'off')+'"></span>';
}
function modelStatusText(m){
  if(!m.enabled)return t('model.disabled');
  var ready=(m.apiKey&&m.apiKey.length>0)||(m.provider==='ollama');
  return ready?(m.model||providerDisplayName(m.provider)):t('model.noKey');
}
function refreshModel(){
  refreshModelAvatars(); /* 设置页改动后同步聊天头像色 */
  var models=B.listModels();
  var mh='';
  /* 原生引擎: 永远在线, 置顶 */
  mh+='<div class="model-row" data-model="__native"><div><div class="pv">'+esc(t('rt.nativeEngine'))+'</div><div class="md">'+esc(t('rt.nativeDesc'))+'</div></div><span class="badge ok"><span class="dot"></span>'+t('rt.online')+'</span></div>';
  /* 已注册模型 */
  models.forEach(function(m){
    var ready=(m.apiKey&&m.apiKey.length>0)||(m.provider==='ollama');
    var sel=m.isDefault?' sel':'';
    mh+='<div class="model-row'+sel+'" data-model="'+esc(m.id)+'">'
      +'<i class="av" style="background:'+esc(m.color||providerColor(m.provider))+'">'+esc((m.name||'?').charAt(0))+'</i>'
      +'<div><div class="pv">'+esc(m.role||t('model.roleGeneral'))+(m.isDefault?' · '+t('model.default'):'')+'</div>'
      +'<div class="md">'+esc(m.name)+'</div>'
      +'<div class="ms">'+modelStatusDot(m)+'<span>'+esc(modelStatusText(m))+'</span></div></div>'
      +(m.isDefault?'<span class="badge ok"><span class="dot"></span>'+t('model.default')+'</span>':'<span class="radio"></span>')
      +'</div>';
  });
  if(models.length===0){
    mh+='<div class="model-none">'+esc(t('model.none'))+'</div>';
  }
  /* 添加模型入口 */
  mh+='<div class="model-row model-add" data-model="__add"><div><div class="md">'+t('model.add')+'</div></div></div>';
  $('modelList').innerHTML=mh;
  document.querySelectorAll('#modelList .model-row').forEach(function(el){
    el.addEventListener('click',function(){
      var key=el.getAttribute('data-model');
      if(key==='__native'){
        B.toast(t('rt.nativeEngine')+': torch/battery/brightness/volume/wifi/vibrate/tts/clipboard/notify/location/sms/contacts/call/screen/app/input/network/process/file');
      }else if(key==='__add'){
        B.openSettings();
      }else{
        /* 点击非默认模型 → 设为默认 */
        var m=models.find(function(x){return x.id===key;});
        if(m&&!m.isDefault){
          var res=B.setDefaultModel(key);
          if(res.ok){B.toast(t('model.setDefault')+' '+m.name);refreshModel();}
          else{B.toast(res.error||t('model.setFail'));}
        }else if(m){
          B.openSettings();
        }
      }
    });
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
