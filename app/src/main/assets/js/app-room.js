/* ============================================================
   app-room.js — 新建房间 Sheet + 房间操作 Sheet + 帮助
   (从 app.js 拆出, DESIGN_POLISH #2)
   ============================================================ */

/* ============ Sheet 互斥: 全局锁, 同一时刻只能有一个 sheet ============ */
var _sheetOpen=false;
function closeAllSheets(){
  document.querySelectorAll('.sheet-mask').forEach(function(m){m.classList.remove('open');});
  document.querySelectorAll('.sheet').forEach(function(s){s.classList.remove('open');});
  _sheetOpen=false;
}
function openSheetExclusive(maskId,sheetId){
  closeAllSheets();
  $(maskId).classList.add('open');
  $(sheetId).classList.add('open');
  _sheetOpen=true;
}

/* ============ 新建房间 sheet (两步) ============ */
var newMode='single';
$('fabNew').addEventListener('click',function(){
  openSheetExclusive('sheetMask','sheetNew');
  $('sheetStep1').style.display='';$('sheetStep2').style.display='none';
  $('newRoomName').value='';$('newRoomDesc').value='';
  _pickedModels=[]; /* B1: 重置勾选 */
  newMode='single'; /* 重置模式 */
  document.querySelectorAll('.mopt').forEach(function(o){o.classList.toggle('sel',o.getAttribute('data-mode')==='single');});
  $('modelPicker').classList.remove('open'); /* E2: 收起模型列表 */
  $('newRoomName').focus();ev('打开新建房间 sheet');
});
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
/* btnStep2Prev 已删除, ← 箭头替代 (B5) */

/* 协作方式选择 */
document.querySelectorAll('.mopt').forEach(function(el){el.addEventListener('click',function(){newMode=el.getAttribute('data-mode');document.querySelectorAll('.mopt').forEach(function(o){o.classList.toggle('sel',o===el);});renderModelPicker();});});

/* ============ 多模型: 动态模型勾选 (新建房间第二步) ============ */
var _pickedModels=[];
function renderModelPicker(){
  var wrap=$('modelPicker');
  if(!wrap)return;
  if(newMode!=='council'){wrap.classList.remove('open');return;}
  wrap.classList.add('open'); /* E2: max-height 展开 */
  var models=B.listModels();
  _pickedModels=models.filter(function(m){return m.isDefault;}).map(function(m){return m.id;});
  var h='<div class="sh-label">'+t('model.selectTitle')+'</div>';
  /* B4: 空状态 */
  if(models.length===0){
    h+='<div class="mpick-empty">'+t('model.none')+'<br><span style="cursor:pointer;color:var(--acc-live);font-weight:600" id="mpickGoSettings">→ 去设置添加模型</span></div>';
    wrap.innerHTML=h;
    var btn=document.getElementById('mpickGoSettings');
    if(btn)btn.addEventListener('click',function(){B.openSettings();closeSheet();});
    return;
  }
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
      if(m&&m.isDefault)return;
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
  var members;
  if(newMode==='council'){
    members={human:[{who:'you',role:'owner'}],ai:_pickedModels.slice()};
  }else{
    members={human:[{who:'you',role:'owner'}],ai:['mov']}; /* B2: 统一格式 */
  }
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
  openSheetExclusive('roomOpsMask','sheetRoomOps');
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

$('btnRoomMore').addEventListener('click',function(){
  if(curRoomId)openRoomOpsSheet(curRoomId);
});
function bindRoomListLongPress(){
  document.querySelectorAll('#roomList .room').forEach(function(el){
    bindLongPress(el,{
      text:'',
      exec:function(){if(!_sheetOpen)openRoomOpsSheet(el.getAttribute('data-room'));}
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

$('btnSheetClose').addEventListener('click',closeSheet);

/* 帮助按钮 */
$('btnHelp').addEventListener('click',function(){
  var desk=ROOMS.find(function(r){return r.id==='desk';});
  if(desk){enterRoom('desk');setTimeout(function(){$('msgInput').value='帮助';sendMsg();},300);}
});
