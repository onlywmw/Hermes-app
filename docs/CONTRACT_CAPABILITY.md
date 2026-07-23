# CONTRACT_CAPABILITY — 能力边界表

> v1.1 · 2026-07-24 · **每行结论附验证依据**（v1.0 的教训：凭架构印象写表 = 会错，联网行就是反例）。
> agent 计划时必须对照本表执行。**✅ 做到可上线标准，⚠️ 只能 demo 的明示，❌ 明说并给替代。**
> 能力变更必须同 commit 更新本表。这是"假菜单"原则的机器可读版。

## 〇、两个运行环境（一切判断的前提）

| 环境 | 是什么 | 能力来源 |
|------|--------|----------|
| **MOV 宿主内** | agent 通过 device.cmd 直接调本机 | 27 个原生能力 (CapabilityExecutor, 30 分支无桩) + manifest 15 项权限 |
| **打包 APK 内** | agent 产出的单文件 HTML 在独立 WebView 壳里跑 | 模板仅声明 `INTERNET` (aapt dump 实测)，其余=浏览器能力 |

## 一、✅ 能做 — 必须做到可上线完成度

### A. 宿主内（agent device.cmd 直调）

| 能力 | 依据 | 完成度要求 |
|------|------|-----------|
| 手电/震动/音量/亮度/语音朗读 TTS/通知/剪贴板/电量/系统信息/进程/网络状态/截屏 | CapabilityExecutor 27 分支；DevicePolicy.probe 换机自适应（无闪光灯/马达自动不放行） | 真做，禁止 HTML 模拟 |
| 拍照 camera.photo / 定位 location.get / 通讯录 / 短信 / 拨打电话 | MOV manifest 已声明 CAMERA/LOCATION/CONTACTS/SMS/CALL_PHONE（需用户运行时授权） | 权限被拒要有人话降级 |
| 应用启动 app.launch / 应用列表 / 模拟点滑 input.tap/swipe | 同上 | — |
| 文件读写 + 版本快照 | StorageManager | 快照可回滚 |
| Cron 定时任务 | CronManager 白名单 | 产出落房间 + 通知 |
| HTML → 签名 APK（稳定包名覆盖升级） | PackageBuilder 链路真机已验证 | 安装/启动/杀进程数据在 |

### B. 打包 APK 内（WebView 浏览器能力 + MovShell 桥）

| 能力 | 依据 | 解锁场景 |
|------|------|---------|
| **联网 fetch/XHR/WebSocket（仅 HTTPS）** | 模板已声明 INTERNET (aapt dump)；shouldOverrideUrlLoading 只拦页面导航不拦 XHR | 连云服务器的 C/S 应用（类微信） |
| **相机预览/扫一扫 getUserMedia** | 模板 v2：manifest CAMERA + 运行时申请 + onPermissionRequest 授权，2026-07-24 真机验证（权限弹窗→授权→实时预览） | 扫码点单、拍照上传 |
| **录音 MovShell.recordAudio(秒)+recordResult()** | 2026-07-24 真机：录到 4225 字节真实 AAC。**注意：本机 MIUI 的 WebView getUserMedia 音频通道 NotReadableError（系统层限制，权限已授予仍失败），必须走原生桥** | 语音输入、录音笔记 |
| **通知 MovShell.notify(title,text)** | 模板 v2 JS 桥：POST_NOTIFICATIONS 懒申请，2026-07-24 真机验证（系统 NotificationRecord 证据）。返回 ok/no-permission | 新消息提醒、订单提醒 |
| **震动 MovShell.vibrate(ms)** | 返回布尔——false=本机无马达（如 Redmi Pad 无 vibrator 服务），hasVibrator() 自检后如实反馈，禁止盲报成功 | 按键反馈、提醒 |
| **传感器 DeviceMotion/DeviceOrientation** | Android WebView 无需权限（与 iOS 不同） | 体感游戏：平衡球、赛车转向 |
| **音频播放 `<audio>`/Web Audio** | 播放无需权限；单文件可内联 base64 音效 | 游戏音效、提示音 |
| **WebGL/Canvas/游戏引擎** | WebView 自带 GPU 加速 | 3D/复杂游戏 |
| **Gamepad API** | WebView 支持，蓝牙/USB 手柄 | 手柄游戏 |
| **localStorage / IndexedDB** | 模板 setDomStorageEnabled(true) | 订单/记录持久化（IndexedDB 容量大得多） |
| 单文件 HTML 工具/表单/小游戏 | 全链路已验证 | — |

