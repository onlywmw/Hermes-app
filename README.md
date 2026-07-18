# Hermes-app

Termux + Hermes 智能体**单 APK** 融合版：在 Android 手机/平板上运行的中文 AI 智能体，
能聊天、能记事儿、能直接操作设备（闹钟、日历、短信、通知、应用、无障碍点击……）。

## 这是什么

一个 APK（包名 `com.termux`）装下三样东西：

| 组成 | 说明 |
|------|------|
| **Termux 终端** | 原版终端 + bootstrap，完整 Linux 环境 |
| **Hermes 面板** | 聊天 UI（`HermesActivity`）+ 后台服务（`HermesService`） |
| **完整 hermes-agent** | [Nous hermes-agent](https://github.com/NousResearch/hermes-agent) v0.18.2 内嵌运行（python3.13） |

架构链路：

```
聊天 UI ──HTTP──> android_server.py (127.0.0.1:18080)
                      │  完整 hermes-agent（多轮记忆/技能/压缩/cron）
                      ├──> LLM API（DeepSeek/Kimi/Qwen/GLM/…，OpenAI 兼容直连）
                      └──> JSON-RPC 桥 (127.0.0.1:18081) ──> Android 系统 API
```

## 功能

**对话能力**
- 多轮会话记忆（SQLite 持久化，杀进程不丢）
- 上下文自动压缩，长对话不爆 context
- 技能系统（SKILL.md 渐进加载）、策展记忆（MEMORY.md / USER.md）
- 聊天记录本地持久化，回桌面再回来不丢

**设备控制（25+ 工具）**
- ⏰ 自管闹钟：AlarmManager 精确唤醒 + 全屏响铃，标签可靠，杀进程也能响
- 📅 日历同步：直接写入系统日历（随账户同步）
- ⏳ 定时任务（cron）：「明早 8 点提醒我带伞」，结果发系统通知
- 📋 剪贴板 / 🔔 通知 / 🔦 手电 / 📳 震动 / 🔋 电量 / 📍 位置
- ✉️ 短信收发 / 👥 通讯录 / 📦 应用列表与打开
- 🔊 音量 / ☀️ 亮度 / 🌐 打开网址 / 🔒 锁屏
- 🖱️ 无障碍：dump 界面、按文本点击、输入文字
- 🐚 Termux shell / root shell（已 root 设备）

**多厂商 LLM**（设置里选厂商自动填默认模型，模型名自动转小写）
DeepSeek / Kimi(Moonshot) / 通义 Qwen / 智谱 GLM / 豆包 / 零一 Yi / OpenRouter

## 构建

```bash
# 需要 Android SDK + JDK（Android Studio 自带 jbr 即可）
cd termux-app-master/termux-app-master
echo "sdk.dir=<你的 SDK 路径>" > local.properties
./gradlew :app:assembleDebug
# 输出: app/build/outputs/apk/debug/termux-app_apt-android-7-debug_*.apk
```

安装到设备（数据保留）：

```bash
adb install -r termux-app_apt-android-7-debug_arm64-v8a.apk
```

**首次启动**会自动部署 python3.13 + pip 依赖（清华镜像，约 5–15 分钟，仅一次，
通知栏有进度）。完整 agent 就绪后自动切换；失败自动回退内嵌轻量 agent。

## 目录结构

```
termux-app-master/          # Termux fork（本仓库主战场）
└── termux-app-master/
    └── app/src/main/
        ├── assets/
        │   ├── hermes_agent.py        # 轻量 agent（降级后备）
        │   ├── android_server.py      # 完整 agent 的 HTTP 适配器
        │   ├── hermes_agent_full.zip  # hermes-agent v0.18.2 精简源码
        │   └── plugins/android_bridge/  # 25 个安卓工具的 hermes 插件
        └── java/com/hermes/
            ├── service/   # HermesService（部署/守护）+ 无障碍 + 设备管理员
            ├── bridge/    # JSON-RPC 桥（HermesSocketServer + AndroidToolBridge）
            ├── receiver/  # BootReceiver + AlarmReceiver（自管闹钟）
            └── ui/        # 聊天面板 + 设置（厂商/Key/模型）
hermes-agent-main/          # 完整 hermes-agent 上游源码（参考用，不在 APK 内）
docs/
```

## 版本与回退

| Tag | 内容 |
|-----|------|
| `v1.0.0-stable` | 轻量 agent 版（融合前稳定版） |
| `v2.0.0` | 完整 agent 融合：多轮记忆/技能/cron/压缩 |
| `v2.1.0` | 系统闹钟初版 + 辅助 LLM 调用环境修复 |
| `v2.2.0` | 自管闹钟 v2（MIUI 标签可靠）+ 日历同步 |

回退：`git checkout <tag>` 重打包，或 `adb install -r` 旧 APK（应用数据保留）。

## 已知的 MIUI/HyperOS 适配点

- 系统时钟 App 会清洗第三方 `SET_ALARM` 的参数 → 闹钟走自管方案（AlarmReceiver）
- psutil 拒绝 Android 平台 → 用上游自带 shim（`scripts/install_psutil_android.py`）
- 需在系统设置里允许 App「自启动」以保证后台服务存活（电池优化白名单已自动申请）

## License

各部分遵循其原始许可证（termux-app: GPLv3；hermes-agent 见其 LICENSE）。
