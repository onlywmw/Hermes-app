/* ============================================================
   render.js — DOM 渲染: 房间列表 / 消息 / 视图切换 / 阶段
   ============================================================ */

/* ---------- 房间列表渲染 · Apple grouped ---------- */
function avstack(r){var s='<span class="avstack">';r.members.forEach(function(m){var a=AV[m]||AV.hermes;s+='<i style="background:'+a[1]+'">'+a[0]+'</i>';});return s+'</span>';}
function roomCard(r){
  var bc=PHASE_BADGE[r.phase]||'off';
  return '<div class="room" data-room="'+r.id+'">'
    +'<span class="udot'+(r.unread?' show':'')+'"></span>'
    +'<div class="r1">'+avstack(r)+'<b>'+esc(r.name)+'</b><time>'+esc(r.time)+'</time></div>'
    +'<div class="r2"><span class="mini-tag '+(r.mode==='council'?'council':'')+'">'+(r.mode==='council'?'council · '+r.members.length+' AI':'单聊 · hermes')+'</span><span class="badge '+bc+'"><span class="dot"></span>'+esc(r.phase)+'</span></div>'
    +'<div class="r3">'+esc(r.last)+'</div></div>';
}
function renderRooms(){
  var active=ROOMS.filter(function(r){return r.phase!=='已归档';});
  var archived=ROOMS.filter(function(r){return r.phase==='已归档';});
  var h='';
  if(active.length){
    h+='<div class="glabel">'+t('rooms.active')+'</div><div class="glist">';
    active.forEach(function(r){h+=roomCard(r);});
    h+='</div>';
  }
  if(archived.length){
    h+='<div class="glabel">'+t('rooms.archived')+'</div><div class="glist">';
    archived.forEach(function(r){h+=roomCard(r);});
    h+='</div>';
  }
  $('roomList').innerHTML=h;
  /* 大标题副标题: N 个项目 · M 个团队在讨论 */
  var councils=active.filter(function(r){return r.mode==='council';}).length;
  if($('roomsSub'))$('roomsSub').innerHTML='<b>'+active.length+'</b> '+t('rooms.projects')+' · <b>'+councils+'</b> '+t('rooms.councils');
  document.querySelectorAll('#roomList .room').forEach(function(el){
    el.addEventListener('click',function(){enterRoom(el.getAttribute('data-room'));});
  });
  bindRoomListLongPress();
  $('ndChat').classList.toggle('show',ROOMS.some(function(r){return r.unread;}));
}

/* ---------- 视图切换 ---------- */
function showView(id){document.querySelectorAll('.view').forEach(function(v){v.classList.toggle('act',v.id===id);});}
function setTab(t){
  curTab=t;
  document.querySelectorAll('.bnav button').forEach(function(b){b.classList.toggle('on',b.getAttribute('data-tab')===t);});
  if(t==='chat'){showView(curRoomId?'view-room':'view-rooms');}
  else{showView('view-'+t);}
  if(t==='run'){refreshRuntime();}
  if(t==='board'){initBoardIfNeeded();}
}
document.querySelectorAll('.bnav button').forEach(function(b){b.addEventListener('click',function(){setTab(b.getAttribute('data-tab'));ev('切换 Tab → '+b.getAttribute('data-tab'));});});

