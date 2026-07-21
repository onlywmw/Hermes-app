/* ============================================================
   app.js — 入口: 事件绑定 + 初始化 + i18n
   ============================================================ */

/* 返回按钮 */
document.getElementById('btnBack').addEventListener('click',function(){
  genCounter++;curRoomId=null;
  try{if(window.HermesBridge)HermesBridge.setRoomOpen('');}catch(e){}
  setTab('chat');showView('view-rooms');renderRooms();ev('返回房间列表');
});

/* 发送按钮 + 回车 */
$('btnSend').addEventListener('click',sendMsg);
$('msgInput').addEventListener('keydown',function(e){if(e.key==='Enter')sendMsg();});

/* P1-8: 附件按钮 — 真实文件选择器 */
$('plusBtn').addEventListener('click',function(){
  trayOpen=!trayOpen;
  $('attTray').classList.toggle('open',trayOpen);
  $('plusBtn').classList.toggle('open',trayOpen);
  ev(trayOpen?'展开附件托盘':'收起附件托盘');
});
document.querySelectorAll('.tray-item').forEach(function(el){
  el.addEventListener('click',function(){
    closeTray();
    B.pickFile(function(info){
      if(info){pending.push(info);renderPend();ev(t('file.pick')+info.name);}
      else{ev(t('file.cancel'));}
    });
  });
});

/* ============ Sheet 互斥函数已移至 app-room.js (先加载) ============ */

/* ============ 新建房间 sheet — 绑定已在 app-room.js, 此处不重复 ============ */
$('sheetMask').addEventListener('click',closeSheet);
function closeSheet(){$('sheetMask').classList.remove('open');$('sheetNew').classList.remove('open');}

/* 第一步 → 第二步 */
$('btnStep1Next').addEventListener('click',function(){
  $('sheetStep1').style.display='none';$('sheetStep2').style.display='';
});
$('btnStep1Cancel').addEventListener('click',closeSheet);

/* 第二步 ← 第一步 */
$('btnStep2Back').addEventListener('click',function(){
  $('sheetStep2').style.display='none';$('sheetStep1').style.display='';
});
$('btnStep2Prev').addEventListener('click',function(){
  $('sheetStep2').style.display='none';$('sheetStep1').style.display='';
});

/* 协作方式选择 */
document.querySelectorAll('.mopt').forEach(function(el){el.addEventListener('click',function(){newMode=el.getAttribute('data-mode');document.querySelectorAll('.mopt').forEach(function(o){o.classList.toggle('sel',o===el);});renderModelPicker();});});

/* ============ 多模型: 动态模型勾选 (新建房间第二步) ============ */
var _pickedModels=[];
function renderModelPicker(){
  var wrap=$('modelPicker');
  if(!wrap)return;
  if(newMode!=='council'){wrap.style.display='none';return;}
  wrap.style.display='';
  var models=B.listModels();
  /* 默认模型必选 */
  var defaultModel=models.find(function(m){return m.isDefault;});
  _pickedModels=models.filter(function(m){return m.isDefault;}).map(function(m){return m.id;});
  var h='<div class="sh-label">'+t('model.selectTitle')+'</div>';
  models.forEach(function(m){
    var isDef=!!m.isDefault;
    var ready=(m.apiKey&&m.apiKey.length>0)||(m.provider==='ollama');
    h+='<div class="mpick'+(isDef?' sel':'')+'" data-mid="'+esc(m.id)+'">'
      +'<i class="av" style="background:'+esc(m.color||'#D97706')+'">'+esc((m.name||'?').charAt(0))+'</i>'
      +'<div class="mpick-info"><b>'+esc(m.name)+'</b><span>'+(isDef?t('model.default')+' · ':'')+(ready?'':t('model.noKey'))+'</span></div>'
      +'<span class="mcheck">'+(isDef?'✓':'')+'</span></div>';
  });
  h+='<div class="mpick-hint" id="mpickHint">'+t('model.selectHint')+'</div>';
  wrap.innerHTML=h;
  document.querySelectorAll('#modelPicker .mpick').forEach(function(el){
    el.addEventListener('click',function(){
      var mid=el.getAttribute('data-mid');
      var m=models.find(function(x){return x.id===mid;});
      if(m&&m.isDefault)return; /* 默认模型不可取消 */
      var idx=_pickedModels.indexOf(mid);
      if(idx>=0){_pickedModels.splice(idx,1);el.classList.remove('sel');el.querySelector('.mcheck').textContent='';}
      else{_pickedModels.push(mid);el.classList.add('sel');el.querySelector('.mcheck').textContent='✓';}
      updatePickHint();
    });
  });
  updatePickHint();
}
function updatePickHint(){
  var el=$('mpickHint');
  if(el)el.textContent=t('model.selected')+' '+_pickedModels.length+' · '+t('model.selectHint');
}

