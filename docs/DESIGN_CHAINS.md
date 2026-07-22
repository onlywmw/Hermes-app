# DESIGN: 全交互链路

版本: v2.0
日期: 2026-07-22

每条 = 用户动作 → DOM → JS函数 → 数据变化 → 为什么这样设计

---

## A. 启动

| # | 用户动作 | DOM/系统 | JS 函数 | 结果 | 设计理由 |
|---|---------|---------|---------|------|---------|
| A1 | 点 APP 图标 | Android Launch | HermesActivity.onCreate | WebView 加载 hermes-shell.html | — |
| A2 | HTML 解析 | `<link>` `<script>` 标签 | 浏览器引擎 | CSS 渲染, JS 按序执行 | 无打包工具, 加载顺序 = 依赖顺序 |
| A3 | JS 初始化 | — | app.js: initLang → renderRooms → setTab('chat') | 首屏: 房间列表 | — |
| A4 | JS 初始化 | — | app.js: refreshModelAvatars | AV 表合并注册表模型颜色 | 新模型需要头像色 |
| A5 | JS 初始化 | — | app.js: setTimeout(refreshRuntime, 600) | 运行页预加载数据 | 延迟避免阻塞首屏 |
| A6 | JS 初始化 | — | app.js: encStatus 检查 | 加密不可用时 toast 警告 | 安全提醒 |
| A7 | 权限请求 | 系统弹窗 | HermesActivity.requestPermissions | 6 个危险权限逐一弹窗 | — |

---

## B. 底部导航

| # | 用户动作 | DOM | JS 函数 | 结果 | 设计理由 |
|---|---------|-----|---------|------|---------|
| B1 | 点"会话" | `button[data-tab="chat"]` | render.js: setTab('chat') | 显示房间列表或聊天详情 | view 用 CSS .act 切换, 不销毁 |
| B2 | 点"看板" | `button[data-tab="board"]` | render.js: setTab('board') → board.js: initBoardIfNeeded | 显示看板, 触发条浮现 | 首次打开才 init |
| B3 | 点"运行" | `button[data-tab="run"]` | render.js: setTab('run') → runtime.js: refreshRuntime | 显示运行页, 数据刷新 | 每次切 tab 刷数据 |
| B4 | 有未读消息 | `#ndChat` 红点 | render.js: renderRooms | 会话 tab 显示金色圆点 | 视觉提示 |

---

## C. 房间列表 (view-rooms)

| # | 用户动作 | DOM | JS 函数 | 数据变化 | 设计理由 |
|---|---------|-----|---------|---------|---------|
| C1 | 看房间列表 | `#roomList` | render.js: renderRooms | 遍历 ROOMS 数组 → innerHTML | 每次操作后重渲染 |
| C2 | 点房间卡片 | `.room[data-room]` | render.js → chat.js: enterRoom(id) | curRoomId=id, 切换 view-room, 加载聊天+文件 | — |
| C3 | 长按房间卡片(非desk) | `.room[data-room]` | chat.js: bindLongPress → app-room.js: openRoomOpsSheet | 底部弹出房间操作 sheet | 500ms+移动<10px |
| C4 | 长按 desk 房间 | `.room[data-room="desk"]` | 同上 | sheet 只显示"清空聊天记录" | 系统房间不可重命名/归档/删除 |
| C5 | 点 FAB "+" | `#fabNew` | app-room.js | 居中弹窗: newRoomMask.open | position:absolute, 悬浮右下角 |
| C6 | 看卡片头像堆叠 | `.room .avstack` | render.js: avstack(r) | 每个成员: 缩写字母+颜色圆点 | 一眼识别参与者和类型 |
| C7 | 看模式标签 | `.mini-tag` | render.js: renderRooms | "council · 3 AI" / "单聊 · MOV" | — |
| C8 | 看阶段徽章 | `.badge` | render.js: phaseBadge(p) | 颜色+文字 | PHASE_BADGE 映射 |
| C9 | 看最后消息 | `.room .r3` | render.js: r.last | 摘要文字 | — |
| C10 | 看未读点 | `.udot.show` | render.js: r.unread | 进入后消失 | — |

