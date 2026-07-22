/* ============================================================
   store.js — 数据层
   ROOMS: 本地壳数据, 持久化在 localStorage;
   'desk' 为系统保留 id, 不可删除/覆盖
   ============================================================ */
var STORE_KEY='mov_rooms_v2';
/* P1.5: 旧 key 一次性迁移 hermes_rooms_v2 → mov_rooms_v2 (CONTRACT_ROOM 约束3) */
try{
  var _oldStore=localStorage.getItem('hermes_rooms_v2');
  if(_oldStore!=null){
    if(localStorage.getItem(STORE_KEY)==null)localStorage.setItem(STORE_KEY,_oldStore);
    localStorage.removeItem('hermes_rooms_v2');
  }
}catch(e){}
var DEFAULT_ROOMS=[
  {id:'desk',name:'设备控制 · 单聊',mode:'single',members:['mov'],phase:'已交付',last:'手电筒 / 电量 / 音量 / 亮度 / 震动 / 截屏… 直接下达指令',time:'现在',unread:0,played:false,
   seed:[{t:'sys',h:'SINGLE · mov-agent 在线 · 30+ 原生能力就绪'},{t:'agent',who:'mov',h:'我是 MOV。直接下达设备指令即可执行, 例如: <code>打开手电筒</code> · <code>电量多少</code> · <code>最大音量</code> · <code>亮度调高</code> · <code>震动</code> · <code>截屏</code>。非指令类问题会转交 AI 回答(右上角 ≡ 可配置 API)。'}]},
  {id:'fit',name:'健身 APP · 设计提案',mode:'council',members:{human:[{who:'you',role:'owner'}],ai:[]},phase:'讨论中',last:'多模型各抒己见 → 汇总 → MOV 执行',time:'现在',unread:0,played:false,
   seed:[{t:'sys',h:'COUNCIL 已召开 · 多模型 AI 团队 · MOV 主持'},{t:'agent',who:'mov',h:'设计一个健身 APP 的话, 先告诉我你的目标用户和核心需求——我会拉 AI 团队一起讨论方案。'}]},
  {id:'hainan',name:'HAINAN.WANG · 部署',mode:'single',members:['mov'],phase:'执行中',last:'EdgeOne 构建巡检通过, 证书链校验中',time:'2h',unread:1,played:false,
   seed:[{t:'agent',who:'mov',h:'正在腾讯云 EdgeOne 执行部署巡检, 完成后回报。'}]}
];
var ROOMS;
try{
  var saved=JSON.parse(localStorage.getItem(STORE_KEY));
  if(saved&&saved.length){
    ROOMS=saved.map(function(r){r.msgs=[];r.msgData=r.msgData||[];r.seeded=!!r.seeded;r.played=false;return r;});
    if(!ROOMS.some(function(r){return r.id==='desk';}))ROOMS.unshift(DEFAULT_ROOMS[0]);
  }else{ROOMS=DEFAULT_ROOMS;}
}catch(e){ROOMS=DEFAULT_ROOMS;}
function persistRooms(){
  try{
    localStorage.setItem(STORE_KEY,JSON.stringify(ROOMS.map(function(r){
      return {id:r.id,name:r.name,mode:r.mode,members:r.members,phase:r.phase,
              last:r.last,time:r.time,unread:r.unread,seed:r.seed,seeded:r.seeded,played:r.played,
              msgData:r.msgData||[]};
    })));
  }catch(e){}
}
<<<<<<< Updated upstream
var AV={ 'claude':['CL','#52525B'],'gpt-5':['G5','#27272A'],'gemini':['GM','#71717A'],'mov':['MO','#D97706'],'YOU':['ME','#09090B'] };
/* 厂商色板: 13 个 provider key → 头像区分色 (后端未给 color 时的兜底); 未知 provider 回退金色 */
var PROVIDER_COLORS={
  'deepseek':'#4D6BFE','moonshot':'#1F1F23','zhipu':'#3A5BF0','qwen':'#615CED',
  'doubao':'#3370FF','spark':'#00A1E0','minimax':'#E8442E','baichuan':'#FF7A00',
  'stepfun':'#6E56CF','hunyuan':'#00B578','yi':'#4B5563','openai':'#10A37F','ollama':'#52525B'
};
function providerColor(key){return PROVIDER_COLORS[key]||'#D97706';}
/* 厂商显示名: 以后端桥 getProviderPresets 为准 (与 ModelPresets.displayName 一致), 查不到用 key 本身 */
var _presetsCache=null;
function providerDisplayName(key){
  try{
    if(!_presetsCache)_presetsCache=B.providerPresets();
    var p=(_presetsCache||[]).find(function(x){return x.key===key;});
    return p?p.displayName:key;
  }catch(e){return key;}
}
=======
var AV={ 'claude':['CL','#38bdf8'],'gpt-5':['G5','#a3e635'],'gemini':['GM','#22d3ee'],'mov':['MO','#f59e0b'],'YOU':['ME','#f1f5f9'] };
>>>>>>> Stashed changes
/* 多模型: 把注册表模型的颜色合并进 AV 表 */
function refreshModelAvatars(){
  try{
    var models=B.listModels();
    models.forEach(function(m){
<<<<<<< Updated upstream
      var col=m.color||providerColor(m.provider);
      AV[m.id]=[(m.name||'?').slice(0,2).toUpperCase(),col];
      /* 兼容: 用模型名也能匹配 */
      AV[m.name]=[(m.name||'?').slice(0,2).toUpperCase(),col];
=======
      AV[m.id]=[(m.name||'?').slice(0,2).toUpperCase(),m.color||'#f59e0b'];
      /* 兼容: 用模型名也能匹配 */
      AV[m.name]=[(m.name||'?').slice(0,2).toUpperCase(),m.color||'#f59e0b'];
>>>>>>> Stashed changes
    });
  }catch(e){}
}
/* 多模型: 房间 AI 成员 ID 列表 (兼容旧数组格式和新对象格式) */
function roomAiMembers(r){
  if(!r.members)return [];
  if(Array.isArray(r.members))return r.members.filter(function(m){return m!=='mov'&&m!=='hermes'&&m!=='ai-team';});
  return (r.members.ai)||[];
}
/* 多模型: 房间 AI 成员显示名 (查注册表, 查不到用 ID) */
function roomAiNames(r){
  var ids=roomAiMembers(r);
  if(ids.length===0)return [];
  var models=B.listModels();
  return ids.map(function(id){
    var m=models.find(function(x){return x.id===id;});
    return m?m.name:id;
  });
}
var PHASE_BADGE={'讨论中':'run','收敛中':'run','待确认':'aw','执行中':'run','已交付':'ok','已归档':'off','待评审':'run'};

var curTab='chat', curRoomId=null, pending=[], trayOpen=false;
var genCounter=0; /* 房间切换守卫 (HANDOFF §5) */

function $(id){return document.getElementById(id);}
function ev(msg){ if(window.HermesBridge&&HermesBridge.log){try{HermesBridge.log(msg);}catch(e){}} }
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
