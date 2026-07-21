/* ============================================================
   board.js — 看板 tab: 全屏 iframe + 底部触发条 + 应用选择面板
   ============================================================ */
var _boardApps=[];
var _boardActive=null;
var _boardHideTimer=null;
var _boardInited=false;

var DEFAULT_BOARD_APPS=[
  {id:'music',name:'音乐',icon:'音',source:'board-apps/music.html',builtin:true},
  {id:'reader',name:'阅读',icon:'读',source:'board-apps/reader.html',builtin:true},
  {id:'fitness',name:'健身',icon:'健',source:'board-apps/fitness.html',builtin:true},
  {id:'notes',name:'笔记',icon:'记',source:'board-apps/notes.html',builtin:true}
];

function initBoardIfNeeded(){
  if(!_boardInited){
    _boardInited=true;
    var saved=localStorage.getItem('mov_board_apps_v1');
    _boardApps=saved?JSON.parse(saved):DEFAULT_BOARD_APPS.slice();
    if(_boardApps.length>0&&!_boardActive){
      _boardActive=_boardApps[0].id;
      loadBoardApp(_boardActive);
    }
  }
  showBoardTrigger();
}

function saveBoardApps(){
  localStorage.setItem('mov_board_apps_v1',JSON.stringify(_boardApps));
}

function loadBoardApp(id){
  var app=_boardApps.find(function(a){return a.id===id;});
  if(!app)return;
  /* P0-3: URL 协议校验 — 只允许 http/https 和内置 board-apps */
  if(app.type==='url'&&!isValidBoardUrl(app.source)){
    B.toast(t('board.invalidUrl')||'URL 无效');
    return;
  }
  $('boardFrame').src=app.source;
  $('boardTriggerIcon').textContent=app.icon;
  $('boardTriggerName').textContent=app.name;
  _boardActive=id;
}
/* P0-3: 校验看板 URL */
function isValidBoardUrl(url){
  if(!url)return false;
  if(url.indexOf('board-apps/')===0)return true; /* 内置应用 */
  try{
    var u=new URL(url);
    return u.protocol==='https:'||u.protocol==='http:';
  }catch(e){return false;}
}

function showBoardTrigger(){
  var el=$('boardTrigger');
  el.classList.remove('hidden');
  clearTimeout(_boardHideTimer);
  _boardHideTimer=setTimeout(function(){el.classList.add('hidden');},3000);
}

function openBoardPanel(){
  openSheetExclusive('boardPanelMask','boardPanel');
  renderBoardPanel();
  clearTimeout(_boardHideTimer);
}

function closeBoardPanel(){
  $('boardPanelMask').classList.remove('open');
  $('boardPanel').classList.remove('open');
  showBoardTrigger();
}

function renderBoardPanel(){
  var h='';
  _boardApps.forEach(function(app){
    var active=_boardActive===app.id?' active':'';
    h+='<div class="board-app-card'+active+'" data-app="'+esc(app.id)+'">'
      +'<span class="ba-icon">'+esc(app.icon)+'</span>'
      +'<span class="ba-name">'+esc(app.name)+'</span></div>';
  });
  h+='<div class="board-app-card" id="boardPanelAdd">'
    +'<span class="ba-icon">＋</span>'
    +'<span class="ba-name">'+t('board.add')+'</span></div>';
  $('boardGrid').innerHTML=h;

  document.querySelectorAll('.board-app-card[data-app]').forEach(function(el){
    el.addEventListener('click',function(){
      var id=el.getAttribute('data-app');
      loadBoardApp(id);
      closeBoardPanel();
    });
    var app=_boardApps.find(function(a){return a.id===el.getAttribute('data-app');});
    if(app&&!app.builtin){
      bindLongPress(el,{
        text:t('board.remove'),
        exec:function(){
          _boardApps=_boardApps.filter(function(a){return a.id!==app.id;});
          saveBoardApps();
          if(_boardActive===app.id){_boardActive=null;$('boardFrame').src='';}
          renderBoardPanel();
          B.toast(t('board.removed'));
        }
      });
    }
  });
  $('boardPanelAdd').addEventListener('click',openBoardAddSheet);
}

/* 添加应用 sheet */
function openBoardAddSheet(){
  openSheetExclusive('boardAddMask','boardAddSheet');
  $('boardAddName').value='';
  $('boardAddUrl').value='';
  $('boardAddName').focus();
}
function closeBoardAddSheet(){
  $('boardAddMask').classList.remove('open');
  $('boardAddSheet').classList.remove('open');
}
function confirmBoardAdd(){
  var name=$('boardAddName').value.trim();
  var url=$('boardAddUrl').value.trim();
  if(!name){B.toast(t('board.addName'));return;}
  if(!url){B.toast(t('board.addUrl'));return;}
  var icon=name.charAt(0);
  var id='app_'+Date.now();
  _boardApps.push({id:id,name:name,icon:icon,source:url,builtin:false,type:'url'});
  saveBoardApps();
  renderBoardPanel();
  closeBoardAddSheet();
  B.toast(t('board.added')+' '+name);
  ev('添加看板应用 '+name);
}

/* 触碰底部区域唤醒触发条 */
document.addEventListener('touchstart',function(e){
  if(curTab!=='board')return;
  var y=e.touches[0].clientY;
  var h=window.innerHeight;
  if(y>h-80)showBoardTrigger();
},{passive:true});
