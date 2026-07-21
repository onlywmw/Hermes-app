/* ============================================================
   app-files.js — 文件子 tab + 预览 + 新建文件 Sheet
   (从 app.js 拆出, DESIGN_POLISH #2)
   ============================================================ */

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
