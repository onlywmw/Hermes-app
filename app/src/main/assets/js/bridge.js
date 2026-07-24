/* ============================================================
   bridge.js — 原生桥 + 全局错误边界
   ============================================================ */

/* P0-1: 异步回调注册表 */
var _cbId=0,_cbMap={};
window._hermesCb=function(id,data){var fn=_cbMap[id];if(fn){delete _cbMap[id];fn(data);}};
function nextCbId(){return 'cb'+(++_cbId)+'_'+Date.now();}

var B=(function(){
  var b=window.HermesBridge||null;
  return {
    present:b||null,
    cmd:function(c){try{return b?b.execCommand(c):'';}catch(e){return '';}},
    parse:function(t){try{return b?JSON.parse(b.parseIntent(t)):{};}catch(e){return {};}},
    ai:function(t){try{return b?b.aiChat(t):'';}catch(e){return '';}},
    /* P0-1: 异步 AI */
    aiAsync:function(t,cb){if(!b){cb({ok:false,content:'浏览器演示模式'});return;}var id=nextCbId();_cbMap[id]=cb;b.aiChatAsync(t,id);},
    /* DESIGN_NEW_ROOM v2: 单聊房按绑定模型对话 */
    aiChatWithModel:function(t,modelId,cb){if(!b){cb({ok:false,content:'浏览器演示模式'});return;}var id=nextCbId();_cbMap[id]=cb;b.aiChatWithModel(t,modelId||'',id);},
    /* DESIGN_AGENT_LOOP: agentic 循环 (modelIds = 房间 AI 成员, v2 评审团候选) */
    agentStart:function(goal,roomId,modelIds,cb){if(!b){cb({ok:false,error:'浏览器演示模式'});return;}var id=nextCbId();_cbMap[id]=cb;b.agentStart(goal,roomId,JSON.stringify(modelIds||[]),id);},
    agentStop:function(loopId){try{if(b)b.agentStop(loopId);}catch(e){}},
    agentAnswer:function(loopId,text){try{if(b)b.agentAnswer(loopId,text);}catch(e){}},
    agentPlanRespond:function(loopId,approved,note){try{if(b)b.agentPlanRespond(loopId,approved,note||'');}catch(e){}},
    /* P1: 写盘前预览确认/驳回 */
    agentFileWriteRespond:function(loopId,approved){try{if(b)b.agentFileWriteRespond(loopId,approved);}catch(e){}},
    /* 计划外 shell.exec 确认/驳回 */
    agentShellRespond:function(loopId,approved){try{if(b)b.agentShellRespond(loopId,approved);}catch(e){}},
    aiInfo:function(){try{return b?JSON.parse(b.getAiInfo()):{enabled:false,configured:false};}catch(e){return {enabled:false,configured:false};}},
    device:function(){try{return b?JSON.parse(b.getDeviceInfo()):{};}catch(e){return {};}},
    toast:function(m){try{if(b)b.toast(m);}catch(e){}},
    openSettings:function(){try{if(b)b.openAiSettings();}catch(e){}},
    /* M3: 交互式终端 */
    openTerminal:function(){try{if(b)b.openTerminal();}catch(e){}},
    /* M4: 部署服务器配置 */
    getDeployConfig:function(){try{return b?JSON.parse(b.getDeployConfig()):{configured:false};}catch(e){return {configured:false};}},
    saveDeployConfig:function(o){try{return b?JSON.parse(b.saveDeployConfig(JSON.stringify(o))):{ok:false};}catch(e){return {ok:false};}},
    testDeployConnection:function(cb){if(!b){cb({ok:false,content:'浏览器演示模式'});return;}var id=nextCbId();_cbMap[id]=cb;b.testDeployConnection(id);},
    /* P1-7: 技能 */
    listSkills:function(){try{return b?JSON.parse(b.listSkills()):[];}catch(e){return [];}},
    recordSkill:function(id){try{if(b)b.recordSkillUse(id);}catch(e){}},
    deleteSkill:function(id){try{return b?JSON.parse(b.deleteSkill(id)):{ok:false};}catch(e){return {ok:false};}},
    /* 房间文件操作 */
    writeFile:function(rid,path,content){try{return b?JSON.parse(b.writeFile(rid,path,content)):{ok:false};}catch(e){return {ok:false};}},
    readFile:function(rid,path){try{return b?JSON.parse(b.readFile(rid,path)):{ok:false};}catch(e){return {ok:false};}},
    deleteFile:function(rid,path){try{return b?JSON.parse(b.deleteFile(rid,path)):{ok:false};}catch(e){return {ok:false};}},
    listRoomFiles:function(rid,sub){try{return b?JSON.parse(b.listRoomFiles(rid,sub||'')):{ok:false,files:[]};}catch(e){return {ok:false,files:[]};}},
    initRoom:function(rid,name,desc,members){try{return b?JSON.parse(b.initRoom(rid,name,desc,JSON.stringify(members))):{ok:false};}catch(e){return {ok:false};}},
    /* 存储系统 */
    listWorkFiles:function(rid){try{return b?JSON.parse(b.listWorkFiles(rid)):{ok:false,files:[]};}catch(e){return {ok:false,files:[]};}},
    saveWorkFile:function(rid,path,content,author){try{return b?JSON.parse(b.saveWorkFile(rid,path,content,author||'you')):{ok:false};}catch(e){return {ok:false};}},
    listVersions:function(rid,path){try{return b?JSON.parse(b.listVersions(rid,path)):{ok:false,versions:[]};}catch(e){return {ok:false,versions:[]};}},
    restoreVersion:function(rid,path,snap){try{return b?JSON.parse(b.restoreVersion(rid,path,snap)):{ok:false};}catch(e){return {ok:false};}},
    listInboxFiles:function(rid){try{return b?JSON.parse(b.listInboxFiles(rid)):{ok:false,files:[]};}catch(e){return {ok:false,files:[]};}},
    listArchiveFiles:function(rid){try{return b?JSON.parse(b.listArchiveFiles(rid)):{ok:false,sources:[]};}catch(e){return {ok:false,sources:[]};}},
    writeArchive:function(rid,source,content){try{return b?JSON.parse(b.writeArchive(rid,source,content)):{ok:false};}catch(e){return {ok:false};}},
    listNotes:function(){try{return b?JSON.parse(b.listNotes()):{ok:false,files:[]};}catch(e){return {ok:false,files:[]};}},
    saveNote:function(name,content){try{return b?JSON.parse(b.saveNote(name,content)):{ok:false};}catch(e){return {ok:false};}},
    readNote:function(name){try{return b?JSON.parse(b.readNote(name)):{ok:false};}catch(e){return {ok:false};}},
    deleteNote:function(name){try{return b?JSON.parse(b.deleteNote(name)):{ok:false};}catch(e){return {ok:false};}},
    initRoomStorage:function(rid){try{if(b)b.initRoomStorage(rid);}catch(e){}},
    getRoomMeta:function(rid){try{return b?JSON.parse(b.getRoomMeta(rid)):{ok:true,files:[]};}catch(e){return {ok:true,files:[]};}},
    deleteWorkFile:function(rid,path){try{return b?JSON.parse(b.deleteWorkFile(rid,path)):{ok:false};}catch(e){return {ok:false};}},
    deleteInboxFile:function(rid,path){try{return b?JSON.parse(b.deleteInboxFile(rid,path)):{ok:false};}catch(e){return {ok:false};}},
    deleteArchiveFile:function(rid,path){try{return b?JSON.parse(b.deleteArchiveFile(rid,path)):{ok:false};}catch(e){return {ok:false};}},
    appendChat:function(rid,msg){try{if(b)b.appendChatMessage(rid,JSON.stringify(msg));}catch(e){}},
    loadChat:function(rid,date){try{return b?JSON.parse(b.loadChatMessages(rid,date)):{ok:true,messages:[]};}catch(e){return {ok:true,messages:[]};}},
    /* P1-8: 文件选择 */
    pickFile:function(cb,roomId){if(!b){cb(null);return;}var id=nextCbId();_cbMap[id]=cb;b.pickFile(id,roomId||'');},
    /* 发送到桌面: 产出文件固定桌面快捷方式 (CONTRACT_STORAGE 发送到桌面 §) */
    pinFileShortcut:function(rid,path,label){try{return b&&b.pinFileShortcut?JSON.parse(b.pinFileShortcut(rid,path,label||'')):{ok:false,error:'桥不可用'};}catch(e){return {ok:false,error:String(e)};}},
    /* 打包成应用: HTML → 签名 APK 并调起系统安装器 (CONTRACT_STORAGE 打包成应用 §); 异步回调 */
    buildApk:function(rid,path,appName,cb){if(!b||!b.buildApk){cb({ok:false,error:'桥不可用'});return;}var id=nextCbId();_cbMap[id]=cb;b.buildApk(rid,path,appName||'',id);},
    /* 一键安装: 房间产出 APK → 系统安装器 */
    installApk:function(rid,path){try{return b?JSON.parse(b.installApk(rid,path)):{ok:false};}catch(e){return {ok:false};}},
    /* RUNTIME 真数据 */
    runtimeStats:function(){try{return b?JSON.parse(b.getRuntimeStats()):{};}catch(e){return {};}},
    permState:function(){try{return b?JSON.parse(b.getPermissionState()):{};}catch(e){return {};}},
    widgetInfo:function(){try{return b?JSON.parse(b.getWidgetInfo()):{count:0};}catch(e){return {count:0};}},
    openAppSettings:function(){try{if(b)b.openAppSettings();}catch(e){}},
    /* TC-M09: 系统浏览器打开 URL (如"获取 API Key"控制台页); 原生侧 http/https 白名单 */
    openUrl:function(u){try{if(b&&b.openUrl)b.openUrl(u);}catch(e){}},
    /* 多模型管理 (DESIGN_MULTI_MODEL 第1层) */
    listModels:function(){try{return b?JSON.parse(b.listModels()):[];}catch(e){return [];}},
    /* 厂商预设 (ModelPresets · "添加模型只填 API Key"): key/displayName/baseUrl/defaultModel/models/keyConsoleUrl/note */
    providerPresets:function(){try{return b&&b.getProviderPresets?JSON.parse(b.getProviderPresets()):[];}catch(e){return [];}},
    addModel:function(json){try{return b?JSON.parse(b.addModel(typeof json==='string'?json:JSON.stringify(json))):{ok:false};}catch(e){return {ok:false};}},
    updateModel:function(json){try{return b?JSON.parse(b.updateModel(typeof json==='string'?json:JSON.stringify(json))):{ok:false};}catch(e){return {ok:false};}},
    deleteModel:function(id){try{return b?JSON.parse(b.deleteModel(id)):{ok:false};}catch(e){return {ok:false};}},
    testModel:function(json,cb){if(!b){cb({ok:false,error:'浏览器演示模式'});return;}var id=nextCbId();_cbMap[id]=cb;b.testModel(typeof json==='string'?json:JSON.stringify(json),id);},
    setDefaultModel:function(id){try{return b?JSON.parse(b.setDefaultModel(id)):{ok:false};}catch(e){return {ok:false};}},
    setReviewer:function(id){try{return b?JSON.parse(b.setReviewer(id)):{ok:false};}catch(e){return {ok:false};}},
    /* V5: Token 仪表盘 */
    tokenStats:function(){try{return b?JSON.parse(b.getTokenStats()):{today:0,month:0,quota:5000000};}catch(e){return {today:0,month:0,quota:5000000};}},
    /* 加密状态检查 */
    encStatus:function(){try{return b?JSON.parse(b.getEncStatus()):{ok:true};}catch(e){return {ok:true};}}
  };
})();

/* ============ 全局错误边界 ============ */
window.onerror=function(msg,url,line,col,err){
  /* 可观测性: 输出 message/来源/行列/堆栈; file:// 跨源脱敏时 err 为 null, 至少保留 url:line:col */
  var info='SHELL ERROR: '+msg+' @ '+(url||'?')+':'+line+':'+col;
  if(err&&err.stack)info+='\n'+err.stack;
  try{if(window.HermesBridge)HermesBridge.log(info);}catch(e){}
  try{
    var b=document.getElementById('chatBody');
    if(b){
      /* Fix: 不用 innerHTML+= (会重解析全部子节点、销毁事件绑定)，改用 appendChild */
      var lineDiv=document.createElement('div');
      lineDiv.className='sysline';
      lineDiv.style.color='var(--err)';
      lineDiv.style.borderColor='var(--err-dot)';
      lineDiv.textContent=String(msg).slice(0,200)+' (行 '+line+')';
      b.appendChild(lineDiv);
      b.scrollTop=b.scrollHeight;
    }
  }catch(e2){}
  return true;
};
