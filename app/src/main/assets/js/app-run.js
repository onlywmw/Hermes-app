/* ============================================================
   app-run.js — 运行页刷新 + 详情弹层
   (从 app.js 拆出, DESIGN_POLISH #2)
   ============================================================ */

/* 运行页刷新: 切 tab/回前台自动触发 (V5 已移除手动刷新按钮) */

/* 三行入口与详情弹层已随 V5 移除 (通道/技能/权限不再展示) */

/* 个人信息设置入口 */
$('btnPersonalSettings').addEventListener('click',function(){
  B.openSettings();
  ev('从运行页打开设置');
});

/* M3: 交互式终端入口 (内嵌 Ubuntu) */
$('btnTerminal').addEventListener('click',function(){
  B.openTerminal();
  ev('打开终端');
});

/* V5: 素白 ↔ 墨黑 主题切换 (持久化 mov_theme) */
(function initThemeBtn(){
  var btn=$('btnTheme');
  if(!btn)return;
  btn.textContent=document.documentElement.classList.contains('dark')?'◑':'◐';
  btn.addEventListener('click',function(){
    var dark=document.documentElement.classList.toggle('dark');
    try{localStorage.setItem('mov_theme',dark?'dark':'light');}catch(e){}
    btn.textContent=dark?'◑':'◐';
    ev('切换主题 → '+(dark?'墨黑':'素白'));
  });
})();
