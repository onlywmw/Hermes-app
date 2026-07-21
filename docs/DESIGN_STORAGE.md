# DESIGN: MOV 存储架构

版本: v2.0
日期: 2026-07-22
状态: 📐 design-ready

---

## 为什么这份文档重要

存储是 MOV 的基石。存储设计错了，三个后果无法挽回：

1. **性能崩塌** — 读一个房间要 200ms，十个房间就卡死。数据越多越慢，用户跑光。
2. **扩展锁死** — 今天房间文件是扁平的，明天想加版本管理 → 所有旧数据要迁移。迁移是噩梦。
3. **数据丢失** — SharedPreferences 存聊天记录 → 超 5MB 静默截断 → 用户聊天历史没了。

好的存储设计不是"现在跑得动就行"——是**一年后数据量涨了 100 倍，照样跑得动**。

---

## 1. 设计原则

### 1.1 存储层选择铁律

| 数据特征 | 去哪 | 为什么 |
|---------|------|--------|
| 会持续增长 (聊天、文件、日志) | **文件系统** | localStorage 5MB 上限。SharedPreferences 不是数据库 |
| 键值配置、偏好、开关 | **SharedPreferences** | 适合小 KV、写入少 |
| 轻量列表 (<100 条, 展示用) | **localStorage** | 同步读、跨 WebView 可用 |
| 二进制 (图片、音频、PDF) | **文件系统** | 唯一能存二进制的层 |

### 1.2 不可违背

1. **文件系统是主存储。** 所有"数据会增长"的东西——聊天、文件、版本、产出、资料——必须去文件系统。
2. **localStorage 只做缓存和轻量列表。** 房间列表、看板应用列表。不超过 2KB。
3. **SharedPreferences 只存配置。** AI 配置、Cron 任务、语言、权限。不超过 5KB。
4. **一份元数据文件描述整个目录。** 不拆散。不每个文件一个 .json。
5. **读优先。** 写是一次性的。读是每次打开都发生的。宁可写慢 10ms，不要读慢 200ms。

---

## 2. 完整存储地图

```
/sdcard/mov/
│
├─ .mov/                          ← 设备级
│   ├─ device.json                ← 安装ID + 版本 + 语言 + 主题
│   └─ stats.json                 ← 匿名统计缓存 (上报前暂存)
│
├─ users/                         ← 用户级
│   ├─ index.json                 ← 所有本地用户 + 当前用户ID
│   └─ <user-id>/
│       ├─ profile.json           ← 名称 + 头像颜色
│       └─ prefs.json             ← 个人偏好 (覆盖全局设置)
│
├─ templates/                     ← 模板 (跨房间)
│   ├─ index.json                 ← 模板清单 + 元数据
│   └─ <name>.md                  ← 模板文件
│
├─ board-apps/                    ← 看板应用
│   └─ <app-id>/
│       └─ data/                  ← 应用自有数据 (应用自治, MOV不管)
│
└─ rooms/                         ← 房间级
    └─ <room-id>/
        ├─ chat/                  ← 聊天
        │   ├─ index.json         ← 聊天索引 (日期范围 + 文件引用映射)
        │   ├─ 2026-07-21.json
        │   └─ 2026-07-20.json
        └─ files/                 ← 文件
            ├─ work/              ← 产出 (AI生成 + 人修改, 纯文本)
            │   └─ src/Login.tsx
            ├─ work-snapshots/    ← 产出历史版本 (按需加载)
            │   └─ src-Login.tsx/
            │       ├─ v1.tsx
            │       └─ v2.tsx
            ├─ inbox/             ← 资料 (人上传, 二进制)
            │   ├─ mockup.png
            │   └─ 甲方PRD.pdf
            ├─ archive/           ← 归档 (Cron自动产出)
            │   └─ 邮件汇总/
            │       └─ 2026-07-21.md
            └─ .meta/
                └─ index.json     ← 文件元数据 (一份管全部)
```

### SharedPreferences (不走文件系统)

| Key | 内容 | 大小 | 原因 |
|-----|------|------|------|
| `mov_ai_prefs` | AI配置 (provider/key/model/prompt) | ~500B | 纯配置 |
| `mov_cron_jobs` | Cron任务 JSON | ~2KB | 配置, 很小 |
| `mov_skills` | 技能 JSON | ~5KB | 配置, 很小 |
| `mov_stats` | 统计缓存 | ~1KB | 纯数字 |
| `mov_signals` | 行为信号 | ~1KB | 纯数字+时间戳 |

### localStorage (不走文件系统)

| Key | 内容 | 大小 | 原因 |
|-----|------|------|------|
| `mov_rooms_v2` | 房间列表摘要 (不含msgData) | ~2KB | 首屏渲染, 同步读 |
| `mov_board_apps_v1` | 看板应用列表 | ~1KB | 列表 <20条 |