/* ---------- 消息渲染 ---------- */
/* P1-8: 支持字符串 key (旧 ATT) 和文件对象 (新 pickFile) */
function attName(a){return typeof a==='string'?(ATT[a]?ATT[a].n:a):(a&&a.name?a.name:'文件');}
function formatBytes(b){if(b<1024)return b+'B';if(b<1048576)return (b/1024).toFixed(1)+'KB';return (b/1048576).toFixed(1)+'MB';}
function attHtml(a){
  if(typeof a==='string'&&ATT[a]){var s=ATT[a];return '<div class="att">'+s.ic+'<div><div class="an">'+s.n+'</div><div class="am">'+s.m+'</div></div></div>';}
  var name=attName(a),size=a&&a.size?formatBytes(a.size):'';
  return '<div class="att"><span class="fic">F</span><div><div class="an">'+esc(name)+'</div><div class="am">'+esc(size)+'</div></div></div>';
}
function push(roomId,node,data){
  var room=ROOMS.find(function(r){return r.id===roomId;});
  if(!room)return;
  room.msgs=room.msgs||[];room.msgs.push(node);
  if(!data&&node._md){data=node._md;}
  else if(!data&&node.classList&&node.classList.contains('wide')){
    var vc=node.querySelector('.vote-card');
    var pc2=node.querySelector('.plan-card');
    var dc=node.querySelector('.deliver-card');
    var tc=node.querySelector('.toolcall');
    if(vc){data={t:'vote',h:vc.outerHTML};}
    else if(pc2){data={t:'plan',h:pc2.outerHTML};}
    else if(dc){data={t:'deliver',h:dc.outerHTML,tt:dc.querySelector('.tt')?dc.querySelector('.tt').textContent:''};}
    else if(tc){data={t:'tool',h:tc.outerHTML};}
  }
  if(data){room.msgData=room.msgData||[];room.msgData.push(data);}
  if(curRoomId===roomId){var b=$('chatBody');b.appendChild(node);b.scrollTop=b.scrollHeight;}
  persistRooms();
}
function rebuildMsgs(r){
  r.msgs=[];
  (r.msgData||[]).forEach(function(d){
    if(d.t==='tool'){
      var td=document.createElement('div');td.className='msg wide';td.innerHTML=d.h;
      var th=td.querySelector('.th');
      if(th){th.addEventListener('click',function(){td.querySelector('.toolcall').classList.toggle('open');});}
      r.msgs.push(td);
    }else if(d.t==='vote'){
      var vd=document.createElement('div');vd.className='msg wide';vd.innerHTML=d.h;
      r.msgs.push(vd);
    }else if(d.t==='plan'){
      var pd=document.createElement('div');pd.className='msg wide';pd.innerHTML=d.h;
      r.msgs.push(pd);
    }else if(d.t==='deliver'){
      var dd=document.createElement('div');dd.className='msg wide';dd.innerHTML=d.h;
      var db=dd.querySelector('.btn');if(db){db.addEventListener('click',function(){B.toast(d.tt||'交付物');});}
      r.msgs.push(dd);
    }else{
      r.msgs.push(mkMsg(d));
    }
  });
}
function showTyping(roomId,node){
  if(curRoomId===roomId){var b=$('chatBody');b.appendChild(node);b.scrollTop=b.scrollHeight;}
}
function killTyping(node){if(node&&node.parentNode)node.parentNode.removeChild(node);}
function mkMsg(m){
  var d=document.createElement('div');
  if(m.t==='sys'){d.className='sysline';d.innerHTML=m.h;d._md=m;return d;}
  if(m.t==='tool'){
    d.className='msg wide';d.innerHTML=m.h;
    var th=d.querySelector('.th');
    if(th){th.addEventListener('click',function(){d.querySelector('.toolcall').classList.toggle('open');});}
    d._md=m;return d;
  }
  var me=m.me,a=AV[m.who]||AV.hermes;
  d.className='msg '+(me?'user':'agent');
  var role=m.role?' <span class="role">'+m.role+'</span>':'';
  var inner=(m.h||'')+(m.att?attHtml(m.att):'')+(m.caret?'<span class="caret"></span>':'');
  d.innerHTML='<div class="who"><i class="av" style="background:'+a[1]+'">'+a[0]+'</i>'+esc(m.who).toUpperCase()+role+'</div><div class="bubble">'+inner+'</div>';
  d._md=m;
  return d;
}
function setPhase(roomId,p){
  var r=ROOMS.find(function(x){return x.id===roomId;});if(!r)return;r.phase=p;
  if(curRoomId===roomId){$('roomPhaseBadge').innerHTML=phaseBadge(p);}
  renderRooms();persistRooms();ev('['+r.name.split(' · ')[0]+'] 阶段 → '+p);
}
function phaseBadge(p){var bc=PHASE_BADGE[p]||'off';return '<span class="badge '+bc+'"><span class="dot"></span>'+esc(p)+'</span>';}

/* ---------- 工具调用卡片 (chat.js runDeviceCommand + council.js 共用) ---------- */
function toolNode(toolName,args,dur,outHtml){
  var d=document.createElement('div');d.className='msg wide';
  d.innerHTML='<div class="toolcall"><div class="th"><span>▸</span><b>'+esc(toolName)+'</b><span>'+esc(args)+'</span><span class="dur">'+esc(dur)+'</span><span class="car">▶</span></div><div class="tb"><pre>'+outHtml+'</pre></div></div>';
  d.querySelector('.th').addEventListener('click',function(){d.querySelector('.toolcall').classList.toggle('open');});
  return d;
}

/* ---------- S7: 文件卡片 (讨论区文件引用) ---------- */
function mkFileCard(fileName,filePath,size,author,roomId){
  var ext=fileName.split('.').pop().toUpperCase().slice(0,4);
  var d=document.createElement('div');d.className='msg wide';
  d.innerHTML='<div class="file-card">'
    +'<div class="fc-icon">'+esc(ext||'F')+'</div>'
    +'<div class="fc-info"><div class="fc-name">'+esc(fileName)+'</div>'
    +'<div class="fc-meta">'+esc(size)+' · '+esc(author)+'</div></div>'
    +'<div class="fc-actions"><span class="fc-btn" data-action="view">'+t('files.view')+'</span></div></div>';
  d.querySelector('[data-action="view"]').addEventListener('click',function(){
    var res=B.readFile(roomId,filePath);
    if(res.ok){showFilePreview(fileName,res.content);}
    else{B.toast(res.error||'');}
  });
  d._md={t:'file',h:d.querySelector('.file-card').outerHTML,name:fileName};
  return d;
}
