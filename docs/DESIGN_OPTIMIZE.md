# DESIGN: 优化方案

版本: v1.0
日期: 2026-07-22
状态: 📐 design-ready

---

## 当前状态

多模型核心闭环已完成。ModelRegistry、Council 并行调用、五种存储、看板、交互系统——全部在跑。

这份文档列的是**打磨**——不改架构，不改变用户流程，只做让产品更稳、更快、更干净的事。

---

## 1. 运行页 — 加个人信息 & 精简

### 问题

运行页偏技术感。缺少用户身份感。pid/JVM 内存对普通用户无意义。

### 方案

运行页顶部加紧凑的个人区域，然后才是 AI 团队仪表盘。

```
┌─ 运行 ────────────────────────────────┐
│                                        │
│  ● 王墨微 · 本地用户          [≡ 设置] │  ← 新增: 个人区域
│                                        │
│  ┌── AI 团队 ─────────────────────────┐ │
│  │ 🟢 DeepSeek V4   今天 42 次 3.2s  │ │
│  │ 🟢 Claude Opus   今天 18 次 5.1s  │ │
│  │ 🔴 Qwen Max      未配置           │ │
│  └───────────────────────────────────┘ │
│                                        │
│  ┌── 定时任务 · 3 个 ──── [＋] ──────┐ │
│  │ 每日邮件摘要  30 8 * * *  ✅      │ │
│  │ 腾讯云账单    0 */6 * *  ✅       │ │
│  └───────────────────────────────────┘ │
│                                        │
│  ▶ 通道 · 全部正常                     │
│  ▶ 技能 · 3 个                        │
│  ▶ 权限 · 4/7 已授权                  │
│                                        │
└────────────────────────────────────────┘
```

个人信息区域：
- 头像圆点（用当前用户的颜色）+ 用户名
- 右侧 ≡ 设置入口
- 点用户名 → 切本地用户（L1 多人）

进程卡片精简：保留"MOV 运行正常 · 已运行 2h 13m"一行。pid/JVM 内存/指令计数移到设置→关于。

### 改动

| 文件 | 内容 |
|------|------|
| `hermes-shell.html` | 运行页顶部加个人区域；进程卡片精简 |
| `js/runtime.js` | 个人区域渲染；进程卡片只输出状态+时长 |
| `css/shell.css` | 个人区域样式 (~5行) |

---

## 2. 版本迁移

### 问题

v3→v4 改了存储路径、房间数据格式。旧用户升级后数据可能丢失。

### 方案

`HermesApplication.onCreate` 开头调 `MigrationManager.run(context)`。按版本号递增执行迁移脚本。

每条迁移：
1. 检查是否需要（数据版本 < 目标版本）
2. 执行
3. 写完成标记

失败不阻塞启动，记录日志。

### 改动

| 文件 | 内容 |
|------|------|
| `MigrationManager.java` | **新建** |
| `HermesApplication.java` | onCreate 加一行调用（程序员已做） |

---

## 3. app.js 拆分

### 问题

`app.js` 230 行，事件绑定、房间操作、Cron、看板全在一起。两人同时改必然冲突。

### 方案

按视图拆成独立文件，不改任何逻辑——纯搬家：

```
js/app.js        → ~30行, 入口+初始化
js/app-chat.js   → 消息发送、附件、输入框
js/app-room.js   → 新建房间 Sheet、房间操作 Sheet
js/app-board.js  → 看板事件、应用管理
js/app-run.js    → 运行页刷新、Cron 创建、折叠行
js/app-files.js  → 文件预览、新建文件、子 tab
```

加载顺序不变，依赖关系不变。

### 改动

| 文件 | 内容 |
|------|------|
| `js/app.js` | 230→~30行 |
| `js/app-chat.js` | **新建** |
| `js/app-room.js` | **新建** |
| `js/app-board.js` | **新建** |
| `js/app-run.js` | **新建** |
| `js/app-files.js` | **新建** |
| `hermes-shell.html` | 加 5 个 script 标签 |

---

## 4. i18n 旧名字清理

### 问题

`sheet.councilDesc` 残留 "claude / gpt-5 / gemini"。`council.converge` 残留 "hermes 汇总"。

### 方案

全局替换：

```
"hermes" → "MOV"
"hermes-agent" → "mov-agent"  
"claude / gpt-5 / gemini 讨论..." → "多模型各抒己见 → 汇总 → MOV 执行"
"com.hermes.android" → "原生能力引擎 · 30+ 接口"
```

### 改动

`js/i18n.js` — ~10 行。

---

## 5. 安全 & 泄漏

### 5.1 Widget 权限

`AndroidManifest.xml` — receiver 加 `android:permission` 签名级保护。

### 5.2 XSS

`render.js` — `mkMsg()` 气泡改用 `textContent`。加 `sanitize()` 函数。`board.js` — URL 协议校验。

### 5.3 Process 泄漏

`CapabilityExecutor.java` — 5 个 `Runtime.exec()` 加 `finally { proc.destroy(); }`。

### 改动

| 文件 | 内容 |
|------|------|
| `AndroidManifest.xml` | receiver 加 permission |
| `HermesWidgetProvider.java` | 指令白名单 |
| `js/render.js` | textContent + sanitize |
| `js/board.js` | URL 校验 |
| `CapabilityExecutor.java` | finally destroy |

---

