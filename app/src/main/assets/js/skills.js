/* ============================================================
   skills.js — 技能 (timeAgo 供 files.js 使用)
   ============================================================ */
function timeAgo(ts){
  var diff=Date.now()-ts;
  if(diff<3600000)return Math.max(1,Math.floor(diff/60000))+t('ago.m');
  if(diff<86400000)return Math.floor(diff/3600000)+t('ago.h');
  return Math.floor(diff/86400000)+t('ago.d');
}
