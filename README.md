# MOV — 手机上的 AI 交付机器

> 用户说人话目标，稳定收到一个能用的东西。不是聊天应用，不是 skill 库，不是 prompt 合集。

---

## 是什么

Android 开源应用（MIT）：配置多个大模型（DeepSeek / 通义千问 / OpenAI / Ollama，填 API Key 即可），在"房间"里给它们派活。

和聊天应用的本质区别——**它不只聊天，还能动手**：

- **写文件**：AI 产出的文档/代码直接落盘，每次覆盖自动留历史版本
- **执行设备指令**：手电筒、亮度、音量、TTS、通知、剪贴板等 30 个能力
- **定时任务（Cron）**：手机到点自己干活，早上看结果
- **多模型议会（Council）**：同一问题问多个模型，互相评审后汇总
- **HTML → APK**：AI 写的网页一键打包成签名安装包，装上就是真 App

两道安全闸（不可关闭）：
- **计划闸**：AI 动手前先交计划，人批准才执行
- **写入预览**：每次写文件前弹预览卡，人确认才落盘

---

## 三个循环（产品本体）

| 循环 | 一句话 | 例 |
|------|--------|----|
| **自动循环** | Cron + Agent，手机在你睡觉时干活 | 每天 7 点给孩子出 10 道口算题 |
| **生产循环** | 给目标，交付文件/网页/APK | "做个烧烤摊点单应用" → 真能装的 APK |
| **验证循环** | Council 多模型互审后才交付 | 合同让三个模型各审一遍 |

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
       ├─ 13 个 JS 模块: chat / render / files / runtime / skills / store / bridge / i18n / app / app-chat / app-files / app-room / app-run
       │
       ├─ BridgeFactory (70 个 @JavascriptInterface, 聚合 6 个子 Bridge)
       │   ├─ IntentParser → CapabilityExecutor (30 个设备 & 文件能力)
       │   ├─ AiClient (OpenAI 兼容: DeepSeek / Qwen / OpenAI / Ollama)
       │   ├─ ModelRegistry (多模型注册 & 加密存储)
       │   ├─ CouncilClient (多模型并行讨论 → 汇总 → 结构化输出)
       │   ├─ AgentLoop (agentic 循环: 计划→评审→执行→验证→交付)
       │   ├─ StorageManager (四种存储: 产出/资料/归档/个人)
       │   ├─ CronManager (WorkManager 定时调度)
       │   ├─ SkillStore (技能 CRUD)
       │
       └─ B (JS 桥封装, 50+ 方法)
```

---

## 技术栈

- Java 11 · Android API 26+ · targetSdk 36
- WebView 壳 + 纯 HTML/CSS/JS（无前端框架）
- Gradle 8.13 · appcompat 1.6.1
- JUnit 4 · 158 测试用例

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
| [DESIGN_AGENT_LOOP.md](docs/DESIGN_AGENT_LOOP.md) | AgentLoop 设计文档 |
| [MOV_DESIGN_SPEC_V2.html](docs/MOV_DESIGN_SPEC_V2.html) | 视觉设计规范 |

---

## 许可

MIT