---

## D. 新建房间弹窗 (居中, 父子结构)

| # | 用户动作 | DOM | JS 函数 | 数据变化 | 设计理由 |
|---|---------|-----|---------|---------|---------|
| D1 | FAB → 弹窗出现 | `#newRoomMask` `#newRoomDialog` | app-room.js: classList.add('open') | mask: display:none→flex | mask 包 dialog, 统一显隐 |
| D2 | 弹窗居中 | CSS: .dialog-mask.open | flex align+justify center | 屏幕中央白色卡片 | 单输入框不需要全屏 |
| D3 | 输入项目名 | `#newRoomName` | — | — | — |
| D4 | 不填→点"创建" | `#btnCreate` | app-room.js | name='新项目', 创建房间 | 默认名兜底 |
| D5 | 填了→点"创建" | `#btnCreate` | app-room.js | ROOMS.splice(1,0,{...}) | 插在第2位(desk永远第1) |
| D6 | 创建后 | — | app-room.js: closeNewRoomDialog → initRoomStorage → renderRooms → persistRooms → enterRoom | 弹窗关→文件目录建→列表刷新→进房间 | 一气呵成 |
| D7 | 点 ✕ | `#btnSheetClose` | app-room.js: e.stopPropagation → closeNewRoomDialog | 弹窗关闭 | 阻止冒泡防触发mask |
| D8 | 点 mask 空白处 | `#newRoomMask` | app-room.js: closeNewRoomDialog | 弹窗关闭 | — |
| D9 | 点 dialog 内部 | `#newRoomDialog` | app-room.js: e.stopPropagation | 不关闭 | 防止误关 |

---

## E. 房间操作 Sheet (底部滑入)

| # | 用户动作 | DOM | JS 函数 | 数据变化 | 设计理由 |
|---|---------|-----|---------|---------|---------|
| E1 | 长按房间/点⋮ → sheet出现 | `#roomOpsMask` `#sheetRoomOps` | app-room.js: openRoomOpsSheet | mask+sheet 加 .open | 底部滑入, 四行操作 |
| E2 | 点"重命名" | `#opsRename` | app-room.js | 菜单态→输入态, 预填当前名 | sheet 内状态切换 |
| E3 | 输入新名字→确认 | `#opsRenameInput` → `#opsRenameOk` | app-room.js | room.name=newName, render+persist | — |
| E4 | 重命名→取消 | `#opsRenameCancel` | app-room.js | 退回菜单态 | — |
| E5 | 点"归档" | `#opsArchive` | app-room.js | room.phase='已归档', toast, 关闭 | 无二次确认 |
| E6 | 点"清空聊天记录" | `#opsClear` | app-room.js: showOpsConfirm | 菜单态→确认态 | 二次确认防误操作 |
| E7 | 确认清空 | `#opsConfirmOk` | app-room.js: clearRoomHistory | msgs=[], msgData=[], persist, toast | seeded保持true,重进不灌seed |
| E8 | 取消清空 | `#opsConfirmCancel` | app-room.js | 退回菜单态 | — |
| E9 | 点"删除房间" | `#opsDelete` | app-room.js: showOpsConfirm | 菜单态→确认态(红色) | 二次确认 |
| E10 | 确认删除 | `#opsConfirmOk` | app-room.js | ROOMS.splice, genCounter++, 回列表, toast | — |
| E11 | 点 ✕ | `#btnRoomOpsClose` | app-room.js: closeRoomOpsSheet | mask+sheet 去 .open | — |
| E12 | 点遮罩 | `#roomOpsMask` | app-room.js: closeRoomOpsSheet | 同上 | — |

---

## F. 房间详情 — 讨论 tab

### F1. 顶栏 & 子tab

