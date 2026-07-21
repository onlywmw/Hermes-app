/* ============================================================
   files.js — 房间文件 tab: 产出 / 资料 / 归档 / 模板 四视图
   存储系统 v3.0 — 五种存储, 五种体验
   ============================================================ */
var _filesPath='';
var _storageType='work'; /* work | inbox | archive | template */

/* ---------- 子类型切换 ---------- */
function setStorageType(type){
  _storageType=type;
  document.querySelectorAll('.storage-tab').forEach(function(el){
    el.classList.toggle('on',el.getAttribute('data-stype')===type);
  });
  renderStorageView();
}

function renderStorageView(){
  if(_storageType==='work')renderWorkFiles();
  else if(_storageType==='inbox')renderInboxFiles();
  else if(_storageType==='archive')renderArchiveFiles();
  else if(_storageType==='template')renderTemplateFiles();
}

/* ---------- 产出视图: 按时间分组 ---------- */
function renderWorkFiles(){
  var res=B.listWorkFiles(curRoomId);
  var files=(res.files||[]).filter(function(f){return !f.isDir;});
  var h='';
  if(files.length===0){
    h='<div class="sysline">'+t('st.workEmpty')+'</div>';
  }else{
    /* 按日期分组 */
    var groups={};
    files.forEach(function(f){
      var d=new Date(f.modified);
      var key=d.getFullYear()+'-'+(d.getMonth()+1)+'-'+d.getDate();
      var today=new Date();
      var tkey=today.getFullYear()+'-'+(today.getMonth()+1)+'-'+today.getDate();
      var label=key===tkey?t('st.today'):key;
      if(!groups[label])groups[label]=[];
      groups[label].push(f);
    });
    Object.keys(groups).forEach(function(label){
      h+='<div class="st-group-head">'+esc(label)+'</div>';
      groups[label].forEach(function(f){
        var ext=f.name.split('.').pop().toUpperCase().slice(0,4);
        h+='<div class="st-card" data-file="'+esc(f.name)+'" data-type="work">'
          +'<span class="fic">'+esc(ext||'F')+'</span>'
          +'<div class="st-info"><b>'+esc(f.name)+'</b>'
          +'<span>'+formatFileSize(f.size)+' · '+timeAgo(f.modified)+'</span></div>'
          +'<span class="st-ver" data-act="versions">'+t('st.versions')+'</span>'
          +'</div>';
      });
    });
    h+='<div class="st-summary">'+files.length+' '+t('st.fileCount')+'</div>';
  }
  $('storageList').innerHTML=h;
  bindStorageCards();
}

/* ---------- 资料视图: 网格 ---------- */
function renderInboxFiles(){
  var res=B.listInboxFiles(curRoomId);
  var files=(res.files||[]).filter(function(f){return !f.isDir;});
  var h='';
  if(files.length===0){
    h='<div class="sysline">'+t('st.inboxEmpty')+'</div>';
  }else{
    h+='<div class="st-grid">';
    files.forEach(function(f){
      var ext=f.name.split('.').pop().toUpperCase().slice(0,4);
      var isImg=/\.(png|jpg|jpeg|gif|webp|svg)$/i.test(f.name);
      h+='<div class="st-gcard" data-file="'+esc(f.name)+'" data-type="inbox">'
        +'<div class="st-gicon'+(isImg?' img':'')+'">'+esc(isImg?'IMG':ext)+'</div>'
        +'<b>'+esc(f.name)+'</b>'
        +'<span>'+formatFileSize(f.size)+' · '+timeAgo(f.modified)+'</span>'
        +'</div>';
    });
    h+='</div>';
    h+='<div class="st-summary">'+files.length+' '+t('st.refCount')+'</div>';
  }
  $('storageList').innerHTML=h;
  bindStorageCards();
}

/* ---------- 归档视图: 按来源分组 ---------- */
function renderArchiveFiles(){
  var res=B.listArchiveFiles(curRoomId);
  var sources=res.sources||[];
  var h='';
  if(sources.length===0){
    h='<div class="sysline">'+t('st.archiveEmpty')+'</div>';
  }else{
    sources.forEach(function(src){
      h+='<div class="st-archive-src">'
        +'<div class="st-src-head"><b>'+esc(src.source)+'</b><span>'+src.count+' '+t('st.archiveCount')+'</span></div>';
      (src.files||[]).slice(0,5).forEach(function(f){
        h+='<div class="st-card" data-file="'+esc(src.source+'/'+f.name)+'" data-type="archive">'
          +'<span class="fic">AR</span>'
          +'<div class="st-info"><b>'+esc(f.name)+'</b>'
          +'<span>'+formatFileSize(f.size)+' · '+timeAgo(f.modified)+'</span></div>'
          +'</div>';
      });
      if(src.count>5)h+='<div class="st-more">… '+t('st.morePrefix')+' '+(src.count-5)+' '+t('st.moreSuffix')+'</div>';
      h+='</div>';
    });
  }
  $('storageList').innerHTML=h;
  bindStorageCards();
}

/* ---------- 模板视图 ---------- */
function renderTemplateFiles(){
  var res=B.listTemplates();
  var files=(res.files||[]).filter(function(f){return !f.isDir;});
  var h='';
  if(files.length===0){
    h='<div class="sysline">'+t('st.templateEmpty')+'</div>';
  }else{
    files.forEach(function(f){
      h+='<div class="st-card" data-file="'+esc(f.name)+'" data-type="template">'
        +'<span class="fic">TP</span>'
        +'<div class="st-info"><b>'+esc(f.name)+'</b>'
        +'<span>'+formatFileSize(f.size)+' · '+timeAgo(f.modified)+'</span></div>'
        +'<span class="st-ver" data-act="use">'+t('st.use')+'</span>'
        +'</div>';
    });
  }
  h+='<div class="st-actions">'
    +'<span class="st-act-btn" id="btnNewTemplate">'+t('st.newTemplate')+'</span>'
    +'</div>';
  $('storageList').innerHTML=h;
  bindStorageCards();
  var btn=$('btnNewTemplate');
  if(btn)btn.addEventListener('click',function(){openTemplateSheet();});
}

