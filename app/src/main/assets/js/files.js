/* ============================================================
   files.js — 房间文件 tab: 产出 / 资料 / 归档 三视图
   存储系统 v3.0 (模板功能已下线)
   ============================================================ */
var _storageType='work'; /* work | inbox | archive */

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
}

/* ---------- 产出视图: 按时间分组 ---------- */
function renderWorkFiles(){
  var res=B.listWorkFiles(curRoomId);
  if(!res.ok){
    $('storageList').innerHTML='<div class="sysline">'+esc(res.error||t('files.loadFail'))+'</div>';
    return;
  }
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
          +(/\.apk$/i.test(f.name)
            ? '<span class="st-ver" data-act="install">安装</span>'
            : '<span class="st-ver" data-act="versions">'+t('st.versions')+'</span>')
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
  if(!res.ok){
    $('storageList').innerHTML='<div class="sysline">'+esc(res.error||t('files.loadFail'))+'</div>';
    return;
  }
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
  if(!res.ok){
    $('storageList').innerHTML='<div class="sysline">'+esc(res.error||t('files.loadFail'))+'</div>';
    return;
  }
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

/* ---------- 卡片事件绑定 ---------- */
function bindStorageCards(){
  document.querySelectorAll('#storageList .st-card, #storageList .st-gcard').forEach(function(el){
    var fname=el.getAttribute('data-file');
    var ftype=el.getAttribute('data-type');
    el.addEventListener('click',function(e){
      if(lpSuppressClick())return;
      /* 点版本按钮 */
      if(e.target.getAttribute('data-act')==='versions'){
        openVersionOverlay(fname);
        return;
      }
      /* APK: 点「安装」直调系统安装器; 点卡片其他位置同样进安装 (不走文本预览) */
      if(/\.apk$/i.test(fname)){
        var ir=B.installApk(curRoomId,fname);
        if(!ir.ok)B.toast(ir.error||'安装失败');
        return;
      }
      /* 预览文件 (Fix: 补 files/ 前缀 — 磁盘布局为 rooms/<id>/files/<type>/) */
      var path=ftype==='work'?'files/work/'+fname
        :ftype==='inbox'?'files/inbox/'+fname
        :'files/archive/'+fname;
      var res2=B.readFile(curRoomId,path);
      if(res2.ok)showFilePreview(fname,res2.content);
      else B.toast(res2.error||t('files.loadFail'));
    });
    /* 长按 → 操作菜单 (发送到桌面 / 删除; 产出/资料) */
    if(ftype==='work'||ftype==='inbox'){
      bindLongPress(el,{
        text:'', /* 操作菜单非危险确认: 直接弹 sheet, 不走 msgActions 确认条 */
        exec:function(){openFileOpsSheet(fname,ftype);}
      });
    }
  });
}

/* ---------- 文件操作 sheet (发送到桌面 / 删除) ---------- */
var _fileOpsTarget=null;
function openFileOpsSheet(fname,ftype){
  _fileOpsTarget={name:fname,type:ftype};
  $('fileOpsName').textContent=fname;
  /* 发送到桌面仅产出 (HtmlViewerActivity 只读 files/work 目录) */
  $('fopsPin').style.display=ftype==='work'?'':'none';
  /* 打包成应用仅产出 .html (PackageBuilder 限 work 区 HTML) */
  $('fopsApk').style.display=(ftype==='work'&&/\.html?$/i.test(fname))?'':'none';
  openSheetExclusive('fileOpsMask','fileOpsSheet');
}
$('btnFileOpsClose').addEventListener('click',function(){closeAllSheets();});
$('fileOpsMask').addEventListener('click',function(){closeAllSheets();});
$('fopsPin').addEventListener('click',function(){
  if(!_fileOpsTarget)return;
  var fname=_fileOpsTarget.name;
  closeAllSheets();
  var r=B.pinFileShortcut(curRoomId,fname,fname);
  if(r.ok)B.toast(t('files.pinRequested'));
  else B.toast(r.error||t('files.pinFail'));
});
/* ---------- 打包成应用 sheet ---------- */
$('fopsApk').addEventListener('click',function(){
  if(!_fileOpsTarget)return;
  var fname=_fileOpsTarget.name;
  $('apkFileName').textContent=fname;
  /* 默认应用名: 文件名去扩展名 (≤16 字符, 原生侧再消毒) */
  $('apkAppName').value=fname.replace(/\.[^.]+$/,'').slice(0,16);
  var btn=$('btnApkBuild');
  btn.disabled=false;btn.textContent=t('pack.start');
  openSheetExclusive('apkMask','apkSheet');
});
$('btnApkClose').addEventListener('click',function(){closeAllSheets();});
$('apkMask').addEventListener('click',function(){closeAllSheets();});
$('btnApkBuild').addEventListener('click',function(){
  if(!_fileOpsTarget)return;
  var fname=_fileOpsTarget.name;
  var appName=$('apkAppName').value.trim();
  var btn=$('btnApkBuild');
  btn.disabled=true;btn.textContent=t('pack.building');
  B.buildApk(curRoomId,fname,appName,function(r){
    btn.disabled=false;btn.textContent=t('pack.start');
    if(r&&r.ok){
      closeAllSheets();
      B.toast(t('pack.done'));
    }else{
      B.toast((r&&r.error)?r.error:t('pack.fail'));
    }
  });
});
$('fopsDelete').addEventListener('click',function(){
  if(!_fileOpsTarget)return;
  var fname=_fileOpsTarget.name,ftype=_fileOpsTarget.type;
  closeAllSheets();
  /* 删除保持长按确认条语义 (危险操作二次确认) */
  showMsgActions(t('files.delete'),function(){
    /* 改用专用桥方法 (内部已定位 files/work|inbox 目录, 避免手拼路径缺 files/ 前缀) */
    var r=(ftype==='work'?B.deleteWorkFile(curRoomId,fname):B.deleteInboxFile(curRoomId,fname));
    if(r.ok){B.toast(t('files.deleted'));renderStorageView();}
    else B.toast(r.error||r.message||'删除失败');
  });
});

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