---

## 3. 每种数据的结构

### 3.1 设备配置

```json
// .mov/device.json (~200B)
{
  "installId": "uuid-xxx",
  "installedAt": 1721404800000,
  "version": "3.2",
  "language": "zh",
  "theme": "light"
}
```

### 3.2 本地用户

```json
// users/index.json (~200B)
{
  "users": [
    {"id": "u1", "name": "王墨微", "color": "#D97706", "created": 1721404800000}
  ],
  "current": "u1"
}

// users/u1/profile.json (~100B)
{
  "name": "王墨微",
  "color": "#D97706",
  "created": 1721404800000
}
```

### 3.3 模板

```json
// templates/index.json (~500B)
{
  "templates": [
    {
      "id": "prd-template",
      "name": "PRD 模板",
      "desc": "产品需求文档标准模板",
      "author": "you",
      "version": 2,
      "usageCount": 5,
      "file": "prd-template.md"
    }
  ]
}
```

### 3.4 聊天

```json
// rooms/<id>/chat/2026-07-21.json  (~40KB/天, 每行一条)
{"t":1721404800,"who":"you","h":"写个登录页面"}
{"t":1721404802,"who":"DeepSeek","role":"产品","h":"好的, 这是代码..."}
{"t":1721405300,"who":"you","h":"存到文件","action":"file.write","file":"src/Login.tsx"}
{"t":1721408400,"who":"张三","h":"性能不行"}
{"t":1721408402,"who":"Claude","role":"技术","h":"用懒加载解决"}
{"t":1721415600,"sys":"COUNCIL 收敛 · hermes 汇总"}
```

每行一条独立 JSON。追加写不需要解析全量。读的时候按日期文件加载。

```json
// rooms/<id>/chat/index.json (~2KB)
{
  "files": [
    {"date":"2026-07-21","count":156,"firstT":1721404800,"lastT":1721491200},
    {"date":"2026-07-20","count":89,"firstT":1721318400,"lastT":1721404799}
  ],
  "totalMessages": 4230,
  "fileRefs": {
    "src/Login.tsx": [1721405300, 1721408400, 1721415600],
    "src/utils.ts": [1721410000]
  },
  "authors": {"you":1203,"DeepSeek":1800,"Claude":800,"张三":427}
}
```

### 3.5 文件元数据

```json
// rooms/<id>/files/.meta/index.json (~3KB, 一个房间的文件清单)
{
  "work": {
    "src/Login.tsx": {
      "currentVersion": 4,
      "lock": null,
      "createdBy": "DeepSeek",
      "createdAt": 1721404800000,
      "createdIn": "msg_abc123",
      "versions": [
        {"v":1,"author":"DeepSeek","at":1721404800000,"msg":"首次生成","chatLink":"msg_abc123"},
        {"v":2,"author":"Claude","at":1721408400000,"msg":"review修改","chatLink":"msg_def456"},
        {"v":3,"author":"you","at":1721412000000,"msg":"手动改","parent":2},
        {"v":"4a","author":"DeepSeek","at":1721415600000,"parent":3,"branch":true},
        {"v":"4b","author":"Claude","at":1721415600000,"parent":3,"branch":true}
      ]
    }
  },
  "inbox": {
    "mockup.png": {
      "uploadedBy":"张三","uploadedAt":1721404800000,"size":438272,"mime":"image/png",
      "tags":["参考截图","甲方"],"referencedIn":["msg_abc123"],
      "aiAnalyzed":true,"aiSummary":"手机截图, 展示电商首页布局"
    }
  },
  "archive": {
    "邮件汇总": {
      "source":"cron","sourceId":"cron_abc","retention":"30days",
      "files":[
        {"name":"2026-07-21.md","date":"2026-07-21","size":2048,"status":"ok"},
        {"name":"2026-07-20.md","date":"2026-07-20","size":1890,"status":"ok"}
      ]
    }
  },
  "stats": {
    "totalFiles":12,"totalSize":15242880,"lastModified":1721415600000,
    "byAuthor":{"DeepSeek":5,"Claude":3,"you":2,"张三":2}
  }
}
```

### 3.6 房间配置

```json
// rooms/<id>/.mov/config.json (~500B)
{
  "id": "r1734567890",
  "name": "产品 V2.0",
  "desc": "讨论新版本功能优先级",
  "mode": "council",
  "members": {
    "human": [{"who":"you","role":"owner"},{"who":"张三","role":"member"}],
    "ai": [{"provider":"deepseek","model":"v4-flash","role":"产品"},
           {"provider":"claude","model":"opus","role":"技术"}]
  },
  "created": 1721404800000,
  "phase": "讨论中"
}
```

