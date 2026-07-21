# DESIGN: 存储架构 v1.0

版本: v1.0
日期: 2026-07-21
状态: 📐 design-ready

---

## 核心问题

MOV 现在有十二种数据，散落在三个不互通的存储层里：

```
localStorage:       房间列表、消息快照、看板应用列表
SharedPreferences:  AI配置、Cron任务、技能、统计、语言
文件系统:           房间文件、上传资料、Cron产出
```

**致命问题**: 你想知道"和 AI 讨论登录页面那次，产出了哪些文件、后来谁改过、现在在哪"——这个问题需要跨三层查询。现在做不到。数据在，但不通。

---

## 1. 数据分类（不是按"在哪存"，是按"怎么用"）

### 1.1 按写入频率

```
热写入 (每秒钟都在写)
  - 聊天消息 (每条消息即时追加)
  - 指令调用计数 (每次执行 +1)

温写入 (每天几次)
  - 房间文件产出 (AI 生成 → 人决定存)
  - 上传资料 (人主动选文件)
  - Cron 产出 (定时触发)

冷写入 (配一次基本不动)
  - AI 配置
  - 房间成员
  - 应用列表
  - 模板
  - 技能
```

### 1.2 按读取模式

```
顺序读 (从头到尾、从新到旧)
  - 聊天记录 (进房间 → 加载最近 N 条 → 向上翻)

随机读 (点哪读哪)
  - 文件内容 (点文件名 → 读那个文件)
  - 文件版本 (点版本号 → 读那个快照)

搜索读 (不知道在哪, 要搜)
  - "那次讨论登录的对话" → 搜聊天记录
  - "DeepSeek 生成的代码" → 搜文件来源
  - "甲方发的 PDF" → 搜上传资料

聚合读 (不读具体内容, 读统计)
  - 房间文件数和大小
  - 技能调用次数
  - Cron 执行历史
```

### 1.3 按关联关系

```
独立数据 (不需要关联查询)
  - AI 配置
  - 语言设置

关联数据 (查询需要跨类型)
  - 聊天消息 ↔ 消息里引用的文件
  - 文件 ↔ 创建它的对话
  - 文件 ↔ 后续的版本修改
  - Cron 任务 ↔ 它的产出文件
  - 技能 ↔ 调用统计
  - 上传资料 ↔ AI 分析摘要
```

---

## 2. 三个存储层的定位

| 层 | 适合什么 | 不适合什么 |
|----|---------|-----------|
| **localStorage** | 应用配置、房间列表摘要、小数据 (<100条) | 聊天消息 (>1MB)、文件内容 |
| **SharedPreferences** | 键值配置 (<1KB) | 列表数据、JSON 数组 |
| **文件系统** | 文件内容、大文本、二进制 | 快速搜索、关联查询 |

**当前问题**: 聊天消息在 localStorage 里——这是错误的。一个活跃房间几个月下来，`msgData` 可以到几 MB。localStorage 有 5MB 上限，且每次读全量 JSON 耗性能。

---

## 3. 重新设计：每种数据该去哪

### 3.1 聊天消息 → 文件系统 + 索引

```
存储:
  /sdcard/mov/rooms/<id>/chat/
    2026-07-21.json      ← 每天一个文件, 只追加
    2026-07-20.json
    ...

文件内容 (每行一条消息):
  {"t":1721404800,"who":"you","h":"写个登录页面"}
  {"t":1721404802,"who":"DeepSeek","h":"好的, 这是代码..."}
  {"t":1721405300,"who":"you","h":"存到文件","action":"file.write","file":"src/Login.tsx"}

读取策略:
  进房间 → 只加载今天的文件 (40KB)
  向上翻 → 按需加载前一天的文件
  搜索 → 遍历文件 (暴力) 或维护一个索引文件

索引文件 (维护在内存, 定期落盘):
  /sdcard/mov/rooms/<id>/chat/index.json
  {
    "filesByDate": ["2026-07-21.json", ...],
    "totalMessages": 4230,
    "fileRefs": {                    ← 哪条消息引用了哪个文件
      "src/Login.tsx": ["1721405300"]
    }
  }
```

**为什么每天一个文件**: 追加写不需要解析全量 JSON。一条新消息直接 append 一行。读的时候按日期范围加载，不需要把几个月的历史全部反序列化。

