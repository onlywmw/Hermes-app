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
  5. desk 房间同样显示「AI 成员」入口 (2026-07-24 起 desk 不再特殊)
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

### TC-R06：desk 是普通房间 (2026-07-24 起)

```
Given: desk 房间
When: 长按 desk / 点 ⋮
Then:
  1. 操作 sheet 与其他房间一致: 重命名/AI 成员/归档/删除/清空 全部可用
  2. 删除 desk 后不复活 (不再从 DEFAULT_ROOMS 自动补回)
  3. 仅首次安装时由 DEFAULT_ROOMS 播种
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
Given: 用户删除了所有房间 (含 desk)
When: 看房间列表
Then:
  1. 列表为空
  2. 不崩溃, 可通过 + 新建房间
```

---

## 实现约束（不可违反）

1. **新建房间是底部 sheet，不是居中弹窗。** `#newRoomMask` + `#newRoomSheet`（兄弟结构，复用 `.sheet-mask`/`.sheet` 体系与 `openSheetExclusive`）。v2.0 起替代旧居中 dialog。
2. **房间数据 `members` 格式：** `{human: [{who, role}], ai: [modelId]}`。旧格式 `['mov']` 仍需兼容（store.js 的 roomAiMembers 已做兼容）；成员编辑保存时旧格式迁移为新格式。
3. **ROOMS 数组持久化在 localStorage key `mov_rooms_v2`。** key 名不可变。
4. **desk 是普通房间 (2026-07-24 起)。** id='desk' 仅作首装播种，可删除/重命名/归档，删除后不复活。desk 走旧全局 AiProviderConfig，不参与房间级模型路由。
5. **新建房间时 `initRoomStorage(id)` 必须在 `enterRoom(id)` 之前调用。** 否则文件 tab 打开时目录不存在。
6. **房间操作 sheet 的状态切换（菜单→确认→输入→成员）通过切换 div 的 display 实现，不增删 DOM。**
7. **单聊房模型路由：** 非 desk 且 members.ai 非空的房间，发消息走 `aiChatWithModel(text, modelId)`；modelId 失效时原生侧回退注册表默认模型；注册表为空走「AI 未配置」提示。
## 新建房间设计决策
---

## 背景：现状的三个硬伤

1. **议会（council）模式没有创建入口。** 弹窗只有一个名字输入框，创建永远是 `mode:'single', members:['mov']`。唯一的 council 房间是 store.js 写死的演示数据。i18n.js 里 `sheet.council*` 一整套文案已存在但未接线。
2. **数据格式违反 CONTRACT_ROOM 约束 2。** 契约要求 `members:{human:[],ai:[modelId]}`，创建代码写的是旧数组格式 `['mov']`。
3. **`B.initRoom(rid,name,desc,members)` 桥接方法是死代码**，房间磁盘元数据（room.json）从未写入。

## 决策记录（与用户确认）

| 问题 | 决策 |
|------|------|
| 核心目标 | 打通议会模式：创建时选「单聊 / AI 团队」+ 选模型 |
| 成员可改性 | 创建后可改（房间操作 sheet 加「AI 成员」入口） |
| 单聊房模型 | 单聊也选模型，统一走 ModelRegistry，废弃房间层面对旧 AiProviderConfig 的依赖（desk 房除外） |
| 空模型兜底 | 引导条「去添加 →」跳运行页；允许跳过先创建 |
| 交互形态 | 单页底部 Sheet（方案 A），替代居中弹窗；CONTRACT_ROOM 约束 1 随之修订 |

## 交互流程

底部 sheet（复用 `.sheet-mask` / `.sheet` 体系与 `openSheetExclusive`）：

```
新建房间              ✕
────────────────────────
名字  [ 产品V2.0            ]   ← 空则默认「新项目」
模式  [ ● 单聊  |  AI 团队  ]   ← segmented，默认单聊
AI 成员
┌────────────────────────┐
│ ○ DeepSeek-V4   (默认)  │   ← 单聊=单选(○)，团队=多选(☑)
│ ○ GPT-5                │
│ ○ 本地 Ollama           │
└────────────────────────┘
── 空态 ──
ⓘ 还没配置模型，去添加 →        ← 点击 setTab('run')
[        创 建        ]
```

行为规则：