## 6. 文档死引用清理

`CLAUDE.md` 和 `README.md` 不再引用已删除的设计文档。版本号统一。

---

## 实施 & 分工

| # | 内容 | 文件数 | 时间 | 谁 |
|---|------|--------|------|-----|
| 1 | 运行页个人信息+精简 | 3 | 20m | 前端 |
| 2 | 版本迁移 | 2 | 30m | 后端 |
| 3 | app.js 拆分 | 7 | 30m | 前端 |
| 4 | i18n 清理 | 1 | 5m | 前端 |
| 5 | 安全&泄漏 | 5 | 40m | 后端 (Java) + 前端 (XSS) |
| 6 | 文档清理 | 2 | 10m | 随意 |

1-6 全部独立，可并行。
# DESIGN: 威胁模型

版本: v1.0
日期: 2026-07-22

---

## 五个攻击面

### 1. API Key 泄露 — 严重度: 高

**攻击路径**: 设备 root → 读 `/data/data/com.hermes.android/shared_prefs/mov_models.xml` → 明文 Key。

**当前**: EncryptedSharedPreferences 加密存储（ModelRegistry 已做）。降级明文只在加密不可用时触发。

**缺口**: 降级明文无警告。用户不知道 Key 在以明文存储。

**缓解**: 加密不可用时不降级——直接弹 toast 告知用户"加密存储不可用，无法保存 API Key"。

---

### 2. JS 桥暴露面 — 严重度: 高

**攻击路径**: WebView 加载恶意内容 → 调用 `window.HermesBridge.writeFile("../../etc/hosts", "127.0.0.1 google.com")` → 文件写入系统路径。

**当前**: `StorageManager.isSafe()` 做了路径逃逸检查。`CapabilityExecutor.doFileWrite()` 也检查了。

**缺口**: 
- 30+ 桥方法，没有统一的参数校验层
- `writeFile` 的 content 参数无大小限制——1GB 内容可以写爆磁盘
- `execCommand` 接收任意字符串 → `IntentParser.parse()` → 执行任意设备指令

**缓解**:
1. 所有桥方法加参数校验：path 不能含 `..`、content 限制 10MB、roomId 必须匹配 `[a-zA-Z0-9_-]+`
2. `execCommand` 加危险指令黑名单：`打电话`、`发短信` 需要用户二次确认
3. iframe 只加载 `assets/board-apps/` 下的本地文件，不加载远程 URL（看板应用限制）

---

### 3. Cron 执行 AI 生成的指令 — 严重度: 高

**攻击路径**: Council 讨论中 AI 被 prompt injection → 输出恶意步骤 → Cron 自动执行。

例如：
```
用户在聊天里贴了一段网页内容（含隐藏的 prompt injection）
→ AI 汇总时被注入 → 输出 {"action":"file.write","target":"/sdcard/.evil.sh","detail":"rm -rf /"}
→ Cron 每天执行 → 持续损害
```

**当前**: 没有防护。Cron 执行 `IntentParser.parse()` → `CapabilityExecutor.execute()` 的完整链路，没有任何 AI 输出校验。

**缓解**:
1. Cron 执行前，步骤必须经过用户审批（当前"手动审批"模式是默认）
2. AI 输出的 `action` 字段加白名单——只允许 `file.write` / `notification.post` / `tts.speak`，不允许 `input.tap` / `input.swipe` / 任何 shell 命令
3. `file.write` 的 `target` 强制限制在 `/sdcard/mov/rooms/<roomId>/files/` 下，且只能写 `.md` `.txt` `.json` `.html` `.tsx` `.ts` `.js` `.css` 白名单后缀

---

### 4. 文件路径遍历 — 严重度: 中

**攻击路径**: 用户添加的看板应用中 iframe 通过 JS 桥写入恶意路径。

**当前**: `isSafe()` 检查。`listRoomFiles` 过滤 `.mov` 隐藏目录。

**缺口**: 新加的 SQLite `dbExec` 桥方法如果拼接字符串可能 SQL 注入。

**缓解**: `dbExec` 用参数化查询（`?` 占位符），Java 层 `SQLiteDatabase.rawQuery` 不接受拼接。

---

### 5. 数据泄漏 — 严重度: 中

**攻击路径**:
- APK 反编译 → 拿到 Supabase anon key → 灌假数据
- ADB 备份 → 拿到整个应用数据
- 旧设备出售 → 数据未清除

**当前**: 遥测系统设计了但端点未配置。Supabase key 未写入。

**缓解**:
1. 砍掉遥测（用户量没到需要的时候）
2. AndroidManifest 加 `android:allowBackup="false"`
3. 设备出售/重置：`/sdcard/mov/` 物理删除（手动操作即可）

---

## 缓解优先级

| # | 措施 | 工作量 | 立即做？ |
|---|------|--------|---------|
| 1 | API Key 加密不降级 | 3行 | ✅ |
| 2 | 桥方法统一参数校验 | 30行 | ✅ |
| 3 | iframe 只加载本地文件 | 5行 | ✅ |
| 4 | Cron 步骤白名单 + 审批 | 20行 | ✅ |
| 5 | `dbExec` 参数化查询 | 设计已包含 | ✅ |
| 6 | 砍掉遥测 | 删文件 | ⚠ 改天 |
| 7 | `allowBackup="false"` | 1行 | ✅ |
