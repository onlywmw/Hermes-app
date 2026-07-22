# MOV — 多模型协作工作台

status: active

> **定义**: 运行在 Android 手机上的多模型协作工作台。人和 AI 在项目房间里共同工作，产出文件。能直接操控设备。

**完整项目文档**: [docs/CONTRACT_ARCH.md](docs/CONTRACT_ARCH.md)

---

## Tab 结构

```
会话  │  运行
──────┼──────
AI 协作│系统 & 模型
项目房间│设备 & Cron
```

## 核心架构

```
MOVActivity (WebView 壳)
  └─ hermes-shell.html (全 UI, 3 个 view + sheets)
       ├─ MOVBridge (30+ 个 @JavascriptInterface)
       │   ├─ IntentParser → CapabilityExecutor (34 个能力)
       │   ├─ AiClient (OpenAI 兼容)
       │   ├─ CouncilClient (多角色讨论)
       │   ├─ CronManager (WorkManager)
       │   └─ SkillStore (技能 CRUD)
       └─ B (JS 桥封装)
```

## 不可变规则

1. **指令优先路由** — IntentParser 命中 → 执行; 未命中 → AI
2. **房间 = 文件仓库** — 文件操作限制在房间目录内
3. **核心交互 WebView，重性能场景走原生** — 版本树 diff、PDF 预览、文件编辑器 → 原生 Activity
4. **长按 = 右键菜单** — 500ms 触发, 移动 >10px 取消
5. **Sheet 替代弹窗** — 不用 prompt()/confirm()
6. **所有持久化 key 变更走 MigrationManager** — 不再有 key 不可变规则

## 交互规范

| 模式 | 场景 | 行为 |
|------|------|------|
| 点击 | 进入/打开 | 直接执行 |
| 长按 | 删除/操作 | 高亮 → 底部黑条 → 点击执行 |
| Sheet | 创建/编辑/确认 | 遮罩 + 面板滑入, ✕ 关闭 |
| Toast | 操作结果 | 短暂弹出 |

## 文件清单

[Java 17 文件] [JS 12 文件] [HTML 1] [CSS 1] — 详见 MASTER.md §10

## 语言

只维护中文。`js/i18n.js` 英文 key 保留不更新。除非确定出海，否则不做 i18n。

---

## 当前待办

| 优先级 | 事项 |
|--------|------|
| ✅ | 看板 tab 已删除 (P2) |
| ✅ | 技能合并到运行页 |
| ✅ | 多模型管理 (ModelRegistry + Council) |
| ✅ | 运行页个人信息 + 精简 (DESIGN_OPTIMIZE §1) |
| ✅ | i18n 旧名字清理 |
| ✅ | 安全加固 (allowBackup/false + 加密警告 + 文件大小限制) |
| 🔵 | SQLite 存储升级 (DESIGN_SQLITE.md, draft) |
| 🔵 | 伪流式 Council (DESIGN_HYBRID.md) |
| 🔵 | 遥测移除 (StatsCollector) |
