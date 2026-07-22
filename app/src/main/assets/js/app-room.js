/* ============================================================
   app-room.js — 新建房间 + 房间操作 + Sheet 工具函数
   ============================================================ */

/* ============ Sheet 工具: 全局互斥 ============ */
function closeAllSheets(){
  document.querySelectorAll('.sheet-mask').forEach(function(m){m.classList.remove('open');});
  document.querySelectorAll('.sheet').forEach(function(s){s.classList.remove('open');});
}
function openSheetExclusive(maskId,sheetId){
  closeAllSheets();
  $(maskId).classList.add('open');
  $(sheetId).classList.add('open');
}

/* ============ 新建房间: 底部 Sheet (DESIGN_NEW_ROOM v2) ============ */
var _nrMode='single',_nrSel=[];

/* 模型勾选组件 (创建 sheet 与成员编辑共用)
   containerId 的兄弟元素 containerId+'Empty' 为空态提示;
   sel 为调用方持有的已选 id 数组 (原地修改); multi=true 多选(团队) */
function renderModelPicker(containerId,sel,multi,onChange){
  var models=B.listModels();
  var box=$(containerId),empty=$(containerId+'Empty');
  if(!models.length){
    box.innerHTML='';if(empty)empty.style.display='';
    if(onChange)onChange();
    return;
  }
  if(empty)empty.style.display='none';
  /* 清理已被删除的模型 id */
  var kept=sel.filter(function(id){return models.some(function(m){return m.id===id;});});
  sel.length=0;kept.forEach(function(id){sel.push(id);});
  /* 单选且无选择 → 默认勾注册表默认模型 */
  if(!multi&&sel.length===0){
    var def=models.find(function(m){return m.isDefault;})||models[0];
    sel.push(def.id);
  }
  var h='';
  models.forEach(function(m){
    var on=sel.indexOf(m.id)>=0;
    var col=m.color||providerColor(m.provider);
    h+='<div class="mpick'+(on?' sel':'')+'" data-mid="'+esc(m.id)+'">'
      +'<i class="av" style="background:'+esc(col)+'">'+esc((m.name||'?').slice(0,2).toUpperCase())+'</i>'
      +'<div class="mpick-info"><b>'+esc(m.name||m.id)+'</b><span>'+esc(providerDisplayName(m.provider))+(m.isDefault?' · '+t('model.default'):'')+'</span></div>'
      +'<span class="mcheck">'+(on?'✓':'')+'</span></div>';
  });
  box.innerHTML=h;
  box.querySelectorAll('.mpick').forEach(function(el){
    el.addEventListener('click',function(){
      var mid=el.getAttribute('data-mid');
      var i=sel.indexOf(mid);
      if(multi){if(i>=0)sel.splice(i,1);else sel.push(mid);}
      else{sel.length=0;sel.push(mid);}
      renderModelPicker(containerId,sel,multi,onChange);
    });
  });
  if(onChange)onChange();
}

function syncModeOpts(container,mode){
  container.querySelectorAll('.mopt').forEach(function(el){
    el.classList.toggle('sel',el.getAttribute('data-mode')===mode);
  });
}

$('fabNew').addEventListener('click',function(){
  _nrMode='single';_nrSel=[];
  $('newRoomName').value='';
  syncModeOpts($('newRoomModeOpts'),_nrMode);
  renderModelPicker('newRoomModels',_nrSel,false,updateCreateBtn);
  openSheetExclusive('newRoomMask','newRoomSheet');
  $('newRoomName').focus();
});

function closeNewRoomDialog(){closeAllSheets();}
$('newRoomMask').addEventListener('click',closeNewRoomDialog);
$('btnSheetClose').addEventListener('click',closeNewRoomDialog);

$('newRoomModeOpts').querySelectorAll('.mopt').forEach(function(el){
  el.addEventListener('click',function(){
    _nrMode=el.getAttribute('data-mode');
    syncModeOpts($('newRoomModeOpts'),_nrMode);
    renderModelPicker('newRoomModels',_nrSel,_nrMode==='council',updateCreateBtn);
  });
});

/* 团队模式且有模型可选时, 至少选 1 个才能创建; 无模型 → 允许跳过 ( DESIGN_NEW_ROOM 边界表 ) */
function updateCreateBtn(){
  var blocked=_nrMode==='council'&&B.listModels().length>0&&_nrSel.length===0;
  $('btnCreate').disabled=blocked;
  $('btnCreate').style.opacity=blocked?'.45':'';
}

$('btnGotoModels').addEventListener('click',function(){closeAllSheets();setTab('run');});

$('btnCreate').addEventListener('click',function(){
  var name=$('newRoomName').value.trim()||t('sheet.defaultName');
  var id='r'+Date.now();
  var ai=_nrSel.slice();
  var seed=_nrMode==='council'
    ?[{t:'sys',h:'COUNCIL 已召开 · 多模型 AI 团队 · MOV 主持'},{t:'agent',who:'mov',h:t('sheet.councilFirst')}]
    :[{t:'agent',who:'mov',h:'我是 MOV。直接下达指令或提问即可。'}];
  ROOMS.splice(1,0,{
    id:id, name:name, mode:_nrMode,
    members:{human:[{who:'you',role:'owner'}],ai:ai},
    phase:_nrMode==='council'?'讨论中':'已交付',
    last:_nrMode==='council'?t('sheet.councilReady'):'MOV 已就绪',
    time:'现在', unread:0, played:false, msgs:[], seed:seed
  });
  closeAllSheets();
  B.initRoomStorage(id);
  B.initRoom(id,name,'',ai); /* 激活死桥: 落盘 room.json 元数据 */
  renderRooms();persistRooms();enterRoom(id);
  ev('新建房间 '+name+' · '+_nrMode+' · '+ai.length+' AI');
});