/* 创建房间 */
$('btnCreate').addEventListener('click',function(){
  var desc=$('newRoomDesc').value.trim();
  var name=$('newRoomName').value.trim();
  if(!name){
    name=desc?desc.slice(0,15):t('sheet.defaultName');
  }
  var id='r'+Date.now();
  /* 多模型: council 模式用勾选的模型 ID; single 模式只用默认 */
  var members;
  if(newMode==='council'){
    members={human:[{who:'you',role:'owner'}],ai:_pickedModels.slice()};
  }else{
    members=['mov'];
  }

  /* 动态 seed: 围绕描述生成第一句话 */
  var seed=[];
  if(desc){
    seed.push({t:'sys',h:t('sheet.createdMsg')+' '+name+'\n'+t('sheet.goalMsg')+' '+desc});
    if(newMode==='council'){
      seed.push({t:'agent',who:'mov',h:t('sheet.councilFirst')});
    }else{
      seed.push({t:'agent',who:'mov',h:t('sheet.descFirst')});
    }
  }else{
    if(newMode==='council'){
      seed.push({t:'sys',h:t('sheet.councilReady')});
      seed.push({t:'agent',who:'mov',h:t('sheet.councilAsk')});
    }else{
      seed.push({t:'agent',who:'mov',h:t('sheet.readyMsg')+' '+name+t('sheet.readySuffix')});
    }
  }

  ROOMS.splice(1,0,{id:id,name:name,mode:newMode,members:members,
    phase:newMode==='council'?'讨论中':'已交付',
    last:newMode==='council'?t('sheet.councilReady'):t('sheet.movReady'),
    time:'现在',unread:0,played:false,msgs:[],seed:seed});
  $('newRoomName').value='';$('newRoomDesc').value='';closeSheet();
  var memberObjs;
  if(newMode==='council'){
    memberObjs=[{who:'you',role:'owner'}].concat(_pickedModels.map(function(mid){
      var models=B.listModels();var m=models.find(function(x){return x.id===mid;});
      return {who:mid,role:m?m.role:'ai'};
    }));
  }else{
    memberObjs=[{who:'mov',role:'agent'}];
  }
  B.initRoom(id,name,desc,memberObjs);
  B.initRoomStorage(id);
  ev('新建房间 '+name+' ('+newMode+')');
  renderRooms();persistRooms();enterRoom(id);
});

/* ============ 顶栏入口 ============ */
$('btnSettings').addEventListener('click',function(){B.present?B.openSettings():B.toast(t('settings'));ev('打开 AI 设置');});

/* ============ 房间操作 sheet (替代 prompt) ============ */
var _opsRoomId=null,_opsConfirmAction=null;

function openRoomOpsSheet(roomId){
  var room=ROOMS.find(function(r){return r.id===roomId;});
  if(!room)return;
  openSheetExclusive('roomOpsMask','sheetRoomOps');
  _opsRoomId=roomId;
  $('roomOpsName').textContent=room.name;
  /* desk 房间只显示清空 */
  var isDesk=(roomId==='desk');
  $('opsRename').style.display=isDesk?'none':'';
  $('opsArchive').style.display=isDesk?'none':'';
  $('opsDelete').style.display=isDesk?'none':'';
  $('opsClear').style.display='';
  /* 重置为菜单态 */
  $('roomOpsMenu').style.display='';
  $('roomOpsConfirm').style.display='none';
  $('roomOpsRename').style.display='none';
  ev('打开房间操作 sheet');
}
function closeRoomOpsSheet(){
  $('roomOpsMask').classList.remove('open');
  $('sheetRoomOps').classList.remove('open');
  _opsRoomId=null;_opsConfirmAction=null;
}
function showOpsConfirm(text,action){
  $('roomOpsMenu').style.display='none';
  $('roomOpsRename').style.display='none';
  $('roomOpsConfirmText').textContent=text;
  $('roomOpsConfirm').style.display='';
  _opsConfirmAction=action;
}

