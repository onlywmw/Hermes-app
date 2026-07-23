/* ============================================================
   render.js — DOM 渲染: 房间列表 / 消息 / 视图切换 / 阶段
   P0-3: XSS 修复 — sanitize + textContent 优先
   ============================================================ */

/* P0-3: HTML 消毒 — 白名单实现: DOMParser 解析后递归重建, 只放行安全标签与 class 属性 */
function sanitize(html){
  if(!html)return '';
  /* 标签白名单: 仅允许纯展示类标签 */
  var ALLOWED={b:1,i:1,em:1,strong:1,code:1,pre:1,span:1,div:1,br:1,p:1,ul:1,ol:1,li:1,small:1,sub:1,sup:1};
  /* 危险标签: 连同其内容整棵丢弃(不解包) */
  var DROP={script:1,style:1,iframe:1,object:1,embed:1,form:1,base:1,meta:1,link:1};
  /* DOMParser 以 text/html 解析, 解析出的文档不执行脚本 */
  var doc=new DOMParser().parseFromString(String(html),'text/html');
  var out=document.createElement('div');
  /* 递归遍历: 文本原样保留; 白名单标签重建; 其余标签解包(只保留子节点) */
  function walk(src,dst){
    var nodes=src.childNodes;
    for(var k=0;k<nodes.length;k++){
      var n=nodes[k];
      if(n.nodeType===3){dst.appendChild(document.createTextNode(n.nodeValue));}
      else if(n.nodeType===1){
        var tag=n.tagName.toLowerCase();
        if(DROP[tag])continue;
        if(ALLOWED[tag]){
          var el=document.createElement(tag);
          /* 属性白名单: 只放行 class, 且值只允许字母数字/空格/下划线/连字符 */
          var cls=n.getAttribute('class');
          if(cls&&/^[a-zA-Z0-9 _-]+$/.test(cls))el.setAttribute('class',cls);
          walk(n,el);
          dst.appendChild(el);
        }else{
          walk(n,dst);
        }
      }
    }
  }
  walk(doc.body,out);
  return out.innerHTML;
}
/* P0-3: 安全气泡 — 只保留 <code> 标签，其余走 textContent */
function safeBubble(html){
  var div=document.createElement('div');div.className='bubble';
  if(!html)return div;
  var parts=html.split(/(<code>[\s\S]*?<\/code>)/g);
  parts.forEach(function(part){
    if(/^<code>[\s\S]*?<\/code>$/i.test(part)){
      var code=document.createElement('code');
      code.textContent=part.replace(/^<code>/i,'').replace(/<\/code>$/i,'');
      div.appendChild(code);
    }else if(part){
      div.appendChild(document.createTextNode(part));
    }
  });
  return div;
}