1. 模式切「AI 团队」→ 模型列表变多选；未选任何模型时创建按钮置灰。
2. 单聊默认勾选注册表默认模型；团队默认勾选默认模型，用户增删。
3. 创建 → `B.initRoomStorage(id)` → `enterRoom(id)`（顺序遵守 CONTRACT_ROOM 约束 5）。
4. seed 消息按模式区分：单聊沿用现有文案；团队用现成 key `sheet.councilFirst`。
5. 创建同时调用现存的 `B.initRoom(rid, name, '', members)` 落盘 room.json（激活死代码，desc 本期留空）。

## 创建后编辑成员

房间操作 sheet（长按 / ⋮）菜单新增「AI 成员」项，点开复用创建 sheet 的同一组件（模式 segmented + 模型勾选）：

- 模式始终由用户显式选择，不由勾选数反推；唯一例外：勾选数为 0 且模式为团队时，保存自动降级 `mode='single'`。
- 保存即生效：更新 `members` 与 `mode`；旧格式 `members:['mov']` 在保存时迁移为新格式。
- 保存后刷新房间副标题（`enterRoom` 的 roomSub 逻辑）与列表头像栈（`avstack`）。
- 编辑时若房间正在 council 讨论：`genCounter++` 使旧讨论回调失效（复用现有守卫）。

## 数据模型

```js
// 单聊
{ id, name, mode:'single',
  members:{human:[{who:'you',role:'owner'}], ai:['<modelId>']} }
// 团队
{ id, name, mode:'council',
  members:{human:[{who:'you',role:'owner'}], ai:['<modelId>','<modelId>']} }
```

- 旧格式兼容继续由 `roomAiMembers()`（store.js）承担，不改存储 key（`mov_rooms_v2` 不变）。

## 单聊房 AI 路由变更

- 非 desk 单聊房：`routeMessage()` 取 `roomAiMembers(room)[0]`，走新增的
  `B.aiChatWithModel(text, modelId, cbId)` → `BridgeAi.aiChatWithModel`
  （`new AiClient(modelRegistry.get(modelId).toModelConfig())`，~30 行 Java）。
- desk 房：维持现状走全局 AiProviderConfig（设备控制场景不动）。
- 房间未选模型或模型已被删除：回退到注册表默认模型；注册表为空 → 现有「AI 未配置」提示。

## 边界情况

| 场景 | 行为 |
|------|------|
| 模型列表为空 | 显示引导条；创建按钮可用；发消息走「AI 未配置」提示 |
| 团队只选 1 个模型 | 允许，CouncilClient 单成员自然退化 |
| 编辑成员减到 0 | 自动降级 `mode='single'` |
| 编辑时讨论进行中 | `genCounter++` 使旧回调失效 |
| 名字为空 | 默认「新项目」（同现状） |

## 改动文件清单

| 文件 | 改动 |
|------|------|
| `hermes-shell.html` | newRoom dialog → sheet；模型列表容器；操作 sheet 加「AI 成员」面板 |
| `js/app-room.js` | 重写创建逻辑 + 成员编辑逻辑（约 +120 行） |
| `js/chat.js` | `routeMessage` 单聊按房间模型路由（~10 行） |
| `js/bridge.js` | B 封装加 `aiChatWithModel`（~4 行） |
| `js/i18n.js` | 新增 key：模式名 / 成员 / 去添加 / 至少选一个 |
| `css/shell.css` | 尽量复用 V4.0 已有类：`.mopt`/`.mode-opts`（模式选项卡）、`.mpick`/`.mpick-wrap`（模型勾选行）、`.chip-btn`；仅补 segmented 等缺失样式（<30 行） |
| `BridgeAi.java` / `BridgeFactory.java` | 加 `aiChatWithModel`（~30 行） |
| `docs/CONTRACT_ROOM.md` | 约束 1 改为 sheet；TC-R01/R02 重写；新增成员编辑 TC |

## 测试与验收

- `./gradlew test`：Java 侧不回归。
- 手动验收按更新后的 CONTRACT_ROOM TC：创建单聊 / 创建团队 / 空模型引导 / 编辑成员（含降级）/ 杀进程重启持久化 / 单聊房按所选模型回复。

## 明确不做（YAGNI）

- 房间描述（desc）字段：本期不落 UI，仅通过 `B.initRoom` 传空串占位。
- 创建向导多步分页、房间模板套用：不引入。
- desk 房的模型选择：不动。

---

## 关联合同

- [CONTRACT_ARCH.md](CONTRACT_ARCH.md)
- [CONTRACT_ZINDEX.md](CONTRACT_ZINDEX.md) — sheet 层级
