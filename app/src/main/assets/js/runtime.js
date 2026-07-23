/* ============================================================
   runtime.js — 运行 tab: 全部真数据 + i18n
   v3: 技能已移至 skills.js, Cron 从自动 tab 移入
   ============================================================ */

/* ---------- 主刷新 ---------- */
/* 步骤隔离: 任一区块抛错只降级该区块并上报 logcat, 不再拖垮整页 */
function refreshRuntime(){
  var steps=[['process',refreshProcess],['channels',refreshChannels],['model',refreshModel],
             ['perms',renderPermissions],['cron',renderCronJobs],['skills',renderSkillPage],
             ['tokens',renderTokenStats]];
  for(var i=0;i<steps.length;i++){
    try{steps[i][1]();}
    catch(e){ev('运行页区块刷新失败 ['+steps[i][0]+']: '+(e&&e.message?e.message:e));}
  }
  ev('运行页数据已刷新');
}

/* ---------- Token 仪表盘 (V5) ---------- */
function renderTokenStats(){
  var s=B.tokenStats();
  if($('tkToday'))$('tkToday').textContent=fmtTok(s.today);
  if($('tkMonth'))$('tkMonth').textContent=fmtTok(s.month);
  var pct=s.quota>0?Math.min(100,Math.round(s.month/s.quota*100)):0;
  if($('tkBar'))$('tkBar').style.width=pct+'%';
  if($('tkQuotaUsed'))$('tkQuotaUsed').textContent=fmtTok(s.month);
  if($('tkQuotaPct'))$('tkQuotaPct').textContent=pct;
  var models=B.listModels();
  if($('prSub'))$('prSub').textContent='本地运行 · '+models.length+' 个模型';
}
function fmtTok(n){
  n=n||0;
  if(n>=1e6)return (n/1e6).toFixed(2)+'M';
  if(n>=1e3)return (n/1e3).toFixed(1)+'K';
  return String(Math.round(n));
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
/* 跨文件函数兜底: store.js 版本错位(旧缓存)时降级不炸页 */
function _pvColor(p){return (typeof providerColor==='function')?providerColor(p):'#D97706';}
function _pvName(p){return (typeof providerDisplayName==='function')?providerDisplayName(p):p;}
function modelStatusText(m){
  if(!m.enabled)return t('model.disabled');
  var ready=(m.apiKey&&m.apiKey.length>0)||(m.provider==='ollama');
  return ready?(m.model||_pvName(m.provider)):t('model.noKey');
}
function refreshModel(){
  if(typeof refreshModelAvatars==='function')refreshModelAvatars(); /* 设置页改动后同步聊天头像色 */
  var models=B.listModels();
  var mh='';
  /* 原生引擎: 永远在线, 置顶 */
  mh+='<div class="model-row" data-model="__native"><div><div class="pv">'+esc(t('rt.nativeEngine'))+'</div><div class="md">'+esc(t('rt.nativeDesc'))+'</div></div><span class="badge ok"><span class="dot"></span>'+t('rt.online')+'</span></div>';
  /* 已注册模型 (无 radio/选中框: 身份由 AGENT 标签与大脑徽章表达) */
  models.forEach(function(m){
    var ready=(m.apiKey&&m.apiKey.length>0)||(m.provider==='ollama');
    mh+='<div class="model-row" data-model="'+esc(m.id)+'">'
      +'<i class="av" style="background:'+esc(m.color||_pvColor(m.provider))+'">'+esc((m.name||'?').charAt(0))+'</i>'
      +'<div><div class="pv">'+(m.isDefault?esc(t('model.brainTag')):esc(m.role||t('model.roleGeneral')))
      +'</div>'
      +'<div class="md">'+esc(m.name)+'</div>'
      +'<div class="ms">'+modelStatusDot(m)+'<span>'+esc(modelStatusText(m))+'</span></div></div>'
      +(m.isDefault?'<span class="badge ok"><span class="dot"></span>'+t('model.brain')+'</span>':'')
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
      if(lpSuppressClick())return; /* 长按后 300ms 内抑制 click, 防双触发 */
      var key=el.getAttribute('data-model');
      if(key==='__native'){
        B.toast(t('rt.nativeEngine')+': torch/battery/brightness/volume/wifi/vibrate/tts/clipboard/notify/location/sms/contacts/call/screen/app/input/network/process/file');
      }else if(key==='__add'){
        openModelSheet(null); /* TC-M09: 运行页快捷添加, 不再跳原生页 */
      }else{
        /* 点击模型行 → 管理 sheet (不再一点就换默认; 大脑切换必须是刻意动作) */
        var m=models.find(function(x){return x.id===key;});
        if(m)openModelOps(m);
      }
    });
    /* TC-M10: 模型行长按 → 管理菜单 (空 text = 直达 sheet, 不弹确认条) */
    var lpKey=el.getAttribute('data-model');
    if(lpKey!=='__native'&&lpKey!=='__add'&&typeof bindLongPress==='function'){
      (function(k){
        bindLongPress(el,{text:'',exec:function(){
          var cur=B.listModels().find(function(x){return x.id===k;});
          if(cur)openModelOps(cur);
        }});
      })(lpKey);
    }
  });
}