| # | 用户动作 | DOM | JS 函数 | 结果 | 设计理由 |
|---|---------|-----|---------|------|---------|
| F1.1 | 点 ← 返回 | `#btnBack` | app.js | curRoomId=null, setTab('chat'), showView('view-rooms') | — |
| F1.2 | 系统返回键 | Android Back | HermesActivity: OnBackPressedCallback | 同上 | 物理返回 = 点← |
| F1.3 | 看房间标题 | `#roomTitle` | chat.js: enterRoom | r.name | — |
| F1.4 | 看副标题 | `#roomSub` | chat.js: enterRoom | 成员列表+协作模式 | — |
| F1.5 | 看阶段徽章 | `#roomPhaseBadge` | chat.js: phaseBadge | 颜色+文字 | — |
| F1.6 | 点 ⋮ | `#btnRoomMore` | app-room.js: openRoomOpsSheet | 弹出房间操作 sheet | — |
| F1.7 | 点"讨论"子tab | `.room-tab[data-subtab="chat"]` | app-files.js: setSubtab('chat') | chatPane显示, fileView隐藏 | display切换 |
| F1.8 | 点"文件"子tab | `.room-tab[data-subtab="files"]` | app-files.js: setSubtab('files') | fileView显示, chatPane隐藏 | — |

### F2. 消息发送

| # | 用户动作 | DOM | JS 函数 | 数据变化 | 设计理由 |
|---|---------|-----|---------|---------|---------|
| F2.1 | 输入文字→点 ↑ | `#msgInput` → `#btnSend` | app-chat.js: sendMsg → chat.js: routeMessage | push→persist, 输入框清空 | — |
| F2.2 | 输入文字→回车 | `#msgInput` keydown | app-chat.js: sendMsg | 同上 | — |
| F2.3 | 空内容+无附件→点发送 | `#btnSend` | sendMsg: 提前 return | 不发送 | 防空消息 |
| F2.4 | 命令文字→发送 | `#msgInput` | chat.js: routeMessage → B.parse 命中 | runDeviceCommand → 工具卡片 | 指令优先 |
| F2.5 | 非命令+AI已配→发送 | `#msgInput` | routeMessage → runAiChat | B.aiAsync → typing→AI回复 | 异步不阻塞UI |
| F2.6 | 非命令+AI未配→发送 | `#msgInput` | routeMessage → push提示 | 提示配置Key | — |

### F3. 设备指令 (26个)

每个指令: 输入文字 → IntentParser.parse 命中 → CapabilityExecutor.execute → toolNode 工具卡片

| # | 输入 | capability | 备注 |
|---|------|-----------|------|
| F3.1 | 打开手电筒 | torch.on | — |
| F3.2 | 关闭手电筒 | torch.off | — |
| F3.3 | 电量多少 | battery.status | — |
| F3.4 | 音量调到 10 | volume.set | — |
| F3.5 | 当前音量 | volume.get | — |
| F3.6 | 震动 500 | vibrate | — |
| F3.7 | 亮度调到 128 | brightness.set | 需 WRITE_SETTINGS |
| F3.8 | 当前亮度 | brightness.get | — |
| F3.9 | WiFi状态 | wifi.status | — |
| F3.10 | 打开wifi | wifi.toggle | Android10+无效 |
| F3.11 | 关闭wifi | wifi.toggle | Android10+无效 |
| F3.12 | 设备信息 | system.info | 含 /proc/meminfo |
| F3.13 | ip地址 | network.info | — |
| F3.14 | 截屏 | screen.capture | 需ADB/root |
| F3.15 | 应用列表 | app.list | — |
| F3.16 | 最近短信 | sms.recent | 需READ_SMS |
| F3.17 | 联系人 | contacts.list | 需READ_CONTACTS |
| F3.18 | 朗读 你好 | tts.speak | — |
| F3.19 | 读取剪贴板 | clipboard.get | — |
| F3.20 | 复制到剪贴板 xxx | clipboard.set | — |
| F3.21 | toast 你好 | toast | — |
| F3.22 | 发通知 xxx | notification.post | 需POST_NOTIFICATIONS |
| F3.23 | 定位 | location.get | 需GPS/网络定位 |
| F3.24 | 文件列表 | file.ls | — |
| F3.25 | 点击 500 800 | input.tap | Runtime.exec |
| F3.26 | 滑动 500 1500 500 500 | input.swipe | Runtime.exec |
| F3.27 | 帮助 | help | 能力列表 |

