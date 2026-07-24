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

/* ============ 新建房间: 底部 Sheet (无协作方式 · agent 必选, 其余 AI 为可选评审) ============ */
var _nrSel=[];

/* 模型勾选组件 (创建 sheet 与成员编辑共用)
   首行固定 mov agent 锁定行 (必选, 不可取消);
   sel 为调用方持有的已选评审模型 id 数组 (原地修改); 恒为多选 */
function renderModelPicker(containerId,sel,multi,onChange){
  var models=B.listModels();
  var box=$(containerId),empty=$(containerId+'Empty');
  /* agent 锁定行: 永远在最前, 勾选且禁用 */
  var agentRow='<div class="mpick sel" data-mid="__agent" style="opacity:.85">'
    +'<i class="av" style="background:var(--acc-live);color:#fff">MO</i>'
    +'<div class="mpick-info"><b>mov agent</b><span>'+t('sheet.agentLock')+'</span></div>'
    +'<span class="mcheck">✓</span></div>';
  if(!models.length){
    box.innerHTML=agentRow;if(empty)empty.style.display='';
    if(onChange)onChange();
    return;
  }
  if(empty)empty.style.display='none';
  /* 清理已被删除的模型 id */
  var kept=sel.filter(function(id){return models.some(function(m){return m.id===id;});});
  sel.length=0;kept.forEach(function(id){sel.push(id);});
  var h=agentRow;
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
    if(el.getAttribute('data-mid')==='__agent')return; /* agent 锁定不可点 */
    el.addEventListener('click',function(){
      var mid=el.getAttribute('data-mid');
      var i=sel.indexOf(mid);
      if(i>=0)sel.splice(i,1);else sel.push(mid);
      renderModelPicker(containerId,sel,multi,onChange);
    });
  });
  if(onChange)onChange();
}

$('fabNew').addEventListener('click',function(){
  _nrSel=[];
  $('newRoomName').value='';
  /* 默认评审 = 评审标记模型; 无标记回退大脑 (单模型身兼两职; agent 恒在, 用户可再改) */
  var models=B.listModels();
  var reviewers=models.filter(function(m){return m.isReviewer;});
  if(reviewers.length){reviewers.forEach(function(m){_nrSel.push(m.id);});}
  else{var def=models.find(function(m){return m.isDefault;});if(def)_nrSel.push(def.id);}
  renderModelPicker('newRoomModels',_nrSel,true,updateCreateBtn);
  openSheetExclusive('newRoomMask','newRoomSheet');
  $('newRoomName').focus();
});

function closeNewRoomDialog(){closeAllSheets();}
$('newRoomMask').addEventListener('click',closeNewRoomDialog);
$('btnSheetClose').addEventListener('click',closeNewRoomDialog);

/* agent 恒在, 评审随意 → 创建键常开 */
function updateCreateBtn(){
  $('btnCreate').disabled=false;
  $('btnCreate').style.opacity='';
}

$('btnGotoModels').addEventListener('click',function(){closeAllSheets();setTab('run');});