/* ---------- MODEL SHEET (TC-M09: 运行页快捷添加/编辑) ---------- */
var _msEditId=null,_msProvider='deepseek',_msPresets=[],_msOld=null;

function _msPreset(k){
  for(var i=0;i<_msPresets.length;i++)if(_msPresets[i].key===k)return _msPresets[i];
  return null;
}
/* 打开添加/编辑 sheet; editId=null 为添加 */
function openModelSheet(editId){
  _msPresets=B.providerPresets();
  if(!_msPresets||!_msPresets.length){B.openSettings();return;} /* 旧版原生无预设桥 → 回退原生页 */
  _msEditId=editId||null;
  _msOld=null;
  if(editId){
    _msOld=B.listModels().find(function(x){return x.id===editId;})||null;
    if(!_msOld)return;
  }
  _msProvider=_msOld?_msOld.provider:'deepseek';
  $('modelSheetTitle').textContent=t(_msEditId?'model.editTitle':'model.addTitle');
  renderProviderList();
  $('modelKey').value='';
  var hint=$('modelKeyHint');
  if(_msOld&&_msOld.apiKey){ /* listModels 脱敏值仅用于展示, 不回传 */
    hint.style.display='';
    hint.textContent=t('model.keepKeyHint').replace('{key}',_msOld.apiKey);
  }else{
    hint.style.display='none';
  }
  if(_msOld){
    $('modelBaseUrl').value=_msOld.baseUrl||'';
    $('modelName').value=_msOld.model||'';
    $('modelDisplay').value=_msOld.name||'';
    $('modelRole').value=_msOld.role||'通用';
    setModelAdvOpen(true);
  }else{
    applyPresetDefaults(true);
    $('modelRole').value='通用';
    setModelAdvOpen(false);
  }
  updateModelKeyUI();
  updateModelSaveBtn();
  openSheetExclusive('modelMask','modelSheet');
}
function renderProviderList(){
  var h='';
  _msPresets.forEach(function(p){
    h+='<div class="mpick'+(p.key===_msProvider?' sel':'')+'" data-pv="'+esc(p.key)+'">'
      +'<i class="av" style="background:'+esc(_pvColor(p.key))+'">'+esc((p.displayName||'?').charAt(0))+'</i>'
      +'<div class="mpick-info"><b>'+esc(p.displayName)+'</b><span>'+esc(p.note||p.defaultModel||'')+'</span></div>'
      +'<span class="mcheck">'+(p.key===_msProvider?'✓':'')+'</span></div>';
  });
  $('providerList').innerHTML=h;
  document.querySelectorAll('#providerList .mpick').forEach(function(el){
    el.addEventListener('click',function(){
      _msProvider=el.getAttribute('data-pv');
      renderProviderList();
      applyPresetDefaults(!_msOld); /* 添加模式连显示名一起填; 编辑模式保留显示名 */
      updateModelKeyUI();
      updateModelSaveBtn();
    });
  });
}
/* 按预设回填 baseUrl/模型名; withName=true 时显示名也填 */
function applyPresetDefaults(withName){
  var p=_msPreset(_msProvider);
  $('modelBaseUrl').value=p?p.baseUrl:'';
  $('modelName').value=p?p.defaultModel:'';
  if(withName)$('modelDisplay').value=p?p.displayName:'';
}
function setModelAdvOpen(open){
  $('modelAdv').style.display=open?'':'none';
  $('btnModelAdv').textContent=t('model.advanced')+(open?' ▴':' ▾');
}
/* ollama 隐藏 Key 框; 其余厂商显示 Key 框, 有控制台地址才显示"获取 Key" */
function updateModelKeyUI(){
  var p=_msPreset(_msProvider);
  var isLocal=_msProvider==='ollama';
  $('modelKeyBlock').style.display=isLocal?'none':'';
  $('modelNoKey').style.display=isLocal?'':'none';
  $('btnGetKey').style.display=(p&&p.keyConsoleUrl)?'':'none';
}
/* 非 ollama 且为新增 且 Key 空 → 保存置灰; 编辑模式留空=保持原 Key 可保存 */
function updateModelSaveBtn(){
  var needKey=_msProvider!=='ollama'&&!_msEditId;
  $('btnModelSave').disabled=needKey&&!$('modelKey').value.trim();
}
/* 收集表单为 ModelConfig 同形 JSON */
function collectModelPayload(){
  var p=_msPreset(_msProvider);
  var o={
    provider:_msProvider,
    apiKey:$('modelKey').value.trim(),
    baseUrl:$('modelBaseUrl').value.trim(),
    model:$('modelName').value.trim(),
    name:$('modelDisplay').value.trim()||(p?p.displayName:_msProvider),
    role:$('modelRole').value,
    color:_pvColor(_msProvider),
    enabled:true,
    isDefault:false
  };
  if(_msOld){ /* 编辑: 保留 id 与不可见字段; apiKey 留空 → 后端保留原 Key */
    o.id=_msOld.id;
    o.enabled=_msOld.enabled!==false;
    o.isDefault=!!_msOld.isDefault;
    o.systemPrompt=_msOld.systemPrompt||'';
  }
  return o;
}

