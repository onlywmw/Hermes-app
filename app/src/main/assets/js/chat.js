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
  /* 多模型: 副标题显示真实模型名 */
  $('roomSub').textContent=roomSubtitle(r);
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
  /* Fix 3: 子 tab 回讨论 */
  if(typeof setSubtab==='function')setSubtab('chat');
  pending=[];renderPend();closeTray();
  showView('view-room');setTab('chat');
  renderRooms();persistRooms();
  ev('进入房间 '+r.name);
  /* 多模型: fit 房间不再自动触发硬编码剧本, 用户发第一条消息走真实 Council */
}

/* ---------- 设备指令执行 (真后端) ---------- */
async function runDeviceCommand(id,text){
  var gen=genCounter;
  var alive=function(){return genCounter===gen&&curRoomId===id;};
  var t0=Date.now();
  var typing=mkMsg({t:'agent',who:'mov',caret:true});
  showTyping(id,typing);
  var out=B.cmd(text);
  killTyping(typing);
  if(!alive())return;
  var dur=((Date.now()-t0)/1000).toFixed(2)+'s';
  var ok=out.indexOf('❌')!==0&&out.indexOf('⚠')!==0;
  push(id,toolNode('device',text,dur,esc(out)+'\n<span class="'+(ok?'ok-line':'err-line')+'">'+(ok?'exit 0':'exit 1')+'</span>'));
  push(id,mkMsg({t:'agent',who:'mov',h:out}));
  var room=ROOMS.find(function(r){return r.id===id;});
  if(room){room.last=out.length>32?out.slice(0,32)+'…':out;room.time='现在';renderRooms();persistRooms();}
  ev('执行设备指令: '+text+' → '+(ok?'OK':'FAIL'));
}

/* 房间副标题 (enterRoom / 成员编辑共用, DESIGN_NEW_ROOM v2) */
function roomSubtitle(r){
  /* 房间恒为 agent 房: 副标题 = agent + 评审团 (可无) */
  var aiNames=roomAiNames(r);
  return 'mov agent'+(aiNames.length?' · 评审 '+aiNames.join(' / '):'');
}

/* ---------- AI 对话 (P0-1: 异步, 不阻塞 UI) ---------- */
/* modelId 可选: DESIGN_NEW_ROOM v2 单聊房按绑定模型路由 */
function runAiChat(id,text,modelId){
  var gen=genCounter;
  var alive=function(){return genCounter===gen&&curRoomId===id;};
  var typing=mkMsg({t:'agent',who:'mov',caret:true});
  showTyping(id,typing);
  var onResp=function(resp){
    killTyping(typing);
    /* Fix: 去掉 esc 双重转义 (safeBubble 已 textNode 防护); 失败时 content 即错误信息 */
    var content=resp.content||(resp.ok?'':'AI 调用失败');
    if(!alive()){
      /* 切房不丢回复: 写入对应房间并置 unread, 不渲染 */
      var nr=ROOMS.find(function(r){return r.id===id;});
      if(nr){
        push(id,mkMsg({t:'agent',who:'mov',h:content}));
        if(curRoomId!==id)nr.unread=(nr.unread||0)+1;
        nr.last=(content||'').replace(/\n/g,' ').slice(0,32);nr.time='现在';
        renderRooms();persistRooms();
      }
      return;
    }
    push(id,mkMsg({t:'agent',who:'mov',h:content}));
    var room=ROOMS.find(function(r){return r.id===id;});
    if(room){room.last=(content||'').replace(/\n/g,' ').slice(0,32);room.time='现在';renderRooms();persistRooms();}
  };
  if(modelId){B.aiChatWithModel(text,modelId,onResp);}
  else{B.aiAsync(text,onResp);}
}