/* ============ 房间操作 sheet ============ */
var _opsRoomId=null,_opsConfirmAction=null;

function openRoomOpsSheet(roomId){
  var room=ROOMS.find(function(r){return r.id===roomId;});
  if(!room)return;
  $('roomOpsMask').classList.add('open');
  $('sheetRoomOps').classList.add('open');
  _opsRoomId=roomId;
  $('roomOpsName').textContent=room.name;
  var isDesk=(roomId==='desk');
  $('opsRename').style.display=isDesk?'none':'';
  $('opsMembers').style.display=isDesk?'none':'';
  $('opsArchive').style.display=isDesk?'none':'';
  $('opsDelete').style.display=isDesk?'none':'';
  $('opsClear').style.display='';
  $('roomOpsMenu').style.display='';
  $('roomOpsConfirm').style.display='none';
  $('roomOpsRename').style.display='none';
  $('roomOpsMembers').style.display='none';
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

/* 入口 */
$('btnRoomMore').addEventListener('click',function(){
  if(curRoomId)openRoomOpsSheet(curRoomId);
});

function bindRoomListLongPress(){
  document.querySelectorAll('#roomList .room').forEach(function(el){
    bindLongPress(el,{
      text:'',
      exec:function(){openRoomOpsSheet(el.getAttribute('data-room'));}
    });
  });
}

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
  if(room&&newName){room.name=newName;$('roomTitle').textContent=newName;renderRooms();persistRooms();}
  closeRoomOpsSheet();
});

$('opsArchive').addEventListener('click',function(){
  var room=ROOMS.find(function(r){return r.id===_opsRoomId;});
  if(room){room.phase='已归档';setPhase(room.id,'已归档');B.toast('已归档');}
  closeRoomOpsSheet();
});

$('opsClear').addEventListener('click',function(){
  showOpsConfirm('确定清空所有聊天记录？不可撤销。',function(){
    clearRoomHistory(_opsRoomId);
    closeRoomOpsSheet();
  });
});

$('opsDelete').addEventListener('click',function(){
  showOpsConfirm('确定删除此房间？不可撤销。',function(){
    var room=ROOMS.find(function(r){return r.id===_opsRoomId;});
    if(room){
      var idx=ROOMS.indexOf(room);
      if(idx>=0)ROOMS.splice(idx,1);
      genCounter++;curRoomId=null;
      try{if(window.HermesBridge)HermesBridge.setRoomOpen('');}catch(e){}
      setTab('chat');showView('view-rooms');renderRooms();persistRooms();
      B.toast('已删除');
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

/* ============ AI 成员编辑 (DESIGN_NEW_ROOM v2: 创建后可改模式与成员) ============ */
var _opsMode='single',_opsSel=[];

$('opsMembers').addEventListener('click',function(){
  var room=ROOMS.find(function(r){return r.id===_opsRoomId;});
  if(!room)return;
  _opsMode=room.mode==='council'?'council':'single';
  _opsSel=roomAiMembers(room);
  $('roomOpsMenu').style.display='none';
  $('roomOpsConfirm').style.display='none';
  $('roomOpsRename').style.display='none';
  $('roomOpsMembers').style.display='';
  syncModeOpts($('opsModeOpts'),_opsMode);
  renderModelPicker('opsModels',_opsSel,_opsMode==='council',null);
});

$('opsModeOpts').querySelectorAll('.mopt').forEach(function(el){
  el.addEventListener('click',function(){
    _opsMode=el.getAttribute('data-mode');
    syncModeOpts($('opsModeOpts'),_opsMode);
    renderModelPicker('opsModels',_opsSel,_opsMode==='council',null);
  });
});

$('btnOpsGotoModels').addEventListener('click',function(){closeRoomOpsSheet();setTab('run');});

$('opsMembersCancel').addEventListener('click',function(){
  $('roomOpsMembers').style.display='none';
  $('roomOpsMenu').style.display='';
});

$('opsMembersOk').addEventListener('click',function(){
  var room=ROOMS.find(function(r){return r.id===_opsRoomId;});
  if(room){
    var ids=_opsSel.slice();
    var mode=_opsMode;
    if(ids.length===0)mode='single'; /* 0 成员团队 → 降级单聊 (DESIGN_NEW_ROOM 边界表) */
    room.mode=mode;
    /* 旧数组格式 ['mov'] 在此迁移为新对象格式 */
    room.members={human:[{who:'you',role:'owner'}],ai:ids};
    genCounter++; /* 进行中的 council 讨论作废 (复用房间切换守卫) */
    if(curRoomId===room.id)$('roomSub').textContent=roomSubtitle(room);
    renderRooms();persistRooms();
    B.toast(t('ops.membersSaved'));
    ev('编辑成员 '+room.name+' → '+mode+' · '+ids.length+' AI');
  }
  closeRoomOpsSheet();
});