### 3.2 房间文件（产出+资料+归档）→ 文件系统 + 元数据索引

```
/sdcard/mov/rooms/<id>/files/
  work/                   ← 产出 (AI生成 + 人修改)
    src/Login.tsx         ← 当前版本 (纯文本)
    src/utils.ts
  work-snapshots/         ← 产出历史快照 (按需加载)
    Login.tsx/v1.tsx
    Login.tsx/v2.tsx
  inbox/                  ← 资料 (人上传)
    mockup.png
    甲方PRD.pdf
  archive/                ← 归档 (Cron自动产出)
    邮件汇总/2026-07-21.md
  .meta/                  ← 所有元数据
    index.json            ← 一份文件, 描述整个 rooms/<id>/files/
```

**为什么不每种资产一个 .json 元数据文件**

当前设计把版本元数据放在 `.mov/work/Login.tsx.json`——每个文件一个 JSON。读一个房间的完整状态需要打开几十个 JSON 文件。合并成一份 `index.json`，一次读全。

### 3.3 `index.json` 结构

```json
{
  "work": {
    "src/Login.tsx": {
      "currentVersion": 4,
      "lock": null,
      "createdBy": "DeepSeek",
      "createdAt": 1721404800,
      "createdIn": "msg_abc123",
      "versions": [
        {"v":1,"author":"DeepSeek","at":1721404800,"msg":"首次生成","chatLink":"msg_abc123"},
        {"v":2,"author":"Claude","at":1721408400,"msg":"review修改","chatLink":"msg_def456"},
        {"v":3,"author":"you","at":1721412000,"msg":"手动改","parent":2},
        {"v":"4a","author":"DeepSeek","at":1721415600,"parent":3,"branch":true},
        {"v":"4b","author":"Claude","at":1721415600,"parent":3,"branch":true}
      ],
      "tags": ["登录","前端"]
    },
    "src/utils.ts": { ... }
  },
  "inbox": {
    "mockup.png": {
      "uploadedBy": "张三",
      "uploadedAt": 1721404800,
      "size": 438272,
      "mime": "image/png",
      "tags": ["参考截图","甲方"],
      "referencedIn": ["msg_abc123"],
      "aiAnalyzed": true,
      "aiSummary": "手机截图, 白色背景, 展示了一个电商首页布局..."
    }
  },
  "archive": {
    "邮件汇总": {
      "source": "cron",
      "sourceId": "cron_abc",
      "retention": "30days",
      "files": [
        {"name":"2026-07-21.md","date":"2026-07-21","size":2048}
      ]
    }
  },
  "stats": {
    "totalFiles": 12,
    "totalSize": 15242880,
    "lastModified": 1721415600,
    "byAuthor": {"DeepSeek":5,"Claude":3,"you":2,"张三":2}
  }
}
```

**一次 `B.readFile(roomId, "files/.meta/index.json")` → 拿到房间里所有文件的结构。** 然后按需加载具体文件内容。

---

## 4. 存储总表

| 数据 | 存储层 | 路径/Key | 读写模式 |
|------|--------|---------|---------|
| 聊天消息 | 文件系统 | `rooms/<id>/chat/<date>.json` | 追加写 / 按日范围读 |
| 聊天索引 | 文件系统 | `rooms/<id>/chat/index.json` | 覆盖写 / 进房间读一次 |
| 房间文件 | 文件系统 | `rooms/<id>/files/work/` | 随机读写 |
| 文件版本 | 文件系统 | `rooms/<id>/files/work-snapshots/` | 写: AI产出时 / 读: 点版本号时 |
| 上传资料 | 文件系统 | `rooms/<id>/files/inbox/` | 写: 上传时 / 读: 预览时 |
| 自动产出 | 文件系统 | `rooms/<id>/files/archive/` | 写: Cron触发 / 读: 浏览时 |
| 文件元数据 | 文件系统 | `rooms/<id>/files/.meta/index.json` | 覆盖写 / 进房间读一次 |
| 房间列表 | localStorage | `mov_rooms_v2` | 列表页展示 (100条以内, 够用) |
| 房间配置 | 文件系统 | `rooms/<id>/.mov/config.json` | 改配置时写 / 进房间读一次 |
| AI 配置 | SharedPreferences | `mov_ai_prefs` | 键值对, 改时写 |
| Cron 配置 | SharedPreferences | `mov_cron_jobs` | JSONArray, 任务变更时写 |
| 技能 | SharedPreferences | `mov_skills` | JSONArray |
| 看板应用 | localStorage | `mov_board_apps_v1` | 列表, <20条 |
| 应用数据 | 文件系统 | `board-apps/<app-id>/data/` | 应用自己管 |
| 模板 | 文件系统 | `templates/` + 元数据 `templates/.meta/index.json` | 跨房间 |
| 统计(匿名) | SharedPreferences | `mov_stats` | 内存累积 + 定期上报 |
| 信号数据 | SharedPreferences | `mov_signals` | 键值对, 行为触发时写 |