/* ---------- MODEL OPS (TC-M10: 模型管理 sheet · 点击/长按统一入口) ---------- */
var _opsModel=null;
function openModelOps(m){
  _opsModel=m;
  $('modelOpsName').textContent=m.name+' · '+_pvName(m.provider)+' · '+(m.model||'');
  /* 角色身份: 大脑(agent 驱动) / 评审候选 */
  $('modelOpsRole').textContent=m.isDefault?t('model.brainDesc'):t('model.reviewerDesc');
  $('modelOpsRole').style.color=m.isDefault?'var(--seal-deep)':'var(--ink-3)';
  $('modelOpsMenu').style.display='';
  $('modelOpsConfirm').style.display='none';
  $('mopsDefault').style.display=m.isDefault?'none':'';
  $('mopsTest').textContent=t('model.testConn');
  openSheetExclusive('modelOpsMask','modelOpsSheet');
}

/* ---------- TC-M09/M10 事件绑定 ---------- */
/* closeAllSheets 定义在 app-room.js (后加载) — 必须包函数延迟求值, 不能直接引用 */
$('modelMask').addEventListener('click',function(){closeAllSheets();});
$('modelOpsMask').addEventListener('click',function(){closeAllSheets();});
$('btnModelClose').addEventListener('click',function(){closeAllSheets();});
$('btnModelOpsClose').addEventListener('click',function(){closeAllSheets();});
$('btnModelAdv').addEventListener('click',function(){setModelAdvOpen($('modelAdv').style.display==='none');});
$('btnGetKey').addEventListener('click',function(){
  var p=_msPreset(_msProvider);
  if(p&&p.keyConsoleUrl)B.openUrl(p.keyConsoleUrl);
});
$('modelKey').addEventListener('input',updateModelSaveBtn);
$('btnModelTest').addEventListener('click',function(){
  var o=collectModelPayload();
  if(_msProvider!=='ollama'&&!o.apiKey){B.toast(t('model.testNeedKey'));return;}
  B.toast(t('model.testing'));
  var btn=$('btnModelTest');
  btn.disabled=true;
  B.testModel(o,function(res){
    btn.disabled=false;
    if(res.ok){B.toast(t('model.testOk')+(res.latencyMs?' · '+res.latencyMs+'ms':''));}
    else{B.toast(t('model.testFail')+(res.error?': '+res.error:''));}
  });
});
$('btnModelSave').addEventListener('click',function(){
  if(this.disabled)return;
  var o=collectModelPayload();
  var res=_msEditId?B.updateModel(o):B.addModel(o);
  if(res.ok){
    B.toast(t('model.saved'));
    closeAllSheets();
    refreshModel();
  }else{
    B.toast(res.error||t('model.setFail'));
  }
});
$('mopsDefault').addEventListener('click',function(){
  if(!_opsModel)return;
  var res=B.setDefaultModel(_opsModel.id);
  if(res.ok){B.toast(t('model.brainSet')+' '+_opsModel.name);}
  else{B.toast(res.error||t('model.setFail'));}
  closeAllSheets();refreshModel();
});
/* 测连接: 用存储的真实 key 走 aiChatWithModel, 行内回显延迟 */
$('mopsTest').addEventListener('click',function(){
  if(!_opsModel)return;
  var row=this;
  row.textContent=t('model.testing');
  var t0=Date.now();
  B.aiChatWithModel('只回复两个字: 收到',_opsModel.id,function(res){
    var ms=((Date.now()-t0)/1000).toFixed(1);
    row.textContent=(res&&res.ok?(t('model.connOk')+' · '+ms+'s'):(t('model.connFail')+' · '+String(res&&res.content||'').slice(0,40)));
    setTimeout(function(){row.textContent=t('model.testConn');},4000);
  });
});
$('mopsEdit').addEventListener('click',function(){
  if(!_opsModel)return;
  openModelSheet(_opsModel.id); /* openSheetExclusive 内部会先关 ops sheet */
});
$('mopsDelete').addEventListener('click',function(){
  if(!_opsModel)return;
  $('modelOpsMenu').style.display='none';
  $('modelOpsConfirm').style.display='';
  $('modelOpsConfirmText').textContent=t('model.deleteConfirm').replace('{name}',_opsModel.name);
});
$('mopsConfirmCancel').addEventListener('click',function(){
  $('modelOpsConfirm').style.display='none';
  $('modelOpsMenu').style.display='';
});
$('mopsConfirmOk').addEventListener('click',function(){
  if(!_opsModel)return;
  var res=B.deleteModel(_opsModel.id);
  if(res.ok){B.toast(t('model.deleted'));}
  else{B.toast(res.error||t('model.cannotDeleteLast'));}
  closeAllSheets();refreshModel();
});

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