### F4. 消息长按删除

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| F4.1 | 长按消息气泡 500ms | `.msg .bubble` | chat.js: bindLongPress → triggerLongPress | 气泡金色边框, 底部黑条浮出 |
| F4.2 | 点"删除这条消息" | `#msgActions` | chat.js: deleteMessage | msgs+msgData 同步 splice, persist, toast |
| F4.3 | 点黑条外部 | — | chat.js: hideMsgActions | 黑条消失, 高亮取消 |
| F4.4 | 长按<500ms | — | cancelLongPress | 无反应 |
| F4.5 | 按住+滑动>10px | — | cancelLongPress | 取消, 不弹出 |

---

## G. 房间详情 — 文件 tab

### G1. 存储类型切换

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| G1.1 | 点"产出" | `.storage-tab[data-stype="work"]` | app-files.js: setStorageType('work') | 显示 work 目录 |
| G1.2 | 点"资料" | `.storage-tab[data-stype="inbox"]` | setStorageType('inbox') | 显示 inbox 目录 |
| G1.3 | 点"归档" | `.storage-tab[data-stype="archive"]` | setStorageType('archive') | 按来源分组显示 |
| G1.4 | 点"模板" | `.storage-tab[data-stype="template"]` | setStorageType('template') | 跨房间模板列表 |

### G2. 文件浏览

| # | 用户动作 | DOM | JS 函数 | 数据变化 | 设计理由 |
|---|---------|-----|---------|---------|---------|
| G2.1 | 看文件列表 | `#storageList` | files.js: renderFileTree / renderStorageView | B.listRoomFiles → 渲染 | index.json 目前仍用文件系统 |
| G2.2 | 点目录 | `.file-row[data-dir="1"]` | files.js | _filesPath追加, 重渲染 | — |
| G2.3 | 点面包屑段 | `.file-crumb[data-path]` | files.js | _filesPath=该路径, 重渲染 | 快速跳转 |
| G2.4 | 点".." | `.file-row[data-up="1"]` | files.js | _filesPath回上级 | 传统导航 |
| G2.5 | 看空目录 | — | files.js | "空目录"文案 | — |

### G3. 文件预览

| # | 用户动作 | DOM | JS 函数 | 结果 | 设计理由 |
|---|---------|-----|---------|------|---------|
| G3.1 | 点文件 | `.file-row[data-dir="0"]` | files.js: showFilePreview | overlay 弹出, 显示内容 | mask包overlay,父子结构 |
| G3.2 | 点 ✕ | `#previewClose` | app-files.js: closeFilePreview | overlay 关闭 | — |
| G3.3 | 点遮罩 | `#previewMask` | app-files.js: closeFilePreview | overlay 关闭 | — |

### G4. 文件操作

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| G4.1 | 长按文件 | `.file-row` | chat.js: bindLongPress → files.js: 删除 | 浮出"删除文件"→删除+toast |
| G4.2 | 点 FAB "+" | `#fileFabAdd` | app-files.js: openFileNewSheet | 弹出新建文件 sheet |

### G5. 新建文件 Sheet

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| G5.1 | 填名字→创建 | `#fileNewName` → `#btnFileNewCreate` | app-files.js | B.saveWorkFile → toast → 关闭 sheet |
| G5.2 | 空名字→创建 | 同上 | app-files.js | toast "请输入文件名" |
| G5.3 | 点 ✕ | `#btnFileNewClose` | app-files.js: closeFileNewSheet | 关闭 |
| G5.4 | 点遮罩 | `#fileNewMask` | app-files.js: closeFileNewSheet | 关闭 |

### G6. 版本管理

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| G6.1 | 点版本入口 | 产出文件→版本按钮 | files.js: showVersionOverlay | overlay 列出版本历史 |
| G6.2 | 点某版本 | `#versionOverlay` 内行 | files.js | 预览该版本内容 |
| G6.3 | 点"应用此版本" | overlay 内按钮 | files.js | B.restoreVersion → toast |
| G6.4 | 点 ✕ | `#versionClose` | app-files.js: closeVersionOverlay | 关闭 |
| G6.5 | 点遮罩 | `#versionMask` | app-files.js: closeVersionOverlay | 关闭 |

