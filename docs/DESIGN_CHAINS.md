# DESIGN: 交互链路全图

版本: v1.0
日期: 2026-07-22

> 每条链路从用户动作开始，到系统反应结束。包含：涉及的文件、DOM 元素、JS 函数、Java 桥方法、为什么这样设计。

---

## 链路 0：启动

```
用户: 点 APP 图标
  → HermesActivity.onCreate()
    → setContentView(R.layout.activity_hermes)   ← 只含一个 WebView
    → WebView.loadUrl("file:///android_asset/hermes-shell.html")
      → HTML 解析 → <link rel="stylesheet" href="css/shell.css">
      → <script src="js/i18n.js">     ← 翻译字典
      → <script src="js/store.js">    ← ROOMS 数据 + AV 颜色表 + $/ev/esc 工具
      → <script src="js/bridge.js">   ← B 对象 (HermesBridge 封装)
      → <script src="js/render.js">   ← 渲染函数
      → <script src="js/council.js">  ← Council 演示 (弃用)
      → <script src="js/chat.js">     ← 消息路由
      → <script src="js/skills.js">   ← 技能列表
      → <script src="js/files.js">    ← 文件树
      → <script src="js/board.js">    ← 看板
      → <script src="js/runtime.js">  ← 运行页
      → <script src="js/app-chat.js"> ← 聊天事件绑定
      → <script src="js/app-room.js"> ← 新建房间 + 房间操作
      → <script src="js/app-files.js">← 文件事件绑定
      → <script src="js/app-board.js">← 看板事件绑定
      → <script src="js/app-run.js">  ← 运行页事件绑定
      → <script src="js/app.js">      ← 初始化: initLang → renderRooms → setTab('chat')
    → addJavascriptInterface(new HermesBridge(), "HermesBridge")
    → requestPermissions()  ← 6 个危险权限

首屏: 房间列表 (view-rooms act), 底部导航三栏
```

**为什么这样设计**:
- 单 HTML 入口 + 多个 JS 文件按依赖顺序加载——没有打包工具，依赖关系通过加载顺序保证
- `app.js` 最后加载，因为初始化需要所有函数和事件绑定就绪
- `app-room.js` 在 `app.js` 之前加载，因为 `app-room.js` 定义了 `openSheetExclusive` 等全局工具函数，`app.js` 的 init 不依赖它但其他文件需要它

---

## 链路 1：底部导航切换

```
用户: 点底部"会话"/"看板"/"运行"
  → DOM: <button data-tab="chat|board|run">
  → JS: render.js line 569
    document.querySelectorAll('.bnav button').forEach(function(b){
      b.addEventListener('click',function(){
        setTab(b.getAttribute('data-tab'));
      });
    });
  → setTab(t):
    - 更新按钮高亮 (.on)
    - showView('view-'+t)  ← 对应 view 加 .act, 其他去 .act
    - if t==='run' → refreshRuntime()
    - if t==='board' → initBoardIfNeeded()

视图的 CSS 控制:
  .view { opacity:0; visibility:hidden; transform:translateX(14px) }
  .view.act { opacity:1; visibility:visible; transform:none }
  ← 只显示一个 view, 其他透明 + 不可交互
```

**为什么这样设计**:
- 三个 view 全部预渲染在 HTML 中，切换只是 CSS class 变化——不销毁不重建，状态保留
- 底部导航 `position:absolute;bottom:0;z-index:15` 浮在所有 view 上方

---

## 链路 2：新建房间

```
用户: 点房间列表右下角 FAB "+"
  → DOM: <button class="fab" id="fabNew">+
        <div class="dialog-mask" id="newRoomMask">          ← 默认 display:none
          <div class="dialog" id="newRoomDialog">            ← mask 的子元素
            <input id="newRoomName">
            <button id="btnCreate">
  → JS: app-room.js
    $('fabNew').addEventListener('click',function(){
      $('newRoomMask').classList.add('open');    ← display:none → display:flex
      $('newRoomName').value='';
      $('newRoomName').focus();
    });

  CSS:
    .dialog-mask { display:none }
    .dialog-mask.open { display:flex; align-items:center; justify-content:center }
    ← mask 全屏半透明黑底 + 居中 flex, dialog 白色圆角卡片在中间

用户: 填名字 (或不填) → 点"创建"
  → JS: app-room.js
    $('btnCreate').addEventListener('click',function(){
      var name = ... || '新项目';
      var id = 'r'+Date.now();
      ROOMS.splice(1,0,{...});          ← 房间对象插入列表第 2 位
      closeNewRoomDialog();              ← 去掉 mask .open → display:none
      B.initRoomStorage(id);            ← Java: 创建 /sdcard/mov/rooms/<id>/ 目录
      renderRooms();                     ← 刷新房间列表 DOM
      persistRooms();                    ← 写 localStorage
      enterRoom(id);                     ← 进入新房间
    });

用户: 点 mask 空白处 / 点 ✕
  → JS: app-room.js
    $('newRoomMask').addEventListener('click', closeNewRoomDialog);
    $('newRoomDialog').addEventListener('click', function(e){ e.stopPropagation(); });
    ← 点 dialog 内部不关闭, 点 mask (dialog 外部) 关闭
    $('btnSheetClose').addEventListener('click', function(e){
      e.stopPropagation();
      closeNewRoomDialog();
    });
    ← ✕ 按钮阻止冒泡, 防止触发 mask 的 click
```