---

## 4. 读写路径

### 4.1 打开 MOV

```
步骤1: B.readFile("", ".mov/device.json")        → 语言 + 版本
步骤2: B.readFile("", "users/index.json")        → 当前用户
步骤3: localStorage.getItem("mov_rooms_v2")      → 房间列表 (首屏)
```

### 4.2 进入一个房间

```
步骤1: B.readFile(roomId, "files/.meta/index.json")    → ~3KB, 文件结构
步骤2: 读今天的 chat 文件:
        today = "2026-07-22"
        B.readFile(roomId, "chat/" + today + ".json")  → ~40KB
步骤3: 渲染
```

### 4.3 发一条消息

```
步骤1: 构造行 JSON → {"t":...,"who":...,"h":...}
步骤2: B.appendFile(roomId, "chat/2026-07-22.json", line)  ← 追加一行
步骤3: 更新 chat/index.json (lastT + count + fileRefs)
步骤4: 更新 localStorage 房间列表摘要 (last 消息 + time)
```

### 4.4 AI 产出文件

```
步骤1: B.writeFile(roomId, "files/work/src/Login.tsx", content)
步骤2: 旧版本 → B.writeFile(roomId, "files/work-snapshots/src-Login.tsx/v3.tsx", oldContent)
步骤3: 更新 files/.meta/index.json (版本 +1, stats 更新)
步骤4: 在消息里插入文件链接
```

### 4.5 搜索"登录" (跨房间)

```
步骤1: 遍历 rooms/*/files/.meta/index.json → 匹配文件名和标签
步骤2: 遍历 rooms/*/chat/*.json → 暴力 grep 消息内容
步骤3: 合并结果

暴力搜索在 <20 房间, <100 聊天文件时完全够用。
以后加倒排索引: rooms/*/chat/search.idx
```

---

## 5. 数据关系图

```
device.json ──────────────────────────────────────────── (1:1 设备)
  │
users/index.json ─────────────────────────────────────── (1:N 用户)
  │
  ├─ templates/index.json ────────────────────────────── (1:N 模板, 跨房间)
  │
  └─ rooms/<id>/                                         (1:N 房间)
       │
       ├─ chat/                                          (1:N 天)
       │   ├─ index.json ← 哪天的文件有哪条消息
       │   └─ <date>.json ← 引用 files 里的文件
       │
       ├─ files/
       │   ├─ work/          ← 版本链在 .meta/index.json
       │   ├─ work-snapshots/ ← 按 .meta 里的版本号读取
       │   ├─ inbox/         ← 上传者 + 标签在 .meta
       │   └─ archive/       ← Cron 来源在 .meta
       │
       └─ .mov/config.json   ← 成员 + 模式 + 阶段
```

---

## 6. SharedPreferences 地图

| Key | 结构 | 读场景 | 写场景 |
|-----|------|--------|--------|
| `mov_ai_prefs` | provider/baseUrl/key/model/prompt/enabled | 每次 AI 调用、设置页 | 设置页保存、测试连接 |
| `mov_cron_jobs` | JSONArray of {id,name,cronExpr,...} | 进运行 tab、Cron 执行时 | 创建/切换/删除 Cron |
| `mov_skills` | JSONArray of {id,name,desc,...} | 进运行 tab 技能区、技能触发时 | 删除技能、recordUse |
| `mov_stats` | aiCalls/totalMs/maxMs/errors/sessions | 后台线程检查是否该上报 | AI调用后、会话结束时 |
| `mov_signals` | firsts/prompts/ai quality | 每次操作后评估触发条件 | 命令执行后、AI调用后 |

### 为什么这些留在 SharedPreferences 不搬去文件

- 纯键值或小 JSON，<5KB
- 频繁写，SharedPreferences 的 `apply()` 是异步非阻塞的
- 不需要版本管理，不需要共享
- 换设备不需要迁移（用户在新设备重新配）

---

## 7. localStorage 地图

| Key | 结构 | 大小上限 | 为什么不能用文件 |
|-----|------|---------|----------------|
| `mov_rooms_v2` | [{id,name,mode,members,phase,last,time,unread}] | ~2KB (20个房间) | 首屏渲染需要同步读。`B.readFile` 异步回调 → 首屏会闪白 |
| `mov_board_apps_v1` | [{id,name,icon,type,source}] | ~1KB (<20个应用) | 打开看板 tab 需要即时渲染，不能等异步 |

### 首屏渲染的特殊性

```
WebView 加载 hermes-shell.html
  → JS 初始化
  → renderRooms() 必须即时拿到房间列表
  → 等 B.readFile 异步回调 → 页面闪白 → 不可接受
  → localStorage 同步读 → 即时渲染
```

