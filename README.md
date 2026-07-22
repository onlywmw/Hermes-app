# MOV — 多模型协作工作台

> Android 手机上的 AI 协作工具。配置多个大模型，在项目房间里让 AI 团队一起工作——讨论、写代码、产出文件。

---

## 是什么

```
会话  │  运行
──────┼────────
AI 协作│系统 & 模型
项目房间│设备 & Cron
```

- **会话**：项目房间。讨论区 + 文件仓库。多个 AI 模型参与讨论，产出代码和文档。
- **运行**：设备状态、AI 模型监控、Cron 定时任务、技能管理。

---

## 快速开始

```bash
# 构建
./gradlew assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动
adb shell am start -n com.hermes.android/.HermesActivity

# 日志
adb logcat -s MOV:D

# 测试
./gradlew test
```

---

## 架构

```
HermesActivity (WebView 壳)
  └─ hermes-shell.html (单 HTML 入口)
       ├─ 14 个 JS 模块: chat / render / files / runtime / skills / council / store / bridge / i18n / app / app-chat / app-files / app-room / app-run
       │
       ├─ HermesBridge (60+ @JavascriptInterface, BridgeFactory 聚合 6 个子 Bridge)
       │   ├─ IntentParser → CapabilityExecutor (30+ 个设备 & 文件能力)
       │   ├─ AiClient (OpenAI 兼容: DeepSeek / Qwen / OpenAI / Ollama)
       │   ├─ ModelRegistry (多模型注册 & 加密存储)
       │   ├─ CouncilClient (多模型并行讨论 → 汇总 → 结构化输出)
       │   ├─ StorageManager (五种存储: 产出/资料/归档/模板/个人)
       │   ├─ CronManager (WorkManager 定时调度)
       │   ├─ SkillStore (技能 CRUD)
       │   └─ StatsCollector (匿名使用统计)
       │
       └─ B (JS 桥封装, 50+ 方法)
```

---

## 技术栈

- Java 11 · Android API 26+ · targetSdk 36
- WebView 壳 + 纯 HTML/CSS/JS（无前端框架）
- Gradle 8.13 · appcompat 1.6.1
- JUnit 4 · 87 测试用例

---

## 文档

| 文档 | 内容 |
|------|------|
| [ONBOARD.md](ONBOARD.md) | 新成员上手指南 |
| [CONTRACT_ARCH.md](docs/CONTRACT_ARCH.md) | 架构总纲 |
| [CONTRACT_MODEL.md](docs/CONTRACT_MODEL.md) | 多模型协作契约 |
| [CONTRACT_ROOM.md](docs/CONTRACT_ROOM.md) | 房间契约 |
| [CONTRACT_RUNTIME.md](docs/CONTRACT_RUNTIME.md) | 运行页契约 |
| [CONTRACT_SECURITY.md](docs/CONTRACT_SECURITY.md) | 安全契约 |
| [CONTRACT_STORAGE.md](docs/CONTRACT_STORAGE.md) | 存储系统契约 |
| [MOV_DESIGN_SPEC_V2.html](docs/MOV_DESIGN_SPEC_V2.html) | 视觉设计规范 |

---

## 许可

MIT