**为什么这样设计**:
- **居中弹窗而非底部 sheet**: 新建房间只有一个输入框，不需要占据整个屏幕。居中弹窗不和其他底部 sheet 争 z-index
- **mask 包住 dialog (父子关系)**: mask 设 `display:none` 时，dialog 一定隐藏。之前平级是 bug
- **dialog 内部 stopPropagation**: 点输入框/按钮时不会误关弹窗
- **房间插入第 2 位**: `ROOMS.splice(1,0,...)` — desk 永远在第 0 位，新建房间紧接其后

---

## 链路 3：房间操作

```
用户: 在房间列表长按房间卡片 / 在房间详情点 ⋮
  → JS: app-room.js
    openRoomOpsSheet(roomId)
      → $('roomOpsMask').classList.add('open')    ← 底部 sheet 滑入
      → $('sheetRoomOps').classList.add('open')
      → 显示 4 个操作行: 重命名 / 归档 / 清空 / 删除
      → desk 房间: 只显示清空

用户: 点重命名 → sheet 切为输入态 → 填名字 → 确认
  → room.name = newName → renderRooms() + persistRooms()

用户: 点归档
  → room.phase = '已归档' → setPhase() → toast → closeRoomOpsSheet()

用户: 点清空 → 确认态 → 确认
  → clearRoomHistory(roomId)
    → room.msgs = [] → room.msgData = [] → persistRooms() → toast

用户: 点删除 → 确认态 → 确认
  → ROOMS.splice(idx, 1) → genCounter++ → 回列表 → persistRooms() → toast

关闭:
  $('roomOpsMask').addEventListener('click', closeRoomOpsSheet)
  $('btnRoomOpsClose').addEventListener('click', closeRoomOpsSheet)
```

**为什么这样设计**:
- **底部 sheet 而非居中弹窗**: 房间操作有 4 个选项，需要较大面积。底部 sheet 符合拇指操作区
- **Sheet 内状态切换** (菜单→确认/输入): 不改 DOM 结构，只切换三个 div 的 display。避免重复创建/销毁
- **desk 锁定**: 系统房间不可重命名/归档/删除

---

## 链路 4：聊天消息发送 & 路由

```
用户: 在输入框打字 → 点 ↑ 或回车
  → JS: app-chat.js
    sendMsg()
      → push(id, mkMsg(...))     ← 消息进 DOM + msgData
      → room.last = ...          ← 更新房间摘要
      → persistRooms()           ← 写 localStorage
      → routeMessage(id, text)

routeMessage 分支:

  ┌─ B.parse(text) 命中设备指令 → runDeviceCommand(id, text)
  │    → B.cmd(text)  ← IntentParser → CapabilityExecutor
  │    → toolNode('device', text, dur, ...)  ← 工具卡片
  │    → push 结果消息
  │
  ├─ 未命中 + AI 已配置 → runAiChat(id, text)
  │    → B.aiAsync(text, callback)
  │    → Java: aiExecutor.execute → AiClient.chat()
  │    → 回调: evalJs → push AI 回复
  │
  └─ 未命中 + AI 未配置 → push 提示消息
```

**为什么这样设计**:
- **指令优先路由**: 本地指令 <1ms 响应，不浪费 AI token
- **AI 异步**: `aiExecutor` 后台线程 → 网络 IO 不阻塞 WebView UI
- **消息即时渲染**: `push()` 先渲染到聊天区，再异步持久化。用户感觉即时

---

## 链路 5：文件系统

```
用户: 在房间详情点"文件"子 tab
  → JS: app-files.js setSubtab('files')
    → $('chatPane').style.display='none'
    → $('fileView').style.display=''
    → _filesPath='' → renderFileTree(curRoomId) 或 renderStorageView()

文件浏览:
  → B.listRoomFiles(roomId, subPath)
    → Java: StorageManager.listWorkFiles → 读目录
  → 点击目录 → _filesPath 追加 → 重新 render
  → 点击文件 → B.readFile → showFilePreview → overlay 预览
  → 长按文件 → bindLongPress → 删除

版本查看:
  → B.listVersions(roomId, path)
  → overlay 展示版本列表
  → 点某个版本 → B.readFile(snapshotPath) → overlay 预览
  → 点"应用此版本" → B.restoreVersion → 当前文件被覆盖
```