### G7. 模板 Sheet

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| G7.1 | 点"新建模板" | — | files.js: openTemplateSheet | 弹出模板 sheet |
| G7.2 | 填名字+内容→保存 | `#templateName` `#templateContent` → `#btnTemplateOk` | app-files.js: confirmTemplate | B.saveTemplate → toast |
| G7.3 | 点模板→使用 | 模板卡片→使用按钮 | files.js | B.useTemplate → 文件复制到房间 |
| G7.4 | 点 ✕ | `#btnTemplateClose` | app-files.js: closeTemplateSheet | 关闭 |
| G7.5 | 点遮罩 | `#templateMask` | app-files.js: closeTemplateSheet | 关闭 |

---

## H. 附件系统

| # | 用户动作 | DOM | JS 函数 | 结果 | 设计理由 |
|---|---------|-----|---------|------|---------|
| H1 | 点 "+" 附件按钮 | `#plusBtn` | app-chat.js | trayOpen 切换, 托盘展开/收起, 按钮旋转45° | 视觉反馈 |
| H2 | 点"选择文件" | `.tray-item` | app-chat.js | closeTray → B.pickFile → 系统文件选择器 | — |
| H3 | 选文件→确认 | 系统文件选择器 | app-chat.js 回调 | pending.push(info), renderPend | 文件信息出现在待发区 |
| H4 | 点待发文件 ✕ | `#pendRow .x` | chat.js: renderPend 内绑定 | pending.splice(i,1), renderPend | 移除 |
| H5 | 纯附件发送 | `#btnSend` | chat.js: sendMsg | push附件卡片, 不触发路由 | — |
| H6 | 附件+文字发送 | `#btnSend` | chat.js: sendMsg | push附件卡片+文字, 触发路由 | — |

---

## I. 看板

### I1. 触发条

| # | 用户动作 | DOM | JS 函数 | 结果 | 设计理由 |
|---|---------|-----|---------|------|---------|
| I1.1 | 切到看板 | `#view-board` | board.js: initBoardIfNeeded→showBoardTrigger | 触发条浮现, 3s后自动hide | 首次才init |
| I1.2 | 等 3 秒 | — | setTimeout | 触发条 classList.add('hidden') | 内容优先,不遮挡 |
| I1.3 | 触摸底部 80px | document touchstart | board.js | 触发条 classList.remove('hidden') | 唤醒 |
| I1.4 | 点触发条 | `#boardTrigger` | board.js: openBoardPanel | 面板滑入 | — |

### I2. 应用选择面板

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| I2.1 | 面板出现 | `#boardPanel` | openSheetExclusive | 底部滑入, 3列网格 |
| I2.2 | 点应用 | `.board-app-card[data-app]` | board.js | loadBoardApp→iframe.src切换→面板关闭 |
| I2.3 | 当前应用 | `.board-app-card.active` | — | 金色边框 |
| I2.4 | 长按非内置应用 | `.board-app-card[data-app]` | bindLongPress | 浮出"移除应用"→删除+toast |
| I2.5 | 长按内置应用 | 同上 | builtin=true→不绑定 | 不浮出操作条 |
| I2.6 | 点 "+" | `#boardPanelAdd` | board.js: openBoardAddSheet | 弹出添加应用 sheet |
| I2.7 | 点 ✕ | `#boardPanelClose` | board.js: closeBoardPanel | 面板关闭 |
| I2.8 | 点遮罩 | `#boardPanelMask` | board.js: closeBoardPanel | 面板关闭 |

### I3. 添加应用 Sheet

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| I3.1 | 填名字+URL→添加 | `#boardAddName` `#boardAddUrl` → `#btnBoardAddOk` | board.js: confirmBoardAdd | _boardApps.push→save→render→toast |
| I3.2 | 空名字→添加 | 同上 | board.js | toast "请输入名称" |
| I3.3 | 点 ✕ | `#btnBoardAddClose` | board.js: closeBoardAddSheet | 关闭 |
| I3.4 | 点遮罩 | `#boardAddMask` | board.js: closeBoardAddSheet | 关闭 |