/* ---------- 卡片事件绑定 ---------- */
function bindStorageCards(){
  document.querySelectorAll('#storageList .st-card, #storageList .st-gcard').forEach(function(el){
    var fname=el.getAttribute('data-file');
    var ftype=el.getAttribute('data-type');
    el.addEventListener('click',function(){
      /* 点版本按钮 */
      if(event.target.getAttribute('data-act')==='versions'){
        openVersionOverlay(fname);
        return;
      }
      /* 点使用模板 */
      if(event.target.getAttribute('data-act')==='use'){
        useTemplateAction(fname);
        return;
      }
      /* 预览文件 */
      var path=ftype==='work'?'work/'+fname
        :ftype==='inbox'?'inbox/'+fname
        :ftype==='archive'?'archive/'+fname
        :'templates/'+fname;
      if(ftype==='template'){
        var res=B.readFile('__templates__',fname);
        /* 模板不在房间内, 用 listTemplates 读不到内容, 简化: 提示 */
        B.toast(t('st.templateHint'));
        return;
      }
      var res2=B.readFile(curRoomId,path);
      if(res2.ok)showFilePreview(fname,res2.content);
      else B.toast(res2.error||t('files.loadFail'));
    });
    /* 长按删除 (产出/资料) */
    if(ftype==='work'||ftype==='inbox'){
      bindLongPress(el,{
        text:t('files.delete'),
        exec:function(){
          var path=(ftype==='work'?'work/':'inbox/')+fname;
          var r=B.deleteFile(curRoomId,path);
          if(r.ok){B.toast(t('files.deleted'));renderStorageView();}
          else B.toast(r.message||'');
        }
      });
    }
  });
}

/* ---------- 版本历史 overlay ---------- */
function openVersionOverlay(fname){
  var res=B.listVersions(curRoomId,fname);
  var versions=res.versions||[];
  var h='<div class="ver-current">'+t('st.current')+': '+esc(fname)+'</div>';
  if(versions.length===0){
    h+='<div class="sysline">'+t('st.noVersions')+'</div>';
  }else{
    versions.sort(function(a,b){return b.timestamp.localeCompare(a.timestamp);});
    versions.forEach(function(v){
      h+='<div class="ver-item" data-snap="'+esc(v.name)+'">'
        +'<span class="ver-ts">'+esc(v.timestamp.replace('_',' · '))+'</span>'
        +'<span class="ver-size">'+formatFileSize(v.size)+'</span>'
        +'<span class="ver-restore">'+t('st.restore')+'</span>'
        +'</div>';
    });
  }
  $('versionBody').innerHTML=h;
  $('versionName').textContent=fname;
  $('versionMask').style.display='';
  $('versionOverlay').style.display='';
  document.querySelectorAll('#versionBody .ver-restore').forEach(function(el){
    el.addEventListener('click',function(){
      var snap=el.parentElement.getAttribute('data-snap');
      var r=B.restoreVersion(curRoomId,fname,snap);
      if(r.ok){B.toast(t('st.restored'));closeVersionOverlay();renderWorkFiles();}
      else B.toast(r.error||'');
    });
  });
}
function closeVersionOverlay(){
  $('versionMask').style.display='none';
  $('versionOverlay').style.display='none';
}

/* ---------- 使用模板 ---------- */
function useTemplateAction(tname){
  var target=prompt(t('st.templateTarget'),tname.replace('.md','')+'-copy.md');
  if(!target)return;
  var r=B.useTemplate(tname,curRoomId,target);
  if(r.ok){B.toast(r.message||t('st.templateUsed'));setStorageType('work');}
  else B.toast(r.error||'');
}

/* ---------- 新建模板 sheet ---------- */
function openTemplateSheet(){
  $('templateMask').classList.add('open');
  $('templateSheet').classList.add('open');
  $('templateName').value='';
  $('templateContent').value='';
  $('templateName').focus();
}
function closeTemplateSheet(){
  $('templateMask').classList.remove('open');
  $('templateSheet').classList.remove('open');
}
function confirmTemplate(){
  var name=$('templateName').value.trim();
  if(!name){B.toast(t('st.templateNeedName'));return;}
  var content=$('templateContent').value;
  var r=B.saveTemplate(name,content);
  if(r.ok){closeTemplateSheet();B.toast(name+' '+t('files.created'));renderTemplateFiles();}
  else B.toast(r.error||'');
}

/* ---------- 文件预览 overlay (复用) ---------- */
function showFilePreview(name,content){
  $('previewName').textContent=name;
  $('previewBody').textContent=content;
  $('previewMask').style.display='';
  $('previewOverlay').style.display='';
}
function closeFilePreview(){
  $('previewMask').style.display='none';
  $('previewOverlay').style.display='none';
}

function formatFileSize(bytes){
  if(bytes<1024)return bytes+'B';
  if(bytes<1048576)return (bytes/1024).toFixed(1)+'KB';
  return (bytes/1048576).toFixed(1)+'MB';
}

/* 文件 tab 的 + 按钮: 上传文件到房间资料 */
function fileFabAction(roomId){
  B.pickFile(function(info){
    if(!info)return;
    B.toast(t('files.uploaded')+' '+info.name);
    renderStorageView();
  },roomId);
}
