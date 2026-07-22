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
  var aiNames=roomAiNames(r);
  $('roomSub').textContent=(r.mode==='council'?'council · '+(aiNames.length?aiNames.join(' / '):'--')+' · 主持 mov':'单聊 · mov-agent');
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

/* ---------- AI 对话 (P0-1: 异步, 不阻塞 UI) ---------- */
function runAiChat(id,text){
  var gen=genCounter;
  var alive=function(){return genCounter===gen&&curRoomId===id;};
  var typing=mkMsg({t:'agent',who:'mov',caret:true});
  showTyping(id,typing);
  B.aiAsync(text,function(resp){
    killTyping(typing);
    if(!alive())return;
    /* Fix: 去掉 esc 双重转义 (safeBubble 已 textNode 防护); 失败时 content 即错误信息 */
    var content=resp.content||(resp.ok?'':'AI 调用失败');
    push(id,mkMsg({t:'agent',who:'mov',h:content}));
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
      push(id,mkMsg({t:'agent',who:'mov',h:'「'+esc(text)+'」不是设备指令, 且 AI 已关闭。点右上角 <code>≡</code> 可启用并配置 API。'}));
    }else{
      push(id,mkMsg({t:'agent',who:'mov',h:'「'+esc(text)+'」不是设备指令。AI 尚未配置 API Key —— 点右上角 <code>≡</code> 设置后即可畅聊。输入 <code>帮助</code> 查看全部设备指令。'}));
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
  push(id,mkMsg({t:'agent',who:'YOU',me:true,h:v,att:pending.length?pending[pending.length-1]:null}));
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

/* 多模型: 真实 Council 讨论 (伪流式: 先到先显, DESIGN_HYBRID v2.0) */
function runCouncil(id,topic,gen){
  var alive=function(){return genCounter===gen&&curRoomId===id;};
  var room=ROOMS.find(function(r){return r.id===id;});
  var modelIds=room?roomAiMembers(room):[];
  setPhase(id,'讨论中');
  var typing=mkMsg({t:'agent',who:'mov',caret:true});
  showTyping(id,typing);
  var replyCount=0;
  var allReplies=[];
  /* 注册全局回调: 每个模型完成就推一条消息 (先到先显) */
  window._councilReply=function(callbackId,data){
    if(!alive())return;
    try{if(typeof data==='string')data=JSON.parse(data);}catch(e){return;}

    if(data.type==='error'){
      killTyping(typing);
      push(id,mkMsg({t:'agent',who:'mov',h:t('council.fail')+(data.content||'未知错误')}));
      setPhase(id,'讨论中');return;
    }
    if(data.type==='summary'){
      killTyping(typing);
      setPhase(id,'收敛中');
      push(id,mkMsg({t:'sys',h:t('council.converge')}));
      push(id,mkMsg({t:'agent',who:'mov',h:data.summary||'(无汇总)'}));
      var steps;try{steps=JSON.parse(data.nextSteps||'[]');}catch(e){steps=[];}
      if(Array.isArray(steps)&&steps.length){
        renderCouncilPlan(id,steps,gen);setPhase(id,'待确认');
      }else{setPhase(id,'待评审');}
      var room2=ROOMS.find(function(r){return r.id===id;});
      if(room2){room2.last=t('council.done');renderRooms();persistRooms();}
      ev('Council 完成 · '+replyCount+' 个模型回复');
      return;
    }
    /* 单个模型回复 — 先到先显, 逐条追加 */
    replyCount++;
    killTyping(typing);
    push(id,mkMsg({t:'agent',who:data.who,role:data.role,h:data.content||''}));
    /* 如果还有没到的, 显示等待 */
    typing=mkMsg({t:'agent',who:'mov',caret:true});
    if(alive())showTyping(id,typing);
    ev('Council 收到 #'+replyCount+' '+data.name);
  };
  /* 发起异步讨论 */
  B.councilAsync(topic,modelIds,function(resp){/* 旧回调不再触发, 流式走 _councilReply */});
}

/* ---------- 投票卡片 (结构化输出) ---------- */
function renderCouncilVotes(id,votes){
  var v=document.createElement('div');v.className='msg wide';
  var vc=document.createElement('div');vc.className='vote-card';
  var vh=document.createElement('div');vh.className='vh';vh.textContent='COUNCIL VOTE';
  vc.appendChild(vh);
  votes.forEach(function(row){
    var vr=document.createElement('div');vr.className='vote-row';
    var av=document.createElement('span');av.className='av';
    var a=AV[row.model]||AV.mov;
    av.style.background=a[1];av.textContent=(row.model||'?').slice(0,2).toUpperCase();
    var nm=document.createElement('span');nm.className='nm';nm.textContent=row.model||'';
    var op=document.createElement('span');op.style.color='var(--ink-3)';op.textContent=row.opinion||'';
    var st=document.createElement('span');st.className='st';
    var okc=document.createElement('span');okc.className='ok';okc.textContent='✓';st.appendChild(okc);
    vr.appendChild(av);vr.appendChild(nm);vr.appendChild(op);vr.appendChild(st);
    vc.appendChild(vr);
  });
  v.appendChild(vc);
  push(id,v);
}

/* ---------- 执行计划卡片 (审批 → 执行 → 交付) ---------- */
function renderCouncilPlan(id,steps,gen){
  var room=ROOMS.find(function(r){return r.id===id;});
  var autoExec=room&&room.autoExec;
  var p=document.createElement('div');p.className='msg wide';
  var pc=document.createElement('div');pc.className='plan-card';
  var phd=document.createElement('div');phd.className='ph';phd.textContent='PLAN · '+steps.length+' 步';pc.appendChild(phd);
  var pb=document.createElement('div');pb.className='pb';
  steps.forEach(function(s){
    var li=document.createElement('li');
    li.textContent=(typeof s==='string')?s:(s.desc||s.action||JSON.stringify(s));
    pb.appendChild(li);
  });
  pc.appendChild(pb);
  var pf=document.createElement('div');pf.className='pf';
  var bAp=document.createElement('button');bAp.className='btn btn-acc';bAp.textContent=t('plan.approve');
  var bRj=document.createElement('button');bRj.className='btn btn-ghost';bRj.textContent=t('plan.reject');
  bAp.addEventListener('click',function(){
    bAp.disabled=true;bRj.disabled=true;bAp.textContent=t('plan.approved');
    ev('批准方案 → 移交执行');
    executeCouncilSteps(id,steps,gen);
  });
  bRj.addEventListener('click',function(){
    bAp.disabled=true;bRj.disabled=true;bRj.textContent=t('plan.rejected');
    ev('驳回方案 → 议会重新讨论');setPhase(id,'讨论中');
  });
  pf.appendChild(bAp);pf.appendChild(bRj);pc.appendChild(pf);
  p.appendChild(pc);
  push(id,p);
  /* 自动执行模式: 直接跑 */
  if(autoExec){bAp.click();}
}

/* ---------- 逐步执行 (复用 toolNode) ---------- */
function executeCouncilSteps(id,steps,gen){
  var alive=function(){return genCounter===gen&&curRoomId===id;};
  setPhase(id,'执行中');
  push(id,mkMsg({t:'sys',h:t('plan.executing')}));
  var produced=[];
  var i=0;
  function next(){
    if(!alive())return;
    if(i>=steps.length){
      /* 全部完成 → 交付物卡片 */
      renderDeliverCard(id,produced);
      setPhase(id,'已交付');
      var room=ROOMS.find(function(r){return r.id===id;});
      if(room){room.last=t('plan.delivered');renderRooms();persistRooms();}
      ev('方案执行完毕 · '+produced.length+' 个产出');
      return;
    }
    var step=steps[i];i++;
    var t0=Date.now();
    /* 字符串步骤 → 当作设备指令/AI 提示; 对象步骤 → 按 action 分发 */
    if(typeof step==='string'){
      var out=B.cmd(step);
      var dur=((Date.now()-t0)/1000).toFixed(2)+'s';
      var ok=out.indexOf('❌')!==0&&out.indexOf('⚠')!==0;
      push(id,toolNode('exec',step,dur,esc(out)+'\n<span class="'+(ok?'ok-line':'err-line')+'">'+(ok?'exit 0':'exit 1')+'</span>'));
      setTimeout(next,300);
    }else if(step.action==='file.write'){
          /* P1: AI 写文件前强制预览，用户确认才落盘 (CONTRACT_ARCH §5) */
          var fpath=step.args.path||'untitled';
          var fcontent=step.args.content||'';
          var card=document.createElement('div');card.className='msg wide';
          var wrap=document.createElement('div');wrap.className='deliver-card';
          wrap.style.borderLeftColor='#F2DFC2';
          var ic=document.createElement('span');ic.className='ic';ic.textContent='W';
          var info=document.createElement('div');info.style.flex='1';info.style.minWidth='0';
          var tt=document.createElement('div');tt.className='tt';tt.textContent=fpath;
          var ss=document.createElement('div');ss.className='ss';
          ss.textContent=fcontent.length+' 字符 · 待确认';
          info.appendChild(tt);info.appendChild(ss);
          var pre=document.createElement('pre');
          pre.style.cssText='margin:8px 0 0;padding:8px;background:var(--code-bg);border-radius:8px;font-size:10px;line-height:1.6;max-height:120px;overflow:auto;white-space:pre-wrap;word-break:break-word;width:100%;';
          pre.textContent=fcontent.length>2000?fcontent.slice(0,2000)+'\n…(截断预览)':fcontent;
          var btnRow=document.createElement('div');
          btnRow.style.cssText='display:flex;gap:8px;margin-top:8px;';
          var bOk=document.createElement('button');bOk.className='btn btn-acc';bOk.textContent='确认写入';
          var bSkip=document.createElement('button');bSkip.className='btn btn-ghost';bSkip.textContent='跳过';
          btnRow.appendChild(bOk);btnRow.appendChild(bSkip);
          wrap.appendChild(ic);wrap.appendChild(info);
          card.appendChild(wrap);card.appendChild(pre);card.appendChild(btnRow);
          push(id,card);
          var settled=false;
          function settle(action){
            if(settled)return;settled=true;
            bOk.disabled=true;bSkip.disabled=true;
            var dur2=((Date.now()-t0)/1000).toFixed(2)+'s';
            if(action==='write'){
              var res=B.saveWorkFile(id,fpath,fcontent,'mov');
              ss.textContent=fcontent.length+' 字符 · '+(res.ok?'已写入':'写入失败');
              wrap.style.borderLeftColor=res.ok?'var(--ok-dot)':'#e55';
              push(id,toolNode('file.write',fpath,dur2,esc(res.ok?('已写入 '+fpath):(res.error||'写入失败'))+'\n<span class="'+(res.ok?'ok-line':'err-line')+'">'+(res.ok?'exit 0':'exit 1')+'</span>'));
              if(res.ok)produced.push(fpath);
            }else{
              ss.textContent=fcontent.length+' 字符 · 已跳过';
              wrap.style.borderLeftColor='var(--ink-4)';
            }
            setTimeout(next,300);
          }
          bOk.addEventListener('click',function(){settle('write');});
          bSkip.addEventListener('click',function(){settle('skip');});
    }else{
      /* 其他 action → 走设备指令通道 */
      var cmdText=step.action+(step.args&&step.args.text?' '+step.args.text:'');
      var out2=B.cmd(cmdText);
      var dur3=((Date.now()-t0)/1000).toFixed(2)+'s';
      var ok2=out2.indexOf('❌')!==0&&out2.indexOf('⚠')!==0;
      push(id,toolNode(step.action,JSON.stringify(step.args||{}),dur3,esc(out2)+'\n<span class="'+(ok2?'ok-line':'err-line')+'">'+(ok2?'exit 0':'exit 1')+'</span>'));
      setTimeout(next,300);
    }
  }
  next();
}

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
  push(id,mkMsg({t:'sys',h:t('plan.reviewHint')}));
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
