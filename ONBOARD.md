# MOV 新成员上手指南

---

## MOV 是什么

Android 手机上，拉多个 AI 模型进房间一起干活。两个 tab：**会话**（房间+聊天+文件）和 **运行**（设备状态+AI 模型+Cron）。

---

## 5 分钟跑起来

```bash
# 构建
./gradlew assembleDebug

# 安装到手机 (设备 21770d7d)
adb -s 21770d7d install -r app/build/outputs/apk/debug/app-debug.apk

# 启动
adb -s 21770d7d shell am start -n com.hermes.android/.HermesActivity

# 看日志
adb -s 21770d7d logcat -s MOV:D

# 跑测试
./gradlew test
```

---

## 代码在哪

```
app/src/main/java/com/hermes/android/   ← Java 后端
  HermesActivity.java     WebView 壳 + JS 桥注册 (桥实现见 bridge/)
  bridge/                 BridgeFactory 聚合 6 个子桥 (68 个 @JavascriptInterface)
  CapabilityExecutor.java 34 个设备+文件能力
  StorageManager.java     文件存储核心
  ModelRegistry.java      多模型注册中心
  CouncilClient.java      多模型并行讨论
  MigrationManager.java   数据版本迁移
  BridgeValidator.java    桥参数统一校验
  ai/AiClient.java        OpenAI 兼容 HTTP 客户端
  cron/                   WorkManager 定时任务
  model/                  模型配置数据类
  skill/                  技能 CRUD
  widget/                 桌面小组件

app/src/main/assets/      ← 前端 (WebView 内运行)
  hermes-shell.html       UI 骨架 (单文件)
  css/shell.css           设计系统
  js/i18n.js              中英双语 (只维护中文)
  js/store.js             数据层 + 持久化
  js/bridge.js            Java 桥 JS 封装 (50+ 方法)
  js/render.js            DOM 渲染
  js/chat.js              消息路由 + 长按设施
  js/skills.js            技能列表 + 搜索
  js/files.js             文件树 + 预览 + 版本
  js/runtime.js           运行页仪表
  js/app.js               入口初始化
  js/app-room.js          新建房间 + 房间操作
  js/app-chat.js          聊天事件绑定
  js/app-files.js         文件事件绑定
  js/app-run.js           运行页事件绑定

app/src/test/             测试
```

---

## 先读这 6 份合同（15 分钟）

每份都写了验收测试用例和不可违反的实现约束。按顺序读：

1. `docs/CONTRACT_ARCH.md` — 架构总纲，技术边界
2. `docs/CONTRACT_STORAGE.md` — 存储系统
3. `docs/CONTRACT_MODEL.md` — 多模型系统
4. `docs/CONTRACT_ROOM.md` — 房间系统
5. `docs/CONTRACT_RUNTIME.md` — 运行页
6. `docs/CONTRACT_SECURITY.md` — 安全

---

## 三条编码规则（PR 审不过直接打回）

1. **Java: `@JavascriptInterface` 方法必须包 try-catch，返回 `{"ok":false,"error":"..."}`。** 禁止向 JS 抛异常——JS 崩溃会导致整个 WebView 白屏。
2. **JS: 所有 DOM 内容用 `createElement` + `textContent`。** 禁止 `innerHTML` 拼接用户输入。模板字符串只用于不含用户输入的场景。
3. **命名: 房间 ID = `r{timestamp}`，模型 ID = `model_{name_lower}`。** 全项目统一，不自己发明格式。

---

## 当前任务

P0（存储根目录）、P1（AI 写文件计划闸）、P2（看板删除）均已完成。剩余：

1. **合同交叉引用** — 每份 CONTRACT 末尾加"关联合同"（仅 `CONTRACT_ARCH.md` 缺）。

---

## 不可触碰的红线

- **存储根路径是 `getExternalFilesDir`，不是 `/sdcard/mov/`。**
- **所有 AI 文件写入必须走预览卡片，用户确认才落盘。**
- **Cron 只能执行白名单 action（`CONTRACT_SECURITY.md` 有完整名单）。**
- **desk 房间 id='desk'，不可删除。**
- **JS 加载顺序就是依赖顺序（`CONTRACT_ARCH.md` 第 7 条）。**