/* ---------- 房间列表渲染 · Apple grouped ---------- */
function avstack(r){var s='<span class="avstack">';var ms=Array.isArray(r.members)?r.members:(r.members&&r.members.ai?r.members.ai:[]);if(ms.length===0){s+='<i style="background:var(--acc-live)">M</i>';}else{ms.forEach(function(m){var a=AV[m]||AV.mov;var col=safeColor(a[1]);s+='<i style="background:'+col+'">'+esc(a[0])+'</i>';});}return s+'</span>';}
/* P0: 颜色白名单 — 只允许 #hex 或字母数字命名色，否则回退默认色 (防 style 注入) */
function safeColor(c){c=String(c||'');return /^(#[0-9a-fA-F]{3,8}|[a-zA-Z0-9]+)$/.test(c)?c:'#f59e0b';}
function roomCard(r){
  var bc=PHASE_BADGE[r.phase]||'off';
  return '<div class="room" data-room="'+r.id+'">'
    +'<span class="udot'+(r.unread?' show':'')+'"></span>'
    +'<div class="r1">'+avstack(r)+'<b>'+esc(r.name)+'</b><time>'+esc(r.time)+'</time></div>'
    +'<div class="r2"><span class="mini-tag '+(r.mode==='council'?'council':'')+'">'+(r.id==='desk'?'单聊 · mov':'agent'+(roomAiMembers(r).length?' · '+roomAiMembers(r).length+' 评审':''))+'</span><span class="badge '+bc+'"><span class="dot"></span>'+esc(r.phase)+'</span></div>'
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
  document.querySelectorAll('#roomList .room').forEach(function(el){
    el.addEventListener('click',function(){
      if(lpSuppressClick())return;
      enterRoom(el.getAttribute('data-room'));
    });
  });
  bindRoomListLongPress();
  $('ndChat').classList.toggle('show',ROOMS.some(function(r){return r.unread;}));
}

/* ---------- 视图切换 ---------- */
function showView(id){if(window.hideMsgActions)hideMsgActions();document.querySelectorAll('.view').forEach(function(v){v.classList.toggle('act',v.id===id);});}
function setTab(t){
  curTab=t;
  document.querySelectorAll('.bnav button').forEach(function(b){b.classList.toggle('on',b.getAttribute('data-tab')===t);});
  if(t==='chat'){showView(curRoomId?'view-room':'view-rooms');}
  else{showView('view-'+t);}
  if(t==='run'){refreshRuntime();}
}
document.querySelectorAll('.bnav button').forEach(function(b){b.addEventListener('click',function(){setTab(b.getAttribute('data-tab'));ev('切换 Tab → '+b.getAttribute('data-tab'));});});

/* ---------- 消息渲染 ---------- */
/* P1-8: 附件为 pickFile 文件对象; 旧持久化的字符串 key 直接显示原文 */
function attName(a){return typeof a==='string'?a:(a&&a.name?a.name:'文件');}
function formatBytes(b){if(b<1024)return b+'B';if(b<1048576)return (b/1024).toFixed(1)+'KB';return (b/1048576).toFixed(1)+'MB';}
function attHtml(a){
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
  /* Fix: 新消息也要绑长按删除 (此前只有 enterRoom 时统一绑, 动态 push 的消息漏绑) */
  if(typeof bindMsgLongPress==='function')bindMsgLongPress(node,roomId);
  persistRooms();
}
function rebuildMsgs(r){
  r.msgs=[];
  (r.msgData||[]).forEach(function(d){
    if(d.t==='tool'){
      var td=document.createElement('div');td.className='msg wide';td.innerHTML=sanitize(d.h);
      var th=td.querySelector('.th');
      if(th){th.addEventListener('click',function(){td.querySelector('.toolcall').classList.toggle('open');});}
      r.msgs.push(td);
    }else if(d.t==='vote'){
      var vd=document.createElement('div');vd.className='msg wide';vd.innerHTML=sanitize(d.h);
      r.msgs.push(vd);
    }else if(d.t==='plan'){
      var pd=document.createElement('div');pd.className='msg wide';pd.innerHTML=sanitize(d.h);
      r.msgs.push(pd);
    }else if(d.t==='deliver'){
      var dd=document.createElement('div');dd.className='msg wide';dd.innerHTML=sanitize(d.h);
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
  if(m.t==='sys'){d.className='sysline';d.innerHTML=sanitize(m.h);d._md=m;return d;}
  if(m.t==='tool'){
    d.className='msg wide';d.innerHTML=sanitize(m.h);
    var th=d.querySelector('.th');
    if(th){th.addEventListener('click',function(){d.querySelector('.toolcall').classList.toggle('open');});}
    d._md=m;return d;
  }
  var me=m.me,a=AV[m.who]||AV.mov;
  d.className='msg '+(me?'user':'agent');
  var role=m.role?' <span class="role">'+esc(m.role)+'</span>':'';
  /* P0-3: who 行用 esc，气泡用 safeBubble */
  var whoDiv=document.createElement('div');whoDiv.className='who';
  whoDiv.innerHTML='<i class="av" style="background:'+esc(a[1])+'">'+esc(a[0])+'</i>'+esc(m.who).toUpperCase()+role;
  d.appendChild(whoDiv);
  d.appendChild(safeBubble(m.h||''));
  if(m.att){var attDiv=document.createElement('div');attDiv.innerHTML=sanitize(attHtml(m.att));while(attDiv.firstChild)d.appendChild(attDiv.firstChild);}
  if(m.caret){var c=document.createElement('span');c.className='caret';d.appendChild(c);}
  d._md=m;
  return d;
}
function setPhase(roomId,p){
  var r=ROOMS.find(function(x){return x.id===roomId;});if(!r)return;r.phase=p;
  if(curRoomId===roomId){$('roomPhaseBadge').innerHTML=phaseBadge(p);}
  renderRooms();persistRooms();ev('['+r.name.split(' · ')[0]+'] 阶段 → '+p);
}
function phaseBadge(p){var bc=PHASE_BADGE[p]||'off';return '<span class="badge '+bc+'"><span class="dot"></span>'+esc(p)+'</span>';}

/* ---------- 工具调用卡片 ---------- */
function toolNode(toolName,args,dur,outHtml){
  var d=document.createElement('div');d.className='msg wide';
  d.innerHTML='<div class="toolcall"><div class="th"><span>▸</span><b>'+esc(toolName)+'</b><span>'+esc(args)+'</span><span class="dur">'+esc(dur)+'</span><span class="car">▶</span></div><div class="tb"><pre>'+outHtml+'</pre></div></div>';
  d.querySelector('.th').addEventListener('click',function(){d.querySelector('.toolcall').classList.toggle('open');});
  return d;
}
