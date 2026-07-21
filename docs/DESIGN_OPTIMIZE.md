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