/* 入口: ⋮ 按钮 */
$('btnRoomMore').addEventListener('click',function(){
  if(curRoomId)openRoomOpsSheet(curRoomId);
});
/* 入口: 房间列表长按 */
function bindRoomListLongPress(){
  document.querySelectorAll('#roomList .room').forEach(function(el){
    bindLongPress(el,{
      text:'',
      exec:function(){if(!_sheetOpen)openRoomOpsSheet(el.getAttribute('data-room'));}
    });
  });
}

/* sheet 事件 */
$('roomOpsMask').addEventListener('click',closeRoomOpsSheet);
$('btnRoomOpsClose').addEventListener('click',closeRoomOpsSheet);

$('opsRename').addEventListener('click',function(){
  var room=ROOMS.find(function(r){return r.id===_opsRoomId;});
  if(!room)return;
  $('roomOpsMenu').style.display='none';
  $('roomOpsConfirm').style.display='none';
  $('roomOpsRename').style.display='';
  $('opsRenameInput').value=room.name;
  $('opsRenameInput').focus();
});
$('opsRenameCancel').addEventListener('click',function(){
  $('roomOpsRename').style.display='none';
  $('roomOpsMenu').style.display='';
});
$('opsRenameOk').addEventListener('click',function(){
  var room=ROOMS.find(function(r){return r.id===_opsRoomId;});
  var newName=$('opsRenameInput').value.trim();
  if(room&&newName){room.name=newName;$('roomTitle').textContent=newName;renderRooms();persistRooms();ev(t('ops.rename')+' → '+newName);}
  closeRoomOpsSheet();
});

$('opsArchive').addEventListener('click',function(){
  var room=ROOMS.find(function(r){return r.id===_opsRoomId;});
  if(room){room.phase='已归档';setPhase(room.id,'已归档');B.toast(t('ops.archived'));ev(t('ops.archive')+' '+room.name);}
  closeRoomOpsSheet();
});

$('opsClear').addEventListener('click',function(){
  showOpsConfirm(t('ops.confirmClear'),function(){
    clearRoomHistory(_opsRoomId);
    closeRoomOpsSheet();
  });
});

$('opsDelete').addEventListener('click',function(){
  showOpsConfirm(t('ops.confirmDelete'),function(){
    var room=ROOMS.find(function(r){return r.id===_opsRoomId;});
    if(room){
      var idx=ROOMS.indexOf(room);
      if(idx>=0)ROOMS.splice(idx,1);
      genCounter++;curRoomId=null;
      try{if(window.HermesBridge)HermesBridge.setRoomOpen('');}catch(e){}
      setTab('chat');showView('view-rooms');renderRooms();persistRooms();
      B.toast(t('ops.deleted'));ev(t('ops.delete')+' '+room.name);
    }
    closeRoomOpsSheet();
  });
});

$('opsConfirmCancel').addEventListener('click',function(){
  $('roomOpsConfirm').style.display='none';
  $('roomOpsMenu').style.display='';
});
$('opsConfirmOk').addEventListener('click',function(){
  if(_opsConfirmAction)_opsConfirmAction();
});

/* 新建房间 sheet ✕ */
$('btnSheetClose').addEventListener('click',closeSheet);

/* ============ 房间内子 tab 切换 ============ */
var curSubtab='chat';
function setSubtab(tab){
  curSubtab=tab;
  document.querySelectorAll('.room-tab').forEach(function(el){
    el.classList.toggle('on',el.getAttribute('data-subtab')===tab);
  });
  $('chatPane').style.display=(tab==='chat')?'':'none';
  $('chatFoot').style.display=(tab==='chat')?'':'none';
  $('fileView').style.display=(tab==='files')?'':'none';
  $('fileFabAdd').style.display=(tab==='files')?'':'none';
  if(tab==='files'&&curRoomId){
    _filesPath='';
    renderStorageView();
  }
}
document.querySelectorAll('.room-tab').forEach(function(el){
  el.addEventListener('click',function(){setSubtab(el.getAttribute('data-subtab'));});
});
$('fileFabAdd').addEventListener('click',function(){
  if(!curRoomId)return;
  fileFabAction(curRoomId);
});
bindLongPress($('fileFabAdd'),{
  text:t('files.new'),
  exec:function(){openFileNewSheet();}
});