**为什么这样设计**:
- **文件 tab 和聊天 tab 是 display 切换**: 不是销毁，用户切回讨论 tab 时状态保留
- **面包屑导航**: 每段可点击跳回任意层级——比".."按钮更直观
- **index.json 仍在文件系统**: SQLite 方案设计了但未实施

---

## 链路 6：看板

```
用户: 切到看板 tab
  → JS: board.js initBoardIfNeeded()
    → localStorage 加载应用列表
    → 默认选中第一个应用 → loadBoardApp → iframe src 赋值
    → showBoardTrigger() → 3 秒后自动 hide

触发条:
  DOM: .board-trigger (半透明胶囊, position:absolute, bottom)
  → 点击 → openBoardPanel() → 应用选择面板从底部滑入
  → 触摸屏幕底部 80px → showBoardTrigger() 唤醒

应用选择面板:
  DOM: .board-panel (底部滑入, height:55%)
  → 3 列网格渲染所有应用
  → 点应用 → loadBoardApp → closeBoardPanel
  → 长按非内置应用 → 删除
  → 点 + → openBoardAddSheet → 填名字+URL → 加入列表
```

**为什么这样设计**:
- **全屏 iframe + 浮动触发条**: 应用内容优先，chrome 最小化
- **内置应用保护**: `builtin:true` 不可删除
- **localStorage 存储**: 应用列表轻量 (<20条)，不需要 Java 层

---

## 链路 7：运行页

```
用户: 切到运行 tab
  → JS: refreshRuntime()
    → refreshProcess()     ← B.runtimeStats() → pid/uptime/mem/cmds
    → refreshChannels()    ← 4 通道状态
    → refreshModel()       ← AI 模型列表
    → renderCronJobs()     ← Cron 任务列表
    → renderPermissions()  ← 权限标签
    → renderSkills()       ← 技能卡片
```

**为什么这样设计**:
- **所有数据实时查询**: 不进缓存，每次切 tab 刷新
- **Cron 任务在运行页**: 定时任务是"运行中"的概念，属于这个 tab

---

## 链路 8：Cron 创建

```
用户: 在运行页输入框填"每天 8:30 汇总邮件" → 点创建
  → JS: app-run.js
    → 解析自然语言: 正则匹配时间 → 生成 cron 表达式
    → B.createCron(name, cron, text)
      → Java: CronManager.createJob → SharedPreferences + WorkManager
    → renderCronJobs() → 刷新列表
```

**为什么这样设计**:
- **自然语言输入**: 普通用户不需要懂 cron 表达式
- **WorkManager**: Android 官方任务调度，系统管理生命周期

---

## 链路 9：长按基础设施

```
用户: 长按任意可删除元素 (消息/技能/文件/房间/看板应用)
  → JS: chat.js bindLongPress(node, action)
    → touchstart/mousedown → 500ms 定时器
    → touchmove >10px → 取消
    → 500ms 到 → triggerLongPress()
      → node 加 .longpress-hl (金色边框高亮)
      → showMsgActions(text, callback) → 底部黑条浮出
      → 点击黑条 → callback 执行 (删除)
      → 点击外部 → hideMsgActions
```

**为什么这样设计**:
- **500ms + 10px 位移取消**: 区分长按和滑动，防止误触发
- **touchstart + mousedown 双事件**: 兼容触屏和鼠标 (开发调试用)
- **操作条而非 confirm 弹窗**: 不用浏览器原生弹窗，视觉一致

---

## 文件-函数-元素对应表

| 用户动作 | DOM 元素 | JS 文件 | 关键函数 | Java 桥 |
|---------|---------|---------|---------|---------|
| 切 tab | .bnav button[data-tab] | render.js | setTab | — |
| 新建房间 | #fabNew → #newRoomMask | app-room.js | open→create→closeNewRoomDialog | initRoomStorage |
| 进房间 | .room[data-room] | render.js→chat.js | enterRoom | setRoomOpen |
| 发消息 | #msgInput + #btnSend | app-chat.js→chat.js | sendMsg→routeMessage | execCommand/aiChatAsync |
| 房间操作 | .room 长按 / #btnRoomMore | app-room.js | openRoomOpsSheet | — |
| 文件浏览 | .room-tab[data-subtab=files] | app-files.js→files.js | setSubtab→renderFileTree | listRoomFiles |
| 文件预览 | .file-row | files.js | showFilePreview | readFile |
| 看板切换 | #boardTrigger→#boardPanel | board.js | openBoardPanel→loadBoardApp | — |
| 长按删除 | 消息/技能/文件气泡 | chat.js | bindLongPress→deleteXxx | deleteFile/deleteSkill |
| 运行刷新 | #btnRunRefresh | app-run.js→runtime.js | refreshRuntime | getRuntimeStats |
| Cron创建 | #cronInput→#btnCronCreate | app-run.js | parseAndCreate | createCronJob |
