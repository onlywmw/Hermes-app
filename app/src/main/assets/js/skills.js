/* ============================================================
   skills.js — 技能列表 + 搜索 + 长按移除 (运行页内)
   ============================================================ */
function renderSkillPage(){
  var skills=B.listSkills();
  var totalUses=0;
  skills.forEach(function(s){totalUses+=s.uses;});
  var head=$('skillHead');
  if(head)head.textContent=t('skill.title')+' · '+skills.length+' · '+totalUses+' '+t('skill.uses');
  renderSkillList(skills,'');
}
function renderSkillList(skills,filter){
  var f=(filter||'').toLowerCase();
  var filtered=skills.filter(function(s){
    if(!f)return true;
    return s.name.toLowerCase().indexOf(f)>=0||s.desc.toLowerCase().indexOf(f)>=0;
  });
  var h='';
  filtered.forEach(function(s){
    var badge=s.status==='修订中'||s.status==='Revising'?'run':'ok';
    var badgeText=badge==='run'?(t('skill.revisions')+' ×'+s.revisions):(s.status==='稳定'||s.status==='Stable'?t('skill.stable'):s.status);
    var lastUsed=s.lastUsed?timeAgo(s.lastUsed):t('ago.never');
    var src=s.source==='自动生成'||s.source==='Auto'?t('skill.auto'):t('skill.installed');
    h+='<div class="skill" data-skill="'+esc(s.id)+'">'
      +'<div class="r1"><b>'+esc(s.name)+'</b><span class="badge '+badge+'"><span class="dot"></span>'+esc(badgeText)+'</span></div>'
      +'<p>'+esc(s.desc)+'</p>'
      +'<div class="r3"><span>'+t('skill.source')+' <b>'+esc(src)+'</b></span><span>'+t('skill.uses')+' <b>'+s.uses+' '+t('rt.times')+'</b></span><span>'+t('skill.lastUsed')+' <b>'+esc(lastUsed)+'</b></span></div>'
      +'</div>';
  });
  if(filtered.length===0)h='<div style="margin:0 12px 8px;padding:16px;text-align:center;font-family:var(--font-mono);font-size:10px;color:var(--ink-4)">'+t('skill.none')+'</div>';
  $('skillList').innerHTML=h;
  /* 绑定长按移除 + 点击触发 */
  document.querySelectorAll('#skillList .skill').forEach(function(el){
    var sid=el.getAttribute('data-skill');
    el.addEventListener('click',function(){
      B.recordSkill(sid);
      B.toast(t('skill.triggered'));
      renderSkillPage();
      ev('触发技能 '+sid);
    });
    bindLongPress(el,{
      text:t('skill.remove'),
      exec:function(){
        var res=B.deleteSkill(sid);
        if(res.ok){B.toast(t('skill.removed'));renderSkillPage();}
        ev('移除技能 '+sid);
      }
    });
  });
}
function timeAgo(ts){
  var diff=Date.now()-ts;
  if(diff<3600000)return Math.max(1,Math.floor(diff/60000))+t('ago.m');
  if(diff<86400000)return Math.floor(diff/3600000)+t('ago.h');
  return Math.floor(diff/86400000)+t('ago.d');
}
