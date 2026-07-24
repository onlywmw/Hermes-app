# MOV — 手机上的 AI 交付机器

> 用户说人话目标，稳定收到一个能用的东西。不是聊天应用，不是 skill 库，不是 prompt 合集。

---

## 是什么

Android 开源应用（MIT）：配置多个大模型（DeepSeek / 通义千问 / OpenAI / Ollama，填 API Key 即可），在"房间"里给它们派活。

和聊天应用的本质区别——**它不只聊天，还能动手**：

- **写文件**：AI 产出的文档/代码直接落盘，每次覆盖自动留历史版本
- **执行设备指令**：手电筒、亮度、音量、TTS、通知、剪贴板等 30 个能力
- **HTML → APK**：AI 写的网页一键打包成签名安装包，装上就是真 App（同包名覆盖升级，改完原地迭代）
- **多模型互审**：房间多模型可对计划/交付做证伪式评审（可选增强）

三道闸门（批准疲劳最小化）：
- **理解闸**：目标有多种合理解读时先反问你（选项点选，零打字）
- **计划闸**：每任务一次，计划卡置顶"给谁用/什么场景/核心闭环"——批准计划 = 批准理解 + 授权写入
- **交付验收**：交付卡每个产出文件带「预览」，内容验收一次完成；失败了给失败卡（人话原因+部分产物+一键重开）

---

## 三个循环（产品本体）

| 循环 | 一句话 | 例 |
|------|--------|----|
| **生产循环**（本体） | 给目标，交付文件/网页/APK | "做个烧烤摊收银应用" → 真能装的 APK |
| **自动循环**（放大器） | Agent 自动推进，手机在你睡觉时干活 | 每天 7 点给孩子出 10 道口算题 |
| **验证循环**（可选增强） | 多模型证伪式互审后才交付 | 合同让三个模型各审一遍 |

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
       ├─ 12 个 JS 模块: chat / render / files / runtime / store / bridge / i18n / app / app-chat / app-files / app-room / app-run
       │
       ├─ BridgeFactory (66 个 @JavascriptInterface, 聚合 6 个子 Bridge)
       │   ├─ IntentParser → CapabilityExecutor (30 个设备能力)
       │   ├─ AiClient (OpenAI 兼容: DeepSeek / Qwen / OpenAI / Ollama)
       │   ├─ ModelRegistry (多模型注册, 单例 + 加密存储)
       │   ├─ AgentLoop (理解闸→计划闸→执行→证伪评审→交付卡/失败卡)
       │   ├─ AgentReview (多模型证伪式评审, 可选)
       │   ├─ StorageManager (四种存储 + 版本快照)
       │   ├─ PackageBuilder (HTML → 签名 APK, 稳定包名覆盖升级)
       │   ├─ linux/ (内嵌 Ubuntu 24.04: 静态 proot + RootfsManager + ProotRunner, agent shell.exec;
       │   │          M2: HermesInstaller 内嵌 Hermes agent, hermes -z 委派)
       │   ├─ TerminalActivity (M3: 交互终端, terminal-emulator/terminal-view 移植自 termux-app)
       │   ├─ linux/DeployConfig (M4: 部署服务器配置→rootfs 注入 movssh/movscp, 全栈交付)
       │   ├─ SkillStore (技能 CRUD)
       │
       └─ B (JS 桥封装, 50+ 方法)
```

---

## 技术栈

- Java 11 · Android API 26+ · targetSdk 36
- WebView 壳 + 纯 HTML/CSS/JS（无前端框架）
- Gradle 8.13 · appcompat 1.6.1
- JUnit 4 · 176 测试用例 · CDP 真机 e2e 驱动（tools/e2e/）

## 官网

`website/` 单文件落地页，任意静态托管可部署。

---

## 文档

| 文档 | 内容 |
|------|------|
| [ONBOARD.md](ONBOARD.md) | 新成员上手指南 |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构总览（实测版） |
| [MOV-STRATEGY.md](docs/MOV-STRATEGY.md) | 产品战略（宪法） |
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