### I4. 应用内容

| # | 用户动作 | DOM | 结果 | 设计理由 |
|---|---------|-----|------|---------|
| I4.1 | 看自带音乐应用 | `#boardFrame` (iframe) | 加载 board-apps/music.html | 本地 HTML, 无需网络 |
| I4.2 | 看自带阅读应用 | 同上 | board-apps/reader.html | — |
| I4.3 | 看自带健身应用 | 同上 | board-apps/fitness.html | — |
| I4.4 | 看自带笔记应用 | 同上 | board-apps/notes.html | — |
| I4.5 | 看用户添加URL | 同上 | iframe 加载远程 URL | — |

---

## J. 运行页

### J1. 进程卡片

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| J1.1 | 看设备状态 | `#healthCard` | runtime.js: refreshProcess | pid/uptime/mem/cmds/lastCmd |
| J1.2 | 看个人信息 | `.personal-row` | runtime.js: renderPersonalRow | 用户名+设置入口 |
| J1.3 | 点 ≡ 设置 | `#btnPersonalSettings` | app-run.js | B.openSettings()→跳设置页 |
| J1.4 | 点 ⟳ 刷新 | `#btnRunRefresh` | app-run.js: refreshRuntime | 全数据刷新 |

### J2. 折叠行 (通道/权限/技能)

| # | 用户动作 | DOM | JS 函数 | 结果 | 设计理由 |
|---|---------|-----|---------|------|---------|
| J2.1 | 看通道摘要 | `#rowChannels` | runtime.js | "全部正常"或具体异常 | 默认折叠 |
| J2.2 | 点通道行 | `#rowChannels` | app-run.js: openRunDetail('channels') | overlay 展开 4 通道详情 | — |
| J2.3 | 看权限摘要 | `#rowPerms` | runtime.js | "4/7 已授权" | 全部授权则隐藏 |
| J2.4 | 点权限行 | `#rowPerms` | app-run.js: openRunDetail('perms') | overlay 展开权限标签 | 点标签跳系统设置 |
| J2.5 | 看技能摘要 | `#rowSkills` | runtime.js | "3 个" | — |
| J2.6 | 点技能行 | `#rowSkills` | app-run.js: openRunDetail('skills') | overlay 展开技能列表+搜索 | — |
| J2.7 | 技能卡片: 点击 | `.skill` | skills.js | B.recordSkill→toast "技能已触发" | — |
| J2.8 | 技能卡片: 长按 | `.skill` | chat.js: bindLongPress→skills.js | 浮出"移除技能"→删除+toast | — |
| J2.9 | 技能搜索 | `#skillSearch` input | skills.js: renderSkillList | 实时过滤 | — |
| J2.10 | 点 overlay ✕ | `#runDetailClose` | app-run.js: closeRunDetail | 关闭 | — |
| J2.11 | 点 overlay 遮罩 | `#runDetailMask` | app-run.js: closeRunDetail | 关闭 | — |

### J3. 模型区

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| J3.1 | 看模型列表 | `#modelList` | runtime.js: refreshModel | 遍历 B.listModels 渲染 |
| J3.2 | 点模型行(已配置) | `.model-row` | runtime.js | B.setDefaultModel→toast→刷新 |
| J3.3 | 点"新增模型"行 | `.model-add` | runtime.js | 跳设置页 |
| J3.4 | 开发者折叠 | 状态条三角 | app-run.js | 展开/折叠 pid/mem/cmds 详情 |

### J4. Cron

| # | 用户动作 | DOM | JS 函数 | 结果 | 设计理由 |
|---|---------|-----|---------|------|---------|
| J4.1 | 输入自然语言→创建 | `#cronInput` → `#btnCronCreate` | app-run.js | 正则提取时间→生成cron→B.createCron→toast→刷新 | 用户不需要懂cron |
| J4.2 | 空输入→创建 | 同上 | app-run.js | toast 提示 | — |
| J4.3 | 点开关 | `.job .switch` | runtime.js: renderCronJobs 内绑定 | B.toggleCron→200ms后刷新 | — |
| J4.4 | 点"删除" | `.del-cron` | runtime.js | confirm→B.deleteCron→toast→刷新 | — |

