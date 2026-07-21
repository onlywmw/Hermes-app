# MOV — 多模型协作工作台

> Android 平板上的 AI 协作工作台。人和大模型在项目房间里共同产出文件，有看板运行轻应用，能直接操控设备。

---

## 是什么

```
会话  │  看板(🔵)  │  运行
──────┼───────────┼──────
AI 协作│轻应用面板  │系统 & 技能
项目房间│Dock + Web │设备 & Cron
```

- **会话**: GitHub Repo 式的项目房间。讨论区 + 文件仓库。人和 AI 一起写代码、写文档、做决策。
- **看板**: macOS Dock 式的轻应用启动器。音乐、阅读、健身… 自带 4 个应用，可自行添加。
- **运行**: 设备控制 + 技能库 + Cron 定时任务 + 通道监控。

## 快速开始

```bash
# 构建
./gradlew assembleDebug

# 安装到平板 (设备 21770d7d)
adb -s 21770d7d install -r app/build/outputs/apk/debug/app-debug.apk

# 启动
adb -s 21770d7d shell am start -n com.hermes.android/.MOVActivity

# 查看日志
adb -s 21770d7d logcat -s MOV:MOV.shell:D

# 运行测试
./gradlew test
```

## 架构

```
MOVActivity (WebView 壳)
  └─ hermes-shell.html (3 视图 + sheets + 操作条)
       ├─ MOVBridge (22 个 @JavascriptInterface)
       │   ├─ IntentParser → CapabilityExecutor (34 个能力)
       │   ├─ AiClient (OpenAI 兼容, 支持 DeepSeek/Qwen/Ollama)
       │   ├─ CouncilClient (多 AI 角色讨论 → 投票 → 执行)
       │   ├─ CronManager (WorkManager 定时调度)
       │   └─ SkillStore (技能 CRUD)
       └─ B (JS 桥封装)
```

## 能力

**设备控制 (30+)**: 手电筒、电池、亮度、音量、WiFi、震动、TTS、剪贴板、通知、定位、短信、联系人、电话、截屏、应用管理、触摸、网络、进程、文件

**文件系统**: 房间内真实目录 (`/sdcard/mov/rooms/<id>/`)，支持 write/read/delete/mkdir/ls

**AI 对话**: DeepSeek / Qwen / OpenAI / Ollama 四选一，异步非阻塞

**Council 议会**: 多 AI 角色讨论 → 投票收敛 → MOV 执行

## 技术栈

- Java 11, Android API 26+, targetSdk 36
- WebView + HTML/CSS/JS 前端壳
- Gradle 8.13, appcompat 1.6.1
- JUnit 4, 87 测试用例

## 文档

| 文档 | 内容 |
|------|------|
| [MOV_MASTER.md](docs/MOV_MASTER.md) | 项目全貌 |
| [DESIGN_ROOM_V3.md](docs/DESIGN_ROOM_V3.md) | 房间设计 (文件仓库模型) |
| [DESIGN_INTERACTION.md](docs/DESIGN_INTERACTION.md) | 交互系统设计 |
| [DESIGN_BOARD_V2.md](docs/DESIGN_BOARD_V2.md) | 看板设计 |
| [DESIGN_SECURITY.md](docs/DESIGN_SECURITY.md) | 安全修复 |
| [DESIGN_MULTI_MODEL.md](docs/DESIGN_MULTI_MODEL.md) | 多模型协作引擎 |

## 许可

MIT
