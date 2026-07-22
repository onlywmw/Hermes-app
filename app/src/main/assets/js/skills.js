/* ============================================================
   skills.js — 技能 (摘要在 agent 卡片, 详情在弹层)
   ============================================================ */
function renderSkillPage(){
  var skills=B.listSkills();
  var totalUses=0;
  skills.forEach(function(s){totalUses+=s.uses;});
  var el=$('skillSummary');
  if(el){
    el.textContent=skills.length+' · '+totalUses+' '+t('skill.uses');
    el.style.color='var(--ink-3)';
  }
}

/* 技能卡片 HTML (详情弹层用) */
function skillCardsHtml(filter){
  var skills=B.listSkills();
  var f=(filter||'').toLowerCase();
  var filtered=skills.filter(function(s){
    if(!f)return true;
    return s.name.toLowerCase().indexOf(f)>=0||s.desc.toLowerCase().indexOf(f)>=0;
  });
  var h='<div class="rd-list">';
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
  if(filtered.length===0)h+='<div class="rd-empty">'+t('skill.none')+'</div>';
  h+='</div>';
  return h;
}

/* 技能卡片事件绑定 (点击触发 + 长按移除) */
function bindSkillCards(container){
  container.querySelectorAll('.skill').forEach(function(el){
    var sid=el.getAttribute('data-skill');
    el.addEventListener('click',function(){
      if(lpSuppressClick())return;
      B.recordSkill(sid);
      B.toast(t('skill.triggered'));
      renderSkillPage();
      ev('触发技能 '+sid);
    });
    bindLongPress(el,{
      text:t('skill.remove'),
      exec:function(){
        var res=B.deleteSkill(sid);
        if(res.ok){B.toast(t('skill.removed'));renderSkillPage();openRunDetail('skills');}
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