### J5. 权限

| # | 用户动作 | DOM | JS 函数 | 结果 |
|---|---------|-----|---------|------|
| J5.1 | 看权限标签 | `.perm` / `.perm.denied` | runtime.js: renderPermissions | 绿/红底色 |
| J5.2 | 点权限标签 | `.perm` | runtime.js | B.openAppSettings→跳系统设置 |

---

## K. AI 设置页 (Java Activity)

| # | 用户动作 | Java | 结果 |
|---|---------|------|------|
| K1 | 选 Provider | Spinner | URL/Model 自动填入预设 |
| K2 | 填 API Key | EditText | 保存到 EncryptedSharedPreferences |
| K3 | 点 Key 右侧 ✕ | `#btnClearKey` | 输入框清空 |
| K4 | 点"保存" | `#btnSave` | 所有字段写入 SharedPreferences, toast |
| K5 | 点"测试连接" | `#btnTest` | 后台线程发"请只回复:OK"→toast ✅/❌ |
| K6 | 切换 AI 开关 | Switch | setAiEnabled |
| K7 | 点"返回" | `#btnBack` | finish()→回 MOV 主界面 |

---

## L. 桌面小组件

| # | 用户动作 | DOM | Java | 结果 |
|---|---------|-----|------|------|
| L1 | 点快捷指令 | widget item | HermesWidgetProvider: executeCommand | IntentParser→CapabilityExecutor→toast ✅/❌ |
| L2 | 点刷新 | refresh button | notifyAppWidgetViewDataChanged | 列表刷新, toast |
| L3 | 点打开应用 | open button | startActivity | 启动 HermesActivity |

---

## M. 全局 & 异常

| # | 场景 | JS | 结果 |
|---|------|-----|------|
| M1 | JS 抛异常 | window.onerror (bridge.js) | logcat + 聊天区红色提示 |
| M2 | 加密降级 | app.js encStatus 检查 | toast 警告 |
| M3 | 桥未连接 | bridge.js: 所有 B.xxx 方法 try/catch | 返回默认值, 不崩溃 |
| M4 | 文件路径遍历 | Java: StorageManager.isSafe | 拒绝, 返回错误 JSON |
| M5 | Cron动作白名单外 | HermesCronWorker | BLOCKED + 日志 |
| M6 | 桥参数非法 | BridgeValidator | 返回错误 JSON |

---

## 文件-函数-元素速查表

| 文件 | 负责的交互 |
|------|-----------|
| `app.js` | 启动初始化, btnBack, 加密检查 |
| `app-room.js` | 新建房间弹窗, 房间操作 sheet, openSheetExclusive/closeAllSheets, btnSettings |
| `app-chat.js` | 消息发送, 附件按钮 |
| `app-files.js` | 子tab切换, 文件预览关闭, 版本overlay关闭, 模板sheet, 新建文件sheet, 存储类型切换 |
| `app-board.js` | 看板触发条, 面板开关, 添加应用sheet |
| `app-run.js` | 运行页刷新, Cron创建, 折叠行展开, 个人信息设置, 开发者折叠 |
| `chat.js` | 消息路由, 长按基础设施, 消息删除, 清空历史, enterRoom |
| `render.js` | 房间列表渲染, 视图切换, 消息渲染, 工具卡片, 文件卡片 |
| `files.js` | 文件树, 版本历史, 文件预览, 模板列表 |
| `board.js` | 应用加载, 面板渲染, 应用添加/删除, 触发条自隐 |
| `runtime.js` | 进程/通道/模型/Cron/权限/技能 渲染 |
| `skills.js` | 技能搜索, 技能触发, 长按删除 |
| `bridge.js` | HermesBridge 50+ 方法封装 |
| `store.js` | ROOMS 数据, AV 颜色表, persistRooms, 多模型头像 |
| `i18n.js` | 翻译字典, t() 函数 |
