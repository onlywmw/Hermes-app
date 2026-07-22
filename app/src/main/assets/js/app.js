/* ============================================================
   app.js — 入口: 初始化 + 全局事件
   其他绑定已移至: app-chat / app-room / app-files / app-board / app-run
   ============================================================ */

/* 返回按钮 */
document.getElementById('btnBack').addEventListener('click',function(){
  genCounter++;curRoomId=null;
  try{if(window.HermesBridge)HermesBridge.setRoomOpen('');}catch(e){}
  setTab('chat');showView('view-rooms');renderRooms();ev('返回房间列表');
});

/* ============ 初始化 ============ */
initLang();
applyI18n();
refreshModelAvatars();
renderRooms();
setTab('chat');
setTimeout(function(){refreshRuntime();},600);
ev('MOV v3.0 '+t('ready')+(B.present?' · '+t('bridge.on'):' · '+t('bridge.off')));

/* 加密状态检查 */
if(B.present){
  var enc=B.encStatus();
  if(enc&&!enc.ok){
    setTimeout(function(){B.toast('⚠ 加密存储不可用, API Key 以明文存储');}, 2000);
  }
}
