# PLAN: 验收缺口修复 + 分工

版本: v2.0
日期: 2026-07-22
来源: 多模型闭环验收报告

---

## 当前完成状态

| 模块 | 状态 |
|------|------|
| ModelRegistry + ModelConfig | ✅ |
| CouncilClient 并行调用 | ✅ |
| AiClient 支持 ModelConfig | ✅ |
| JS bridge 模型方法 | ✅ |
| store.js 多模型头像兼容 | ✅ |
| **StorageManager 改实例** | ⚠️ 改了一半, 编译报错 |
| **bridge/ 目录** | ⚠️ BaseBridge 存在, 编译报错 |

---

## 程序员 A: Java 层 (后端 + 安全)

### A1: 修编译错误 (必须先做)

| 文件 | 问题 | 改法 |
|------|------|------|
| `HermesActivity.java` | `StorageManager.listWorkFiles(...)` 静态调用 | StorageManager 已改实例 → 改为 `storageManager.listWorkFiles(...)` |
| `HermesActivity.java` | 同上 15+ 处 | 全部改实例调用 |
| `bridge/BaseBridge.java` | `activity.evalJsPublic(script)` 不存在 | 改为 `activity.evalJs(script)` |

### A2: 完成 ScopedStorage 迁移

`StorageManager` 实例化已完成。继续：

| 任务 | 文件 |
|------|------|
| 旧路径 `/sdcard/mov/` → `context.getExternalFilesDir(null)/mov/` | `StorageManager.java` |
| `CapabilityExecutor` 的 `ROOMS_BASE` 也改用 StorageManager 路径 | `CapabilityExecutor.java` |
| 首次启动检查旧路径 → 有数据且新路径为空 → 复制迁移 | `StorageManager.java` 加 `migrateIfNeeded()` |

### A3: Widget 权限

| 任务 | 文件 |
|------|------|
| receiver 加 `android:permission="com.hermes.android.permission.EXECUTE_WIDGET"` | `AndroidManifest.xml` |
| `executeCommand()` 加指令白名单 (只允许 14 个预置快捷指令) | `HermesWidgetProvider.java` |

### A4: Process 泄漏

| 任务 | 文件 |
|------|------|
| 5 个 `Runtime.exec()` 方法加 `finally { proc.destroy(); }` | `CapabilityExecutor.java` |

### A5: 完成 Bridge 拆分

| 任务 | 文件 |
|------|------|
| 补全 `bridge/` 下 8 个子 Bridge | `bridge/BridgeDevice.java` `BridgeAi.java` `BridgeFile.java` `BridgeCron.java` `BridgeSkill.java` `BridgeModel.java` `BridgeTemplate.java` `BridgeNote.java` |
| `BridgeFactory` 聚合注册 | `bridge/BridgeFactory.java` |
| `HermesActivity` 改用 BridgeFactory | `HermesActivity.java` |

---

## 程序员 B: 前端层 (JS + HTML + CSS)

### B1: 新建房间模型勾选 UI

| 任务 | 文件 |
|------|------|
| 第二步 "拉 AI 团队" → 从 `B.listModels()` 动态生成勾选列表 | `hermes-shell.html` + `js/app.js` |
| 用户勾选的模型 ID 存入 `room.members.ai` | `js/app.js` |
| 未配置的模型灰显 + 标注"未配置" | `js/app.js` |
| 默认模型预勾选 | `js/app.js` |

### B2: 运行页模型区

| 任务 | 文件 |
|------|------|
| 模型区改为遍历 `B.listModels()` 渲染每张模型卡片 | `js/runtime.js` |
| 每张卡片: 名称 + provider + 状态点 + 今天调用次数 + 延迟 | `js/runtime.js` + `css/shell.css` |
| 点击卡片 → 跳设置页 / toast 详细统计 | `js/runtime.js` |
| 无模型时显示"暂无模型, 去设置页添加" | `js/runtime.js` |

### B3: 第 3 层执行 UI

| 任务 | 文件 |
|------|------|
| Council 汇总后, `nextSteps` JSON 渲染为步骤列表卡片 | `js/chat.js` + `js/render.js` |
| "批准并执行"按钮 → 逐条执行 → 工具卡片显示结果 | `js/chat.js` |
| 全部完成后 → 交付物卡片 | `js/chat.js` + `js/render.js` |
| 设置开关: 自动执行 / 手动审批 | `js/app.js` + `hermes-shell.html` |

### B4: XSS 修复

| 任务 | 文件 |
|------|------|
| `mkMsg()` 气泡改用 `textContent` + 安全 `<code>` 渲染 | `js/render.js` |
| 加 `sanitize()` 函数 (去 script + 事件处理器) | `js/render.js` |
| `loadBoardApp()` 加 URL 协议校验 (`http:` / `https:` only) | `js/board.js` |

### B5: 文档死引用

| 任务 | 文件 |
|------|------|
| 删除 CLAUDE.md 和 README.md 中已删除文档的引用 | `CLAUDE.md` `README.md` |
| 版本号统一 | `app/build.gradle` `MOV_MASTER.md` |

---

## 执行顺序

```
A1 (修编译) ← 必须先做, 阻塞所有后续
  ↓
A2 (ScopedStorage) + B1 (模型勾选UI) ← 并行
A3 (Widget)   + B2 (运行页模型区)    ← 并行
A4 (Process)  + B3 (执行UI)          ← 并行
A5 (Bridge)   + B4 (XSS)             ← 并行
                B5 (文档)            ← 随时可做
```