---

## 5. 读的性能

### 5.1 进房间

```
步骤1: B.readFile(roomId, "files/.meta/index.json")  → 一次读, ~5KB
  → 拿到: 所有文件结构 + 版本摘要 + 标签 + 统计

步骤2: B.readFile(roomId, "chat/" + today + ".json")  → 一次读, ~40KB  
  → 拿到: 今天的聊天记录

步骤3: 渲染 UI
  → 文件 tab: 直接从 index.json 渲染 (不需要读具体文件)
  → 聊天 tab: 显示今天的消息
  → 用户向上翻: 再加载昨天的 chat 文件

总耗时: 2 次文件读 ≈ 20ms (本地磁盘)
```

### 5.2 点开一个文件

```
步骤1: index.json 已经在内存里 → 显示文件元数据 + 版本列表 (0ms)
步骤2: 用户点"查看内容" → B.readFile(roomId, "files/work/src/Login.tsx") → 读当前版本
步骤3: 用户点"v2" → B.readFile(roomId, "files/work-snapshots/Login.tsx/v2.tsx") → 读历史版本

总耗时: 1 次文件读 ≈ 5ms
```

### 5.3 搜索

```
用户输入: "登录" → 遍历 chat/<date>.json (暴力grep) 
                   + index.json 里的 tags 和文件名匹配

索引优化 (以后做):
  chat/search.idx → 倒排索引 {"登录": ["msg_123","msg_456"], ...}
  构建时机: 每天或每小时, 扫描新消息增量更新
```

### 5.4 对比现在

```
现在:
  进房间 → localStorage.getItem(STORE_KEY) → 全量 JSON 解析 → 5MB → 卡 200ms
  查看文件 → B.listRoomFiles → Java 遍历目录 → 每次刷新

改后:
  进房间 → 2 次文件读 → 一次 ~5KB + 一次 ~40KB → 20ms
  查看文件 → index.json 已在内存 → 0ms 拿到结构
```

---

## 6. 数据迁移 (从 v3.1 到 v4.0)

```
v3.1 现状:
  - 房间列表 + 消息: localStorage (mov_rooms_v2)
  - 文件: /sdcard/mov/rooms/<id>/ 散落
  - 配置: SharedPreferences

v4.0 迁移:
  1. 保留 localStorage 房间列表 (轻量, 够用)
  2. 迁移消息: 从 localStorage msgData → chat/<date>.json
     - 每个房间的 msgData → 按时间分组 → 写入每日文件
     - 构建 index.json
     - 迁移完成后, 清空 localStorage 里的 msgData
  3. 迁移文件: 扫描现有 /sdcard/mov/rooms/ → 生成 index.json
  4. SharedPreferences 不变 (配置数据不需要迁移)

迁移触发: 首次打开 v4.0 时自动执行, 不提示用户。
迁移失败: 保留原数据, 记录日志, 不阻塞使用。
```

---

## 7. 需要改的文件

| 文件 | 说明 |
|------|------|
| `js/store.js` | 房间模型改: msgData 从房间对象中移除, 改为 `rooms/<id>/chat/` 文件 |
| `js/chat.js` | push → append 到 chat 文件; enterRoom → 加载 chat 文件 + index.json |
| `js/files.js` | 重构: 基于 index.json 渲染, 不再每次都 listRoomFiles |
| `js/assets/meta.js` | **新建**: index.json 的读写/更新/查询 |
| `CapabilityExecutor.java` | 不变 (文件 API 通用) |
| `HermesActivity.java` | 桥方法不变 |
