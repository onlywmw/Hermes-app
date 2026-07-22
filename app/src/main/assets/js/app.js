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

/* 新建房间 + 房间操作 — 全部已移至 app-room.js */

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
  /* 加密状态检查: 降级明文时弹 toast */
  if(B.present){
    var enc=B.encStatus();
    if(enc&&!enc.ok){
      setTimeout(function(){B.toast('⚠ 加密存储不可用, API Key 以明文存储');}, 2000);
    }
  }