**MsgData 已从 localStorage 移除** (v4.0 迁移)。房间列表摘要保留——它很小，永不超限。

---

## 8. 数据生命周期

```
数据                         创建              删除              清理
────────────────────────────────────────────────────────────────────────
聊天消息      每发一条追加     长按删除单条      不会自动删
                             清空房间全删
聊天文件      每天新建        清空房间          chat/ 下保留所有 .json
房间文件      用户存/AI产出   房间创建者删      不会自动删
文件快照      AI覆盖时生成    删文件时清空      版本链保留
上传资料      用户上传        上传者/创建者删   不会自动删
Cron产出      Cron触发        手动/保留策略    按策略自动清理
设备配置      首次启动        重置应用         从不
用户          创建用户        删除用户         用户数据一起删
模板          用户创建        创建者删         从不
房间          创建房间        创建者删         文件 + 聊天全删
看板应用      用户添加        用户删           应用数据一起删
统计 + 信号  操作触发         重置应用         每天聚合后上报
```

---

## 9. 性能目标

| 操作 | 目标延迟 | 当前(v3.1) | 改后(v4.0) | 实现 |
|------|---------|-----------|-----------|------|
| 打开 MOV → 首屏 | <100ms | ~50ms | ~50ms | 不变 (localStorage 读) |
| 进入房间 (20个文件, 今日156条消息) | <50ms | ~200ms | <30ms | 2次文件读 → index.json + 今日chat |
| 打开文件 tab | <10ms | ~50ms | 0ms | index.json 已在内存 |
| 点开文件内容 (2KB) | <10ms | ~10ms | <10ms | 不变 |
| 发一条消息 | <50ms | ~20ms | <50ms | 追加一行 → 更新聊天索引 |
| 搜索"登录" (5房间, 3000条消息) | <500ms | 不支持 | <200ms | 暴力 grep (以后加索引) |
| 导出房间 (ZIP) | <2s | 不支持 | <1s | 打包 rooms/<id>/ 目录 |

---

## 10. v3.1 → v4.0 数据迁移

### 迁移触发

首次启动 v4.0 时自动执行。不提示用户。迁移完成后写标记 `migrated_to_v4: true` 到 device.json。

### 迁移步骤

```
步骤1: 检查 device.json 是否存在
  → 不存在 = 全新安装 → 跳过迁移

步骤2: 检查 migrated_to_v4 标记
  → 已存在 → 跳过

步骤3: 迁移聊天消息
  for each room in localStorage rooms_v2:
    if room.msgData 存在:
      按时间戳分组 → 每天一个文件
      write rooms/<id>/chat/<date>.json
      构建 rooms/<id>/chat/index.json
      清空 room.msgData ← 只清空, 不删 key
  ← 立即刷新房间列表, 移除 msgData 引用

步骤4: 迁移文件
  for each room with /sdcard/mov/rooms/<id>/:
    扫描 files/ 目录
    构建 files/.meta/index.json

步骤5: 写迁移标记
  device.json: {"migrated_to_v4": true, "migratedAt": now}

步骤6: 出错处理
  任一步骤失败 → 保留原数据 → 记录到 device.json error 字段
  → 下次启动跳过已成功的步骤
```

---

## 11. 设计决策记录

| 决策 | 结论 | 原因 |
|------|------|------|
| 聊天消息: localStorage → 文件系统 | ✅ 搬家 | 保留房间列表摘要; msgData 太长且会超 5MB |
| 文件元数据: 每文件一个 JSON → 每房间一个 index.json | ✅ 合并 | 进房间需要 N 次读 → 1 次读 |
| 聊天: 一个巨 JSON → 每天一个文件 | ✅ 拆分 | 追加写不需要全量反序列化 |
| SharedPreferences 保留 AI/Cron/技能 | ✅ 保留 | <5KB, 高频写, 异步 apply |
| 用户/模板/设备配置: SharedPreferences → 文件 | ✅ 搬家 | 更透明, 可备份, 可迁移 |
| 房间列表: 留在 localStorage | ✅ 保留 | 首屏同步读, <2KB |

---

## 12. 需要改的文件

| 文件 | 说明 |
|------|------|
| `CapabilityExecutor.java` | 新增 `appendFile` 能力 (追加一行到文件) |
| `HermesActivity.java` | 桥方法: `appendFile`, `listRoomFiles` 返回增强JSON |
| `js/assets/meta.js` | **新建** — index.json 读写更新查询 |
| `js/chat.js` | push → appendFile; enterRoom → 读 chat 文件 + index.json |
| `js/files.js` | 重构: 基于 index.json 渲染, 不再每次 listRoomFiles |
| `js/store.js` | 房间模型去掉 msgData; 迁移逻辑 |
| `js/migrate.js` | **新建** — v3 → v4 迁移脚本 |
