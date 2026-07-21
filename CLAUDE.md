# MOV — 多模型协作工作台

> **定义**: 运行在 Android 平板上的多模型协作工作台。人和 AI 在项目房间里共同工作，产出文件。有看板运行轻应用，能直接操控设备。

**完整项目文档**: [docs/MOV_MASTER.md](docs/MOV_MASTER.md)

---

## Tab 结构

```
会话  │  看板(🔵)  │  运行
──────┼───────────┼──────
AI 协作│轻应用面板  │系统 & 技能
项目房间│Dock + Web │设备 & Cron
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

1. **WebView 壳 + JS 桥模型** — 不可改为纯原生 UI
2. **指令优先路由** — IntentParser 命中 → 执行; 未命中 → AI
3. **房间 = 文件仓库** — 文件操作限制在 `/sdcard/mov/rooms/<id>/`
4. **长按 = 右键菜单** — 500ms 触发, 移动 >10px 取消
5. **Sheet 替代弹窗** — 不用 prompt()/confirm()
6. **SharedPreferences key 名不可变** — mov_legacy_ai_prefs / mov_legacy_cron_jobs / mov_legacy_skills
7. **localStorage key 不可变** — mov_legacy_rooms_v2 / mov_legacy_board_apps_v1

## 交互规范

| 模式 | 场景 | 行为 |
|------|------|------|
| 点击 | 进入/打开 | 直接执行 |
| 长按 | 删除/操作 | 高亮 → 底部黑条 → 点击执行 |
| Sheet | 创建/编辑/确认 | 遮罩 + 面板滑入, ✕ 关闭 |
| Toast | 操作结果 | 短暂弹出 |

## 文件清单

[Java 17 文件] [JS 12 文件] [HTML 1] [CSS 1] — 详见 MASTER.md §10

## 当前待办

| 优先级 | 事项 |
|--------|------|
| 🔴 | 文件上传修复 (见 docs/DESIGN_SECURITY.md) |
| 🔴 | 文件预览修复 |
| 🔴 | _filesPath 重置 |
| 🔵 | 看板 tab 实现 (见 docs/DESIGN_BOARD_V2.md) |
| 🔵 | 技能 tab 合并到运行 |
| 🔵 | 多模型管理 |
