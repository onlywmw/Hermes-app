# CONTRACT: 房间系统

版本: v2.0
日期: 2026-07-22
status: design-ready
交付对象: 前端程序员
关联: DESIGN_NEW_ROOM.md（创建流程 v2）

---

## 验收测试用例

### TC-R01：新建房间 — 单聊 + 选模型

```
Given: 用户在房间列表, 注册表有 ≥1 个模型
When: 点 FAB → 底部 sheet 滑出 → 默认「和 MOV 一对一」且默认模型已勾选 → 输入"产品V2.0" → 点创建
Then:
  1. sheet 关闭 (translateY 收起)
  2. 新房间出现在列表第 2 位 (desk 永远第 1)
  3. 自动进入新房间
  4. 聊天区显示 seed 消息: "我是 MOV。直接下达指令或提问即可。"
  5. 房间 members = {human:[{who:'you',role:'owner'}], ai:[所选模型id]}
  6. 发非指令消息 → 由所选模型回答 (走 aiChatWithModel)
  7. localStorage 已持久化 (杀 APP 重启后房间仍在)
  8. 磁盘已落 room.json (B.initRoom 被调用)
```

### TC-R02：新建房间 — AI 团队

```
Given: 注册表有 ≥2 个模型
When: 点 FAB → 模式切到「拉 AI 团队一起讨论」→ 勾选 2 个模型 → 创建
Then:
  1. 房间 mode='council', members.ai 含 2 个模型 id
  2. seed 显示 sheet.councilFirst 文案, phase='讨论中'
  3. 列表 mini-tag 显示 "council · 2 AI"
  4. 发消息 → 走真实 Council 讨论
反例: 团队模式下一个模型都不勾 → 创建按钮置灰不可点
```

### TC-R02b：新建房间 — 默认名 & 空模型引导

```
Given-1: 用户不填名字
When: 点创建
Then: 房间名 = "新项目"

Given-2: 注册表 0 个模型
When: 打开创建 sheet
Then:
  1. 模型区显示「还没有配置模型 · 去添加 →」
  2. 点「去添加 →」→ 跳运行页
  3. 不选模型仍允许创建 (创建后可在 AI 成员里补)
```

### TC-R03：房间操作 — 重命名

```
Given: 用户在房间详情
When: 点 ⋮ → 点"重命名" → 输入"新名字" → 确认
Then:
  1. 顶栏标题变为"新名字"
  2. 房间列表刷新
  3. Toast 提示
  4. localStorage 已持久化
```

### TC-R03b：房间操作 — AI 成员编辑

```
Given: 非 desk 房间
When: ⋮ → AI 成员 → 改模式/增删模型 → 保存
Then:
  1. room.mode 与 room.members 更新 (旧数组格式 ['mov'] 迁移为新对象格式)
  2. 房间副标题与列表头像栈刷新
  3. 勾选数为 0 且模式为团队 → 自动降级 mode='single'
  4. 保存时进行中的 council 讨论作废 (genCounter 守卫)
  5. desk 房间不显示「AI 成员」入口
```

### TC-R04：房间操作 — 清空聊天

```
Given: 房间有 10 条消息
When: ⋮ → 清空 → 确认
Then:
  1. 聊天区空白
  2. room.msgs = [], room.msgData = []
  3. 退出再进入: 仍为空 (不灌 seed, seeded 保持 true)
  4. localStorage 已持久化
```

### TC-R05：房间操作 — 删除

```
Given: 用户创建的房间 (非 desk)
When: ⋮ → 删除 → 确认
Then:
  1. 房间从列表消失
  2. 回到房间列表
  3. localStorage 已持久化
  4. desk 房间不受影响
```

### TC-R06：desk 房间保护

```
Given: desk 房间
When: 长按 desk / 点 ⋮
Then:
  1. 操作 sheet 只显示"清空聊天记录"
  2. "重命名""AI 成员""归档""删除" 不显示
```

### TC-R07：切换房间 → 聊天区隔离

```
Given: 房间 A 有 3 条消息, 房间 B 有 5 条消息
When: 进入 A → 返回列表 → 进入 B
Then:
  1. B 的聊天区显示 B 的 5 条消息
  2. A 的消息不出现在 B
  3. 返回列表 → 进 A → A 的 3 条消息仍存在
```

### TC-R08：房间详情 — 讨论/文件子 tab 切换

```
Given: 用户在房间详情
When: 点"文件" → 点"讨论"
Then:
  1. "文件": chatPane 隐藏, fileView 显示
  2. "讨论": chatPane 显示, fileView 隐藏
  3. 切换不丢状态 (聊天区消息不变)
```

### TC-R09：空房间列表

```
Given: 用户删除了所有房间 (只剩 desk)
When: 看房间列表
Then:
  1. desk 房间存在
  2. 不崩溃
```

---

## 实现约束（不可违反）

1. **新建房间是底部 sheet，不是居中弹窗。** `#newRoomMask` + `#newRoomSheet`（兄弟结构，复用 `.sheet-mask`/`.sheet` 体系与 `openSheetExclusive`）。v2.0 起替代旧居中 dialog。
2. **房间数据 `members` 格式：** `{human: [{who, role}], ai: [modelId]}`。旧格式 `['mov']` 仍需兼容（store.js 的 roomAiMembers 已做兼容）；成员编辑保存时旧格式迁移为新格式。
3. **ROOMS 数组持久化在 localStorage key `mov_rooms_v2`。** key 名不可变。
4. **desk 房间 id='desk' 不可删除。** 如果 localStorage 数据中不存在 desk，从 DEFAULT_ROOMS 恢复。desk 走旧全局 AiProviderConfig，不参与房间级模型路由。
5. **新建房间时 `initRoomStorage(id)` 必须在 `enterRoom(id)` 之前调用。** 否则文件 tab 打开时目录不存在。
6. **房间操作 sheet 的状态切换（菜单→确认→输入→成员）通过切换 div 的 display 实现，不增删 DOM。**
7. **单聊房模型路由：** 非 desk 且 members.ai 非空的房间，发消息走 `aiChatWithModel(text, modelId)`；modelId 失效时原生侧回退注册表默认模型；注册表为空走「AI 未配置」提示。