/* Fix 2: 文件预览关闭 */
$('previewClose').addEventListener('click',closeFilePreview);
$('previewMask').addEventListener('click',closeFilePreview);

/* 存储系统: 版本历史 overlay 关闭 */
$('versionClose').addEventListener('click',closeVersionOverlay);
$('versionMask').addEventListener('click',closeVersionOverlay);

/* 存储系统: 模板 sheet */
$('btnTemplateClose').addEventListener('click',closeTemplateSheet);
$('templateMask').addEventListener('click',closeTemplateSheet);
$('btnTemplateOk').addEventListener('click',confirmTemplate);

/* 存储系统: 存储类型子 tab 切换 */
document.querySelectorAll('.storage-tab').forEach(function(el){
  el.addEventListener('click',function(){setStorageType(el.getAttribute('data-stype'));});
});

/* Fix 5: 新建文件 sheet */
function openFileNewSheet(){
  openSheetExclusive('fileNewMask','fileNewSheet');
  $('fileNewName').value='';
  $('fileNewContent').value='';
  $('fileNewName').focus();
}
function closeFileNewSheet(){
  $('fileNewMask').classList.remove('open');
  $('fileNewSheet').classList.remove('open');
}
$('btnFileNewClose').addEventListener('click',closeFileNewSheet);
$('fileNewMask').addEventListener('click',closeFileNewSheet);
$('btnFileNewCreate').addEventListener('click',function(){
  var name=$('fileNewName').value.trim();
  if(!name){B.toast(t('files.needName'));return;}
  var content=$('fileNewContent').value;
  var res=B.saveWorkFile(curRoomId,name,content,'you');
  if(res.ok){
    closeFileNewSheet();
    B.toast(name+' '+t('files.created'));
    renderStorageView();
  }else{
    B.toast(res.message||'');
  }
});

$('btnHelp').addEventListener('click',function(){
  var desk=ROOMS.find(function(r){return r.id==='desk';});
  if(desk){enterRoom('desk');setTimeout(function(){$('msgInput').value='帮助';sendMsg();},300);}
});

/* 运行页刷新按钮 */
$('btnRunRefresh').addEventListener('click',refreshRuntime);

/* Cron 创建 */
$('btnCronCreate').addEventListener('click',function(){
  var text=$('cronInput').value.trim();
  if(!text){B.toast(t('rt.cronInput'));return;}
  var cron='0 8 * * *';
  var m=text.match(/(\d{1,2}):(\d{2})/);
  if(m){cron=m[2]+' '+m[1]+' * * *';}
  var m2=text.match(/每(\d+)小时/);
  if(m2){cron='0 */'+m2[1]+' * * *';}
  var m3=text.match(/每(\d+)分钟/);
  if(m3){cron='*/'+m3[1]+' * * * *';}
  var name=text.length>20?text.slice(0,20)+'…':text;
  var res=B.createCron(name,cron,text);
  if(res.ok){$('cronInput').value='';renderCronJobs();B.toast(t('cron.created'));}
  else{B.toast(t('cron.createFail')+(res.error||''));}
  ev('创建 Cron: '+text);
});

/* ============ 看板事件绑定 ============ */
$('boardTrigger').addEventListener('click',openBoardPanel);
$('boardPanelMask').addEventListener('click',closeBoardPanel);
$('boardPanelClose').addEventListener('click',closeBoardPanel);
$('btnBoardAddClose').addEventListener('click',closeBoardAddSheet);
$('boardAddMask').addEventListener('click',closeBoardAddSheet);
$('btnBoardAddOk').addEventListener('click',confirmBoardAdd);

/* ============ 运行页: 三行入口 → 详情弹层 ============ */
$('rowChannels').addEventListener('click',function(){openRunDetail('channels');});
$('rowPerms').addEventListener('click',function(){openRunDetail('perms');});
$('rowSkills').addEventListener('click',function(){openRunDetail('skills');});
$('runDetailClose').addEventListener('click',closeRunDetail);
$('runDetailMask').addEventListener('click',closeRunDetail);

/* ============ 初始化 ============ */
initLang();
applyI18n();
refreshModelAvatars(); /* 多模型: 注册表颜色合并进 AV 表 */
renderRooms();
setTab('chat');
setTimeout(function(){refreshRuntime();},600);
ev('MOV v3.0 '+t('ready')+(B.present?' · '+t('bridge.on'):' · '+t('bridge.off')));
