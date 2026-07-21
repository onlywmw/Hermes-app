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

/* ============ 新建房间 sheet ============ */
var newMode='council';
$('fabNew').addEventListener('click',function(){$('sheetMask').classList.add('open');$('sheetNew').classList.add('open');$('newRoomName').focus();ev('打开新建房间 sheet');});
$('sheetMask').addEventListener('click',closeSheet);
function closeSheet(){$('sheetMask').classList.remove('open');$('sheetNew').classList.remove('open');}
document.querySelectorAll('.mopt').forEach(function(el){el.addEventListener('click',function(){newMode=el.getAttribute('data-mode');document.querySelectorAll('.mopt').forEach(function(o){o.classList.toggle('sel',o===el);});});});
$('btnCreate').addEventListener('click',function(){
  var name=$('newRoomName').value.trim()||('新项目 '+ (ROOMS.length+1));
  var id='r'+Date.now();
  var members=newMode==='council'?['claude','gpt-5','gemini']:['hermes'];
  ROOMS.splice(1,0,{id:id,name:name,mode:newMode,members:members,phase:newMode==='council'?'讨论中':'已交付',last:newMode==='council'?'议会已就绪 · 等待议题':'hermes 待命',time:'现在',unread:0,played:false,msgs:[],
    seed:newMode==='council'?[{t:'sys',h:'COUNCIL 已召开 · claude / gpt-5 / gemini · 主持 hermes · 请提出议题'}]:[{t:'agent',who:'hermes',h:'单聊房间已创建, 直接下达设备指令或提问即可。'}]});
  $('newRoomName').value='';closeSheet();
  /* S6: 初始化房间文件目录 */
  var memberObjs=members.map(function(m){return {who:m,role:m==='hermes'?'agent':m};});
  B.initRoom(id,name,'',memberObjs);
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
  $('roomOpsMask').classList.add('open');
  $('sheetRoomOps').classList.add('open');
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
      exec:function(){openRoomOpsSheet(el.getAttribute('data-room'));}
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
  $('fileNewMask').classList.add('open');
  $('fileNewSheet').classList.add('open');
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

/* 技能搜索 (运行页内) */
$('skillSearch').addEventListener('input',function(){
  var skills=B.listSkills();
  renderSkillList(skills,$('skillSearch').value.trim());
});

/* ============ 看板事件绑定 ============ */
$('boardTrigger').addEventListener('click',openBoardPanel);
$('boardPanelMask').addEventListener('click',closeBoardPanel);
$('boardPanelClose').addEventListener('click',closeBoardPanel);
$('btnBoardAddClose').addEventListener('click',closeBoardAddSheet);
$('boardAddMask').addEventListener('click',closeBoardAddSheet);
$('btnBoardAddOk').addEventListener('click',confirmBoardAdd);

/* ============ 初始化 ============ */
initLang();
applyI18n();
renderRooms();
setTab('chat');
setTimeout(function(){refreshRuntime();},600);
ev('MOV v3.0 '+t('ready')+(B.present?' · '+t('bridge.on'):' · '+t('bridge.off')));