function routeMessage(id,text){
  var parsed=B.parse(text);
  if(parsed.cmd){
    runDeviceCommand(id,text);
    return;
  }
  /* DESIGN_NEW_ROOM v2: 非 desk 且绑定了模型的房间 → 按房间模型对话 */
  var room=ROOMS.find(function(r){return r.id===id;});
  var mids=room?roomAiMembers(room):[];
  if(room&&id!=='desk'&&mids.length>0){
    runAiChat(id,text,mids[0]);
    return;
  }
  var info=B.aiInfo();
  if(info.enabled&&info.configured){
    runAiChat(id,text);
  }else if(!info.enabled){
    push(id,mkMsg({t:'agent',who:'mov',h:'「'+text+'」不是设备指令, 且 AI 已关闭。点右上角 <code>≡</code> 可启用并配置 API。'}));
  }else{
    push(id,mkMsg({t:'agent',who:'mov',h:'「'+text+'」不是设备指令。AI 尚未配置 API Key —— 点右上角 <code>≡</code> 设置后即可畅聊。输入 <code>帮助</code> 查看全部设备指令。'}));
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
  /* agent 执行中 (非挂起): 发送键是 ■ 停止 */
  if(_agentExecuting&&_agentLoop&&_agentLoop.roomId===curRoomId&&!_agentLoop.awaiting){
    B.agentStop(_agentLoop.loopId);ev('喊停 agent');return;
  }
  var v=$('msgInput').value.trim();
  if(!v&&pending.length===0)return;
  var room=ROOMS.find(function(r){return r.id===curRoomId;});
  var gen=genCounter,id=curRoomId;
  push(id,mkMsg({t:'agent',who:'YOU',me:true,h:v,att:pending.length?pending[pending.length-1]:null}));
  room.last=v||('[附件] '+(pending[0]?attName(pending[0]):'文件'));room.time='现在';renderRooms();persistRooms();
  $('msgInput').value='';pending=[];renderPend();
  ev('发送消息'+(v?'':'(纯附件)'));
  if(!v)return;
  if(id==='desk'){
    routeMessage(id,v); /* desk 房: 设备指令 + 全局问答, 不走 agent 循环 */
    return;
  }
  /* 非 desk 房恒为 agent 房 (agent 必选): ask_user 答案 / 计划闸意见优先回灌 */
  if(_agentLoop&&_agentLoop.roomId===id&&_agentLoop.awaiting){
    var aw=_agentLoop.awaiting;
    _agentLoop.awaiting=null;restoreAgentInput();
    if(aw==='ask'){B.agentAnswer(_agentLoop.loopId,v);}
    else{B.agentPlanRespond(_agentLoop.loopId,false,v);push(id,mkMsg({t:'sys',h:'已驳回并补充, 重新规划中'}));}
  }else{
    runAgentTask(id,v,gen);
  }
}

/* ---------- Agentic 任务 (DESIGN_AGENT_LOOP v1: 1 驱动 + 工作日志) ---------- */
var _agentLoop=null;   /* {loopId, roomId, gen, awaiting: null|'plan'|'ask'} */
var _agentExecuting=false;

function runAgentTask(id,goal,gen){
  var room=ROOMS.find(function(r){return r.id===id;});
  var modelIds=room?roomAiMembers(room):[];
  setPhase(id,'讨论中');
  var typing=mkMsg({t:'agent',who:'mov',caret:true});
  showTyping(id,typing);
  B.agentStart(goal,id,modelIds,function(resp){
    killTyping(typing);
    if(curRoomId!==id)return;
    if(!resp.ok){push(id,mkMsg({t:'agent',who:'mov',h:resp.error||'任务启动失败'}));return;}
    if(resp.queued){push(id,mkMsg({t:'sys',h:'mov 还在执行上一个任务, 此任务已排队'}));}
    _agentLoop={loopId:resp.loopId,roomId:id,gen:gen,awaiting:null};
  });
}

/* 工作日志入口 (原生 BridgeAi → window._agentLog) */
window._agentLog=function(data){
  try{if(typeof data==='string')data=JSON.parse(data);}catch(e){return;}
  if(!_agentLoop||data.loopId!==_agentLoop.loopId)return;
  var id=_agentLoop.roomId;
  if(data.type==='phase'){setPhase(id,data.phase);return;}
  /* 切房守卫: 不渲染 UI, 但终态事件仍要清理状态, plan/filePreview 自动驳回防原生挂起 */
  if(curRoomId!==id||genCounter!==_agentLoop.gen){
    if(data.type==='plan'&&_agentLoop){B.agentPlanRespond(_agentLoop.loopId,false,'房间已切换, 计划自动驳回');}
    else if(data.type==='filePreview'&&_agentLoop){B.agentFileWriteRespond(_agentLoop.loopId,false);}
    else if(data.type==='deliver'||data.type==='fail'||data.type==='stopped'){endAgentTask(id);}
    return;
  }
  switch(data.type){
    case 'plan': renderAgentPlan(id,data);break;
    case 'filePreview': renderAgentFilePreview(id,data);break;
    case 'step':
      push(id,toolNode(data.name,data.arg||'',((data.durMs||0)/1000).toFixed(2)+'s',
        esc(data.result||'')+'\n<span class="'+(data.ok?'ok-line':'err-line')+'">'+(data.ok?'exit 0':'exit 1')+'</span>'));
      setAgentStatus(id,data);
      break;
    case 'ask':
      _agentLoop.awaiting='ask';
      push(id,mkMsg({t:'agent',who:'mov',h:data.question}));
      setAgentInputHint('回答问题后发送…');
      break;
    case 'note':
      push(id,mkMsg({t:'sys',h:esc(data.text)}));break;
    case 'review':
      /* v2: 交付评审投票轮次 */
      push(id,mkMsg({t:'sys',h:'交付评审·第'+data.round+'轮: '+(data.pass||0)+' 通过 / '+(data.fail||0)+' 返工'}));
      break;
    case 'deliver':
      renderDeliverCard(id,data.files||[]);
      var metric='实际 '+((data.promptTokens||0)+(data.completionTokens||0))+' tokens · '+fmtSec(data.elapsedSec)+' (预估 ~'+Math.round((data.estTokens||0)/1000)+'k · '+fmtSec(data.estSeconds)+')';
      if(data.reviewTokens>0)metric+=' · 含评审 '+data.reviewTokens;
      if(data.reworkRounds>0)metric+=' · 返工 '+data.reworkRounds+' 轮';
      push(id,mkMsg({t:'sys',h:metric}));
      /* v2: 交付评审结论 */
      if(data.comments&&data.comments.length){
        data.comments.forEach(function(c){
          push(id,mkMsg({t:'sys',h:'评审 '+(c.pass?'✓':'✗')+' '+esc(c.name||'')+': '+esc(c.reason||'')}));
        });
      }
      endAgentTask(id);break;
    case 'fail':
      push(id,mkMsg({t:'agent',who:'mov',h:'任务失败: '+(data.reason||'未知原因')}));
      endAgentTask(id);break;
    case 'stopped':
      push(id,mkMsg({t:'sys',h:'任务已停止'}));
      endAgentTask(id);break;
  }
};

function renderAgentPlan(id,data){
  _agentLoop.awaiting='plan';
  var p=document.createElement('div');p.className='msg wide';
  var pc=document.createElement('div');pc.className='plan-card';
  var phd=document.createElement('div');phd.className='ph';
  phd.textContent='PLAN · '+data.steps.length+' 步 · 预计 ~'+Math.round(data.estTokens/1000)+'k tokens · ~'+fmtSec(data.estSeconds)+(data.revised?' · 修订':'');
  pc.appendChild(phd);
  var pb=document.createElement('div');pb.className='pb';
  data.steps.forEach(function(s){
    var li=document.createElement('li');
    /* 计划闸: desc 之外同时展示授权文件路径, 供用户批准前核对 */
    var desc=s.desc||((s.action||'')+' '+(s.path||''));
    li.textContent=s.desc&&s.path?desc+' → '+s.path:desc;
    pb.appendChild(li);
  });
  pc.appendChild(pb);
  /* v2: 评审团意见区 */
  if(data.reviews&&data.reviews.length){
    var rv=document.createElement('div');
    rv.style.cssText='border-top:1px dashed var(--line);padding:var(--sp-2) var(--sp-3);';
    data.reviews.forEach(function(r){
      var line=document.createElement('div');
      line.style.cssText='font-family:var(--font-sans);font-size:var(--fs-sm);color:var(--ink-3);line-height:1.6;margin-top:2px;';
      line.textContent=(r.name||'评审')+(r.role?'('+r.role+')':'')+': '+(r.comment||'');
      rv.appendChild(line);
    });
    pc.appendChild(rv);
  }
  var pf=document.createElement('div');pf.className='pf';
  var bAp=document.createElement('button');bAp.className='btn btn-acc';bAp.textContent=t('plan.approve');
  var bRj=document.createElement('button');bRj.className='btn btn-ghost';bRj.textContent=t('plan.reject');
  bAp.addEventListener('click',function(){
    /* 任务已结束 (_agentLoop 被清空/换轮) 时禁止再点, 先置灰再 return */
    if(!_agentLoop||_agentLoop.loopId!==data.loopId){bAp.disabled=true;bRj.disabled=true;return;}
    bAp.disabled=true;bRj.disabled=true;bAp.textContent=t('plan.approved');
    _agentLoop.awaiting=null;
    B.agentPlanRespond(data.loopId,true,'');
    ev('批准计划 → agent 开工');
  });
  bRj.addEventListener('click',function(){
    if(!_agentLoop||_agentLoop.loopId!==data.loopId){bAp.disabled=true;bRj.disabled=true;return;}
    bRj.disabled=true;
    setAgentInputHint('输入驳回意见后发送…');
  });
  pf.appendChild(bAp);pf.appendChild(bRj);pc.appendChild(pf);
  p.appendChild(pc);
  push(id,p);
}

/* P1: 写盘前强制预览卡 (落盘前 loop 挂起, 确认才写; 驳回=该步失败, AI 重试/改计划) */
function renderAgentFilePreview(id,data){
  var p=document.createElement('div');p.className='msg wide';
  var pc=document.createElement('div');pc.className='plan-card';
  var phd=document.createElement('div');phd.className='ph';
  phd.textContent='写入预览 · '+(data.path||'');
  pc.appendChild(phd);
  /* 内容只走 textContent, 不拼 HTML (安全红线) */
  var pre=document.createElement('pre');pre.className='pvw';
  pre.textContent=data.content||'';
  pc.appendChild(pre);
  var pf=document.createElement('div');pf.className='pf';
  var bOk=document.createElement('button');bOk.className='btn btn-acc';bOk.textContent='确认写入';
  var bRj=document.createElement('button');bRj.className='btn btn-ghost';bRj.textContent='驳回';
  bOk.addEventListener('click',function(){
    /* 任务已结束/换轮时禁止再点, 先置灰再 return */
    if(!_agentLoop||_agentLoop.loopId!==data.loopId){bOk.disabled=true;bRj.disabled=true;return;}
    bOk.disabled=true;bRj.disabled=true;bOk.textContent='已确认 ✓';
    B.agentFileWriteRespond(data.loopId,true);
    ev('确认写入 '+(data.path||''));
  });
  bRj.addEventListener('click',function(){
    if(!_agentLoop||_agentLoop.loopId!==data.loopId){bOk.disabled=true;bRj.disabled=true;return;}
    bOk.disabled=true;bRj.disabled=true;bRj.textContent='已驳回';
    B.agentFileWriteRespond(data.loopId,false);
    ev('驳回写入 '+(data.path||''));
  });
  pf.appendChild(bOk);pf.appendChild(bRj);pc.appendChild(pf);
  p.appendChild(pc);
  push(id,p);
}

/* 执行中状态: 发送键变 ■ 停止, 副标题实时计量 */
function setAgentStatus(id,data){
  _agentExecuting=true;
  $('btnSend').textContent='■';
  $('roomSub').textContent='执行中 · 已用 '+(((data.promptTokens+data.completionTokens)||0)/1000).toFixed(1)+'k tokens · '+fmtSec(data.elapsedSec)+' · 点 ■ 停止';
}
function endAgentTask(id){
  _agentExecuting=false;
  $('btnSend').textContent=t('room.send');
  var r=ROOMS.find(function(x){return x.id===id;});
  if(r&&curRoomId===id)$('roomSub').textContent=roomSubtitle(r);
  _agentLoop=null;
}
function setAgentInputHint(h){$('msgInput').placeholder=h;}
function restoreAgentInput(){$('msgInput').placeholder=t('room.input');}
function fmtSec(s){s=Math.round(s||0);return s>=60?Math.floor(s/60)+'分'+(s%60)+'秒':s+'秒';}

/* ---------- 交付物卡片 ---------- */
function renderDeliverCard(id,produced){
  var d=document.createElement('div');d.className='msg wide';
  var dc=document.createElement('div');dc.className='deliver-card';
  var ic=document.createElement('span');ic.className='ic';ic.textContent='▣';
  var info=document.createElement('div');
  var tt=document.createElement('div');tt.className='tt';tt.textContent=t('plan.deliverTitle');
  var ss=document.createElement('div');ss.className='ss';
  ss.textContent=produced.length?produced.join(' · '):t('plan.noOutput');
  info.appendChild(tt);info.appendChild(ss);
  var btn=document.createElement('button');btn.className='btn btn-acc';btn.textContent=t('files.view');
  btn.addEventListener('click',function(){
    if(produced.length&&curRoomId===id){setSubtab('files');}
    else{B.toast(t('plan.deliverTitle'));}
  });
  dc.appendChild(ic);dc.appendChild(info);dc.appendChild(btn);
  d.appendChild(dc);
  push(id,d);
}

/* ============ 长按基础设施 (消息 + 技能共用) ============ */
var _lpTimer=null,_lpNode=null,_lpStartX=0,_lpStartY=0,_lpAction=null;

function bindLongPress(node,action){
  /* P1.5: 去重 — 节点跨 enterRoom 复用时避免重复绑监听 */
  if(node._lpBound)return;
  node._lpBound=true;
  node.addEventListener('touchstart',function(e){
    _lpStartX=e.touches[0].clientX;_lpStartY=e.touches[0].clientY;
    _lpNode=node;_lpAction=action;
    _lpTimer=setTimeout(function(){triggerLongPress();},500);
  },{passive:true});
  node.addEventListener('touchmove',function(e){
    /* P1.5: 修复 &&/|| 优先级 — dy 判断也必须在 _lpTimer 守卫内 */
    if(_lpTimer&&(Math.abs(e.touches[0].clientX-_lpStartX)>10||Math.abs(e.touches[0].clientY-_lpStartY)>10)){cancelLongPress();}
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
  /* P1.5: 记录长按时间戳, click handler 300ms 内抑制, 防长按/click 双触发 */
  window._lpFired=Date.now();
  _lpNode.classList.add('longpress-hl');
  /* 空 text = 非危险操作 (如房间卡片长按): 不弹 msgActions 确认条直接执行,
     避免无字空白条残留"上膛"劫持后续点击; 高亮反馈保留 */
  if(!_lpAction.text){
    var node=_lpNode;
    setTimeout(function(){node.classList.remove('longpress-hl');},350);
    _lpAction.exec();
    return;
  }
  showMsgActions(_lpAction.text,function(){
    _lpNode.classList.remove('longpress-hl');
    hideMsgActions();
    _lpAction.exec();
  });
}
/* P1.5: click handler 首行调用, 长按后 300ms 内返回 true 应直接 return */
function lpSuppressClick(){return window._lpFired&&(Date.now()-window._lpFired)<300;}
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
/* 兜底: 点确认条以外任意处自动解除"上膛"状态 (捕获阶段, 防内层 stopPropagation) */
document.addEventListener('touchstart',function(e){
  var el=$('msgActions');
  if(el.classList.contains('show')&&!el.contains(e.target))hideMsgActions();
},true);

/* ---------- 消息长按删除 ---------- */
/* P1.5: 不再闭包捕获 idx — 删除一条后剩余节点 idx 过期会删错消息;
   长按触发时按节点在 room.msgs 中的实时位置定位 */
function bindMsgLongPress(node,roomId){
  bindLongPress(node,{
    text:t('msg.delete'),
    exec:function(){
      var room=ROOMS.find(function(r){return r.id===roomId;});
      var idx=(room&&room.msgs)?room.msgs.indexOf(node):-1;
      if(idx>=0)deleteMessage(roomId,idx);
    }
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
  room.msgs.forEach(function(node){
    bindMsgLongPress(node,roomId);
  });
}
