/* ============================================================
   chat.js — 房间进入 / 消息路由 / 设备指令执行 / AI 对话
   ============================================================ */

/* ---------- 进入房间 ---------- */
function enterRoom(id){
  genCounter++;
  curRoomId=id;
  try{if(window.HermesBridge)HermesBridge.setRoomOpen(id);}catch(e){}
  var r=ROOMS.find(function(x){return x.id===id;});
  r.unread=0;
  $('roomTitle').textContent=r.name;
  $('roomSub').textContent=(r.mode==='council'?'council · '+r.members.join(' / ')+' · 主持 mov':'单聊 · mov-agent');
  $('roomPhaseBadge').innerHTML=phaseBadge(r.phase);
  var b=$('chatBody');b.innerHTML='';
  if((!r.msgs||r.msgs.length===0)&&(r.msgData&&r.msgData.length>0)){
    rebuildMsgs(r);
  }
  if((!r.msgs||r.msgs.length===0)&&!r.seeded&&(r.seed&&r.seed.length>0)){
    r.seeded=true;
    r.seed.forEach(function(m){push(id,mkMsg(m),m);});
  }
  (r.msgs||[]).forEach(function(n){b.appendChild(n);});
  b.scrollTop=b.scrollHeight;
  bindAllMsgLongPress(id);
  /* Fix 3: 重置文件浏览路径 + 子 tab 回讨论 */
  _filesPath='';
  if(typeof setSubtab==='function')setSubtab('chat');
  pending=[];renderPend();closeTray();
  showView('view-room');setTab('chat');
  renderRooms();persistRooms();
  ev('进入房间 '+r.name);
  if(r.id==='fit'&&!r.played){r.played=true;runFitCouncil(id);}
}

/* ---------- 设备指令执行 (真后端) ---------- */
async function runDeviceCommand(id,text){
  var gen=genCounter;
  var alive=function(){return genCounter===gen&&curRoomId===id;};
  var t0=Date.now();
  var typing=mkMsg({t:'agent',who:'hermes',caret:true});
  showTyping(id,typing);
  var out=B.cmd(text);
  killTyping(typing);
  if(!alive())return;
  var dur=((Date.now()-t0)/1000).toFixed(2)+'s';
  var ok=out.indexOf('❌')!==0&&out.indexOf('⚠')!==0;
  push(id,toolNode('device',text,dur,esc(out)+'\n<span class="'+(ok?'ok-line':'err-line')+'">'+(ok?'exit 0':'exit 1')+'</span>'));
  push(id,mkMsg({t:'agent',who:'hermes',h:esc(out)}));
  var room=ROOMS.find(function(r){return r.id===id;});
  if(room){room.last=out.length>32?out.slice(0,32)+'…':out;room.time='现在';renderRooms();persistRooms();}
  ev('执行设备指令: '+text+' → '+(ok?'OK':'FAIL'));
}

/* ---------- AI 对话 (P0-1: 异步, 不阻塞 UI) ---------- */
function runAiChat(id,text){
  var gen=genCounter;
  var alive=function(){return genCounter===gen&&curRoomId===id;};
  var typing=mkMsg({t:'agent',who:'hermes',caret:true});
  showTyping(id,typing);
  B.aiAsync(text,function(resp){
    killTyping(typing);
    if(!alive())return;
    var content=resp.ok?resp.content:resp.content;
    push(id,mkMsg({t:'agent',who:'hermes',h:esc(content)}));
    var room=ROOMS.find(function(r){return r.id===id;});
    if(room){room.last=(content||'').replace(/\n/g,' ').slice(0,32);room.time='现在';renderRooms();persistRooms();}
  });
}

function routeMessage(id,text){
  var parsed=B.parse(text);
  if(parsed.cmd){
    runDeviceCommand(id,text);
  }else{
    var info=B.aiInfo();
    if(info.enabled&&info.configured){
      runAiChat(id,text);
    }else if(!info.enabled){
      push(id,mkMsg({t:'agent',who:'hermes',h:'「'+esc(text)+'」不是设备指令, 且 AI 已关闭。点右上角 <code>≡</code> 可启用并配置 API。'}));
    }else{
      push(id,mkMsg({t:'agent',who:'hermes',h:'「'+esc(text)+'」不是设备指令。AI 尚未配置 API Key —— 点右上角 <code>≡</code> 设置后即可畅聊。输入 <code>帮助</code> 查看全部设备指令。'}));
    }
  }
}

/* ---------- 附件系统 (P1-8: 真实文件选择器) ---------- */
function renderPend(){
  var h='';pending.forEach(function(a,i){h+='<span class="pend">'+esc(attName(a))+'<span class="x" data-i="'+i+'">✕</span></span>';});
  $('pendRow').innerHTML=h;
  document.querySelectorAll('#pendRow .x').forEach(function(x){x.addEventListener('click',function(){pending.splice(+x.getAttribute('data-i'),1);renderPend();ev('移除待发附件');});});
}
function closeTray(){trayOpen=false;$('attTray').classList.remove('open');$('plusBtn').classList.remove('open');}

/* ---------- 发送 ---------- */
function sendMsg(){
  if(!curRoomId)return;
  var v=$('msgInput').value.trim();
  if(!v&&pending.length===0)return;
  var room=ROOMS.find(function(r){return r.id===curRoomId;});
  var gen=genCounter,id=curRoomId;
  push(id,mkMsg({t:'agent',who:'YOU',me:true,h:esc(v),att:pending.length?pending[pending.length-1]:null}));
  room.last=v||('[附件] '+(pending[0]&&ATT[pending[0]]?ATT[pending[0]].n:'文件'));room.time='现在';renderRooms();persistRooms();
  $('msgInput').value='';pending=[];renderPend();
  ev('发送消息'+(v?'':'(纯附件)'));
  if(!v)return;
  if(room.mode==='council'){
    /* P1-5: 真实 Council 多角色讨论 */
    runCouncil(id,v,gen);
  }else{
    routeMessage(id,v);
  }
}

