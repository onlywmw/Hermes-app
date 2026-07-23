# CONTRACT: 架构总纲

版本: v1.0
日期: 2026-07-22
status: design-ready
交付对象: 全体程序员

---

## MOV 是什么

Android 手机上，拉多个 AI 模型进房间一起干活。

两个 tab：**会话**（房间列表 + 聊天 + 文件）和 **运行**（设备状态 + AI 模型 + Cron）。看板已删除（P2），不再存在。

---

## 技术边界（不可违反）

1. **平台：Android 手机，API 26+，targetSdk 36。** WebView + 纯 HTML/CSS/JS 前端。无前端框架。
2. **存储根路径：`context.getExternalFilesDir(null) + "/mov/"`。** 禁止 `/sdcard/mov/`。
3. **所有持久化 key 变更必须走 `MigrationManager`。** 无例外。
4. **所有桥方法参数必须过 `BridgeValidator`。** 无例外。
5. **所有 AI 文件写入必须经用户确认，无例外。确认粒度 = 计划闸（DESIGN_AGENT_LOOP）：批准计划即授权计划内列出的文件路径；执行段计划外路径由代码硬拒绝；每次写入记录工作日志。**
6. **Cron 只能执行白名单 action。** 无例外。
7. **JS 文件加载顺序 = 依赖顺序。** `i18n → store → bridge → render → chat → files → runtime → app-chat → app-room → app-files → app-run → app`。
8. **两个 view（view-rooms, view-room）在会话 tab 中切换，view-run 独立。** 切换通过 CSS `.act` class，不销毁重建。

---

## 文件清单

### Java (39 文件)

| 文件 | 职责 |
|------|------|
| `HermesActivity.java` | WebView 壳 + JS 桥注册 |
| `HermesApplication.java` | 启动初始化 + 迁移触发 |
| `CapabilityExecutor.java` | 30 个设备+文件能力 |
| `IntentParser.java` | 自然语言 → ParsedCommand |
| `StorageManager.java` | 五种存储核心逻辑 |
| `MigrationManager.java` | 数据版本迁移 |
| `ModelRegistry.java` | 多模型注册中心 |
| `ModelConfig.java` | 模型配置数据类 |
| `CouncilClient.java` | 多模型并行讨论 |
| `AiClient.java` | OpenAI 兼容 HTTP 客户端 |
| `AiProviderConfig.java` | 旧版单模型配置（保留兼容） |
| `CronManager.java` | WorkManager 调度 |
| `HermesCronWorker.java` | Cron 执行 Worker |
| `SkillStore.java` | 技能 CRUD |
| `BridgeValidator.java` | 桥参数统一校验 |
| `HermesWidgetProvider.java` | 桌面小组件 |

### 前端 (17 文件)

| 文件 | 职责 |
|------|------|
| `hermes-shell.html` | UI 骨架 |
| `css/shell.css` | 设计系统 |
| `js/store.js` | 数据层 + 持久化 |
| `js/i18n.js` | 中英双语（只维护中文） |
| `js/bridge.js` | HermesBridge 封装 |
| `js/render.js` | DOM 渲染 |
| `js/chat.js` | 消息路由 + 长按设施 |
| `js/files.js` | 文件树 + 预览 + 版本 |
| `js/runtime.js` | 运行页仪表 |
| `js/app.js` | 入口初始化 |
| `js/app-chat.js` | 聊天事件绑定 |
| `js/app-room.js` | 新建房间 + 房间操作 |
| `js/app-files.js` | 文件事件绑定 |
| `js/app-run.js` | 运行页事件绑定 |

---

## 启动流程

```
HermesApplication.onCreate
  → StorageManager.init(context)     ← 设置存储根路径
  → MigrationManager.run(context)    ← 数据迁移（如果需要）

HermesActivity.onCreate
  → setContentView(WebView)
  → WebView.loadUrl("hermes-shell.html")
  → JS 按序加载 → app.js 初始化
    → initLang → renderRooms → setTab('chat')
```

---

## 施工合同索引

| 合同 | 内容 | 交付对象 |
|------|------|---------|
| `CONTRACT_STORAGE.md` | 存储系统 | 后端 |
| `CONTRACT_MODEL.md` | 多模型系统 | 后端 + 前端 |
| `CONTRACT_ROOM.md` | 房间系统 | 前端 |
| `CONTRACT_RUNTIME.md` | 运行页 | 前端 |
| `CONTRACT_SECURITY.md` | 安全 | 后端 + 前端 |
| `CONTRACT_ARCH.md` | 架构总纲（本文档） | 全体 |

---

## E2E 自动化测试

`tools/e2e/` 目录包含基于 Chrome DevTools Protocol 的自动化测试脚本 (node + ws)。

- `driver*.js` — 页面按钮遍历 + 状态断言
- `mov-models-verify.js` — 多模型链路验证
- 测试报告在 `tools/e2e/reports/`
- 新增用例: 复制现有 driver 脚本, 修改测试步骤

---

## 关联合同

- [CONTRACT_STORAGE.md](CONTRACT_STORAGE.md)
- [CONTRACT_SECURITY.md](CONTRACT_SECURITY.md)
- [CONTRACT_MODEL.md](CONTRACT_MODEL.md)
- [CONTRACT_ROOM.md](CONTRACT_ROOM.md)
- [CONTRACT_RUNTIME.md](CONTRACT_RUNTIME.md)
- [CONTRACT_ZINDEX.md](CONTRACT_ZINDEX.md)