$('btnCreate').addEventListener('click',function(){
  var name=$('newRoomName').value.trim()||t('sheet.defaultName');
  var id='r'+Date.now();
  var ai=_nrSel.slice();
  ROOMS.splice(1,0,{
    id:id, name:name, mode:'council',
    members:{human:[{who:'you',role:'owner'}],ai:ai},
    phase:'已交付', last:'MOV 已就绪',
    time:'现在', unread:0, played:false, msgs:[],
    seed:[{t:'agent',who:'mov',h:'我是 MOV。直接下达任务, 我会拆解计划给你确认, 然后执行并交付结果。'}]
  });
  closeAllSheets();
  B.initRoomStorage(id);
  B.initRoom(id,name,'',ai); /* 激活死桥: 落盘 room.json 元数据 */
  renderRooms();persistRooms();enterRoom(id);
  ev('新建房间 '+name+' · agent + '+ai.length+' 评审');
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
  /* desk 不再特殊 (2026-07-24): 重命名/成员/归档/删除全部可用 */
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
  /* 多选模式下长按不再弹操作 sheet (避免与勾选手势打架) */
  if(window._roomSelOn&&window._roomSelOn())return;
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

/* ============ AI 成员编辑 (agent 锁定必选, 评审团可增减) ============ */
var _opsSel=[];

$('opsMembers').addEventListener('click',function(){
  var room=ROOMS.find(function(r){return r.id===_opsRoomId;});
  if(!room)return;
  /* 拷贝一份再编辑: roomAiMembers 返回原数组引用, 直接改会导致点取消也生效 */
  _opsSel=roomAiMembers(room).slice();
  $('roomOpsMenu').style.display='none';
  $('roomOpsConfirm').style.display='none';
  $('roomOpsRename').style.display='none';
  $('roomOpsMembers').style.display='';
  renderModelPicker('opsModels',_opsSel,true,null);
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
    /* 房间恒为 agent 房: agent 必选, mode 统一 council; 评审可空 */
    room.mode='council';
    room.members={human:[{who:'you',role:'owner'}],ai:ids};
    genCounter++; /* 进行中的 agent 任务作废 (复用房间切换守卫) */
    if(curRoomId===room.id)$('roomSub').textContent=roomSubtitle(room);
    renderRooms();persistRooms();
    B.toast(t('ops.membersSaved'));
    ev('编辑成员 '+room.name+' → agent + '+ids.length+' 评审');
  }
  closeRoomOpsSheet();
});

/* ============ 会话多选删除 ============
   入口: 房间操作 sheet「多选删除」→ 进入多选模式并预选该房间;
   点卡片切换勾选 (render.js 点击守卫走 window._roomSelOn), 顶条全选/删除/取消;
   删除走条内二次确认, 批量移除后退出。 */
var _selMode=false,_selIds=[];
window._roomSelOn=function(){return _selMode;};
window.toggleRoomSel=function(id){
  var i=_selIds.indexOf(id);
  if(i>=0)_selIds.splice(i,1);else _selIds.push(id);
  updateSelBar();
};

function enterSelMode(preId){
  _selMode=true;_selIds=preId?[preId]:[];
  $('selBar').style.display='flex';
  $('roomList').classList.add('selmode');
  $('selBtnsNormal').style.display='flex';$('selBtnsConfirm').style.display='none';
  renderRooms();
  updateSelBar();
}
function exitSelMode(){
  _selMode=false;_selIds=[];
  $('selBar').style.display='none';
  $('roomList').classList.remove('selmode');
  renderRooms();
}
function updateSelBar(){
  $('selCount').textContent='已选 '+_selIds.length+' 项';
  $('selDel').disabled=_selIds.length===0;
  document.querySelectorAll('#roomList .room').forEach(function(el){
    el.classList.toggle('sel',_selIds.indexOf(el.getAttribute('data-room'))>=0);
  });
}

$('opsMultiSel').addEventListener('click',function(){
  var id=_opsRoomId;
  closeRoomOpsSheet();
  enterSelMode(id);
});
$('selAll').addEventListener('click',function(){
  var allIds=ROOMS.map(function(r){return r.id;});
  _selIds=(_selIds.length===allIds.length)?[]:allIds;
  updateSelBar();
});
$('selCancel').addEventListener('click',exitSelMode);
$('selDel').addEventListener('click',function(){
  if(!_selIds.length)return;
  $('selCount').textContent='确定删除 '+_selIds.length+' 个房间？不可撤销。';
  $('selBtnsNormal').style.display='none';$('selBtnsConfirm').style.display='flex';
});
$('selConfirmCancel').addEventListener('click',function(){
  $('selBtnsNormal').style.display='flex';$('selBtnsConfirm').style.display='none';
  updateSelBar();
});
$('selConfirmOk').addEventListener('click',function(){
  var n=_selIds.length,removedCurrent=false;
  ROOMS=ROOMS.filter(function(r){
    if(_selIds.indexOf(r.id)>=0){if(r.id===curRoomId)removedCurrent=true;return false;}
    return true;
  });
  genCounter++; /* 进行中的 agent 任务作废 (同单删语义) */
  if(removedCurrent){curRoomId=null;try{if(window.HermesBridge)HermesBridge.setRoomOpen('');}catch(e){}}
  exitSelMode();
  persistRooms();
  setTab('chat');showView('view-rooms');
  B.toast('已删除 '+n+' 个房间');
});