/* P1-5: 真实 Council 讨论 (异步, 不阻塞 UI) */
function runCouncil(id,topic,gen){
  var alive=function(){return genCounter===gen&&curRoomId===id;};
  setPhase(id,'讨论中');
  var typing=mkMsg({t:'agent',who:'claude',caret:true});
  showTyping(id,typing);
  B.councilAsync(topic,function(resp){
    killTyping(typing);
    if(!alive())return;
    if(!resp.ok){
      push(id,mkMsg({t:'agent',who:'hermes',h:'Council 调用失败: '+esc(resp.error||'未知错误')}));
      setPhase(id,'讨论中');
      return;
    }
    (resp.messages||[]).forEach(function(m){
      push(id,mkMsg({t:'agent',who:m.who,role:m.role,h:esc(m.content)}));
    });
    setPhase(id,'收敛中');
    push(id,mkMsg({t:'sys',h:'COUNCIL 收敛 · hermes 汇总'}));
    push(id,mkMsg({t:'agent',who:'hermes',h:esc(resp.summary||'(无汇总)')}));
    setPhase(id,'待评审');
    var room=ROOMS.find(function(r){return r.id===id;});
    if(room){room.last='Council 已收敛 · 待评审';renderRooms();persistRooms();}
    ev('Council 讨论完成');
  });
}

/* ============ 长按基础设施 (消息 + 技能共用) ============ */
var _lpTimer=null,_lpNode=null,_lpStartX=0,_lpStartY=0,_lpAction=null;

function bindLongPress(node,action){
  node.addEventListener('touchstart',function(e){
    _lpStartX=e.touches[0].clientX;_lpStartY=e.touches[0].clientY;
    _lpNode=node;_lpAction=action;
    _lpTimer=setTimeout(function(){triggerLongPress();},500);
  },{passive:true});
  node.addEventListener('touchmove',function(e){
    if(_lpTimer&&Math.abs(e.touches[0].clientX-_lpStartX)>10||Math.abs(e.touches[0].clientY-_lpStartY)>10){cancelLongPress();}
  },{passive:true});
  node.addEventListener('touchend',cancelLongPress,{passive:true});
  node.addEventListener('mousedown',function(e){
    _lpStartX=e.clientX;_lpStartY=e.clientY;
    _lpNode=node;_lpAction=action;
    _lpTimer=setTimeout(function(){triggerLongPress();},500);
  });
  node.addEventListener('mouseup',cancelLongPress);
  node.addEventListener('mouseleave',cancelLongPress);
}
function cancelLongPress(){if(_lpTimer){clearTimeout(_lpTimer);_lpTimer=null;}}
function triggerLongPress(){
  _lpTimer=null;
  if(!_lpNode||!_lpAction)return;
  _lpNode.classList.add('longpress-hl');
  showMsgActions(_lpAction.text,function(){
    _lpNode.classList.remove('longpress-hl');
    hideMsgActions();
    _lpAction.exec();
  });
}
function showMsgActions(text,onConfirm){
  var el=$('msgActions');
  $('msgActionText').textContent=text;
  el.classList.add('show');
  el._onConfirm=onConfirm;
}
function hideMsgActions(){
  var el=$('msgActions');
  el.classList.remove('show');
  el._onConfirm=null;
  if(_lpNode){_lpNode.classList.remove('longpress-hl');_lpNode=null;}
}
$('msgActions').addEventListener('click',function(){
  if(this._onConfirm)this._onConfirm();
});

/* ---------- 消息长按删除 ---------- */
function bindMsgLongPress(node,roomId,idx){
  bindLongPress(node,{
    text:t('msg.delete'),
    exec:function(){deleteMessage(roomId,idx);}
  });
}
function deleteMessage(roomId,idx){
  var room=ROOMS.find(function(r){return r.id===roomId;});
  if(!room)return;
  room.msgs=room.msgs||[];
  room.msgData=room.msgData||[];
  if(idx>=0&&idx<room.msgs.length){
    room.msgs.splice(idx,1);
    if(idx<room.msgData.length)room.msgData.splice(idx,1);
  }
  persistRooms();
  /* 重渲染聊天区 */
  var b=$('chatBody');b.innerHTML='';
  (room.msgs||[]).forEach(function(n){b.appendChild(n);});
  b.scrollTop=b.scrollHeight;
  B.toast(t('msg.deleted'));
  ev('删除消息 idx='+idx);
}

/* ---------- 清空聊天记录 ---------- */
function clearRoomHistory(roomId){
  var room=ROOMS.find(function(r){return r.id===roomId;});
  if(!room)return;
  room.msgs=[];
  room.msgData=[];
  /* seeded 保持 true, 重进不灌 seed */
  persistRooms();
  var b=$('chatBody');b.innerHTML='';
  B.toast(t('ops.cleared'));
  ev('清空聊天记录 '+room.name);
}

/* 进入房间后给每条消息绑定长按 */
function bindAllMsgLongPress(roomId){
  var room=ROOMS.find(function(r){return r.id===roomId;});
  if(!room||!room.msgs)return;
  room.msgs.forEach(function(node,idx){
    bindMsgLongPress(node,roomId,idx);
  });
}