**完成度通用标准（所有 ✅ 项）：承诺的功能全部真实现，禁止占位符、桩代码、"TODO"、"略"、图片用色块代替、按钮无响应。**

## 二、⚠️ 带条件 / 只能 demo — 必须明示边界

| 场景 | 条件/缺口 | 边界说明 |
|------|----------|---------|
| **APK 内访问 http:// 明文服务器** | targetSdk 36 → usesCleartextTraffic 默认 false，**明文 HTTP 被系统拦截**；HTTPS 正常 | 用户云服务器裸 IP+http 会被拦！临时方案：服务器上 HTTPS；模板加 cleartext 配置属"近赢解锁"（见三.B） |
| **speechSynthesis 朗读（APK 内 TTS）** | 无需权限，但依赖设备 TTS 引擎（各厂商 ROM 不一） | 用前实测；不保证所有设备有声 |
| 多用户/账号体系界面 | 无后端 + 账号系统 | 单机界面 + 本地模拟数据，交付明说"数据不出本机" |
| 财务/经营系统 | 无真数据源 | 手工录入 + localStorage 统计是真的；自动同步是 demo |
| 协作/共享类 | 无后端同步 | 单机版，明说"不能多人同时用" |

## 三、❌ 做不了 — 明说，给替代

### A. 近赢解锁（一行声明或一个回调的事，排队做）

| 需求 | 缺什么 | 解锁成本 |
|------|--------|---------|
| APK 内明文 HTTP | manifest 一行 usesCleartextTraffic（或按域 networkSecurityConfig） | 模板改 1 行 |
| APK 内选文件/上传照片 `<input type="file">` | 模板重写 WebChromeClient.onShowFileChooser | ~20 行（收款码图片上传需要） |

### B. 真做不了（当前架构外）

| 需求 | 缺什么 | 替代方案 |
|------|--------|---------|
| APK 内定位 Geolocation API | ACCESS_FINE_LOCATION + onGeolocationPermissionsShowPrompt | 模板再升级；现在宿主 location.get 代查 |
| agent 联网抓取 http.get | 工具未建（prompt 注入 + token 爆炸风险，需域名白名单设计） | 路线图 P2；现在用户手动贴内容 |
| 真后端（账号/消息同步/多人实时） | agent 无服务器部署能力 | 交付 server.js + 部署说明（用户有云服务器可自部署，APK 内 HTTPS fetch 已通） |
| 支付接口 | 资质 + 后端 | 展示收款码图片（烧烤摊模式） |
| 应用级推送 | 推送 SDK + 后端 | MovShell.notify 本机通知 |
| PWA/Service Worker | file:// origin 不支持 | 不需要——本地 assets 本身就是离线 |
| Web Share/Clipboard/Notification API | file:// 非 secure context | MovShell.notify / 宿主 device.cmd 替代 |
| 图片理解（拍照解题/票据识别） | AiClient 不支持图片消息 | 路线图 P2 |

## 四、执行规则（写进 prompt 的压缩版依据）

1. 计划前对照本表：需求落在 ✅ → 全部做掉；⚠️ → 计划卡必须标注边界；❌ → 不得闷头做，先说明并给替代。
2. 混合需求（部分 ✅ 部分 ❌）：✅ 部分全做，❌ 部分在计划卡单独列出"此部分无法真实现"及替代。
3. 评审/验收以本表为准：✅ 项出现占位符 = 交付缺陷（返工）；⚠️ 项未明示边界 = 交付缺陷。
4. **本表每行结论必须可复核**：修改任何一行，在 commit 里附上验证方法（aapt dump / 源码行 / 真机实测截图）。

## 五、修订记录

- **v1.2 (2026-07-24)**：模板权限三件套落地 — 相机(getUserMedia)/录音/通知(MovShell.notify)/震动(MovShell.vibrate) 真机验证升 ✅；近赢档清零 vibrate（已交付）
- v1.1：联网行纠错（模板有 INTERNET，v1.0 误判 ❌）；补 cleartext 限制；新增 ✅ 五行（传感器/音频/WebGL/Gamepad/IndexedDB）；新增"近赢解锁"档；每行补验证依据
