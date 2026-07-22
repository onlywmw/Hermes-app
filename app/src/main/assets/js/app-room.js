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

/* ============ 新建房间 ============ */
$('fabNew').addEventListener('click',function(){
  closeAllSheets();
  $('sheetMask').classList.add('open');
  $('sheetNew').classList.add('open');
  $('newRoomName').value='';
  $('newRoomName').focus();
});

function closeNewRoomSheet(){
  closeAllSheets();
}

$('sheetMask').addEventListener('click',closeNewRoomSheet);
$('btnSheetClose').addEventListener('click',closeNewRoomSheet);

$('btnCreate').addEventListener('click',function(){
  var name=$('newRoomName').value.trim()||'新项目';
  var id='r'+Date.now();
  ROOMS.splice(1,0,{
    id:id, name:name, mode:'single', members:['mov'],
    phase:'已交付', last:'MOV 已就绪', time:'现在',
    unread:0, played:false, msgs:[],
    seed:[{t:'agent',who:'mov',h:'我是 MOV。直接下达指令或提问即可。'}]
  });
  closeNewRoomSheet();
  B.initRoomStorage(id);
  renderRooms();persistRooms();enterRoom(id);
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
  $('opsArchive').style.display=isDesk?'none':'';
  $('opsDelete').style.display=isDesk?'none':'';
  $('opsClear').style.display='';
  $('roomOpsMenu').style.display='';
  $('roomOpsConfirm').style.display='none';
  $('roomOpsRename').style.display='none';
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

/* ============ 顶栏入口 ============ */
$('btnSettings').addEventListener('click',function(){
  B.present?B.openSettings():B.toast('设置');
});
