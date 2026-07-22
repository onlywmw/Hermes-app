# CONTRACT: 房间系统

版本: v1.0
日期: 2026-07-22
status: design-ready
交付对象: 前端程序员

---

## 验收测试用例

### TC-R01：新建房间 — 正常流程

```
Given: 用户在房间列表
When: 点 FAB → 弹窗出现 → 输入"产品V2.0" → 点创建
Then:
  1. 弹窗关闭 (display:none)
  2. 新房间出现在列表第 2 位 (desk 永远第 1)
  3. 自动进入新房间
  4. 聊天区显示 seed 消息: "我是 MOV。直接下达指令或提问即可。"
  5. localStorage 已持久化 (杀 APP 重启后房间仍在)
```

### TC-R02：新建房间 — 默认名

```
Given: 用户不填名字
When: 点创建
Then:
  1. 房间名 = "新项目"
  2. 其他行为同 TC-R01
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
  2. "重命名""归档""删除" 不显示
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

1. **新建房间弹窗是居中 dialog，不是底部 sheet。** `#newRoomMask` 包 `#newRoomDialog`（父子结构）。mask 默认 `display:none`，打开时 `display:flex`。
2. **房间数据 `members` 格式：** `{human: [{who, role}], ai: [modelId]}`。旧格式 `['mov']` 仍需兼容（store.js 的 roomAiMembers 已做兼容）。
3. **ROOMS 数组持久化在 localStorage key `mov_rooms_v2`。** key 名不可变。
4. **desk 房间 id='desk' 不可删除。** 如果 localStorage 数据中不存在 desk，从 DEFAULT_ROOMS 恢复。
5. **新建房间时 `initRoomStorage(id)` 必须在 `enterRoom(id)` 之前调用。** 否则文件 tab 打开时目录不存在。
6. **房间操作 sheet 的状态切换（菜单→确认→输入）通过切换三个 div 的 display 实现，不增删 DOM。**
