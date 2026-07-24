# MOV 是什么构造（大白话版）

> 一句话：**MOV 是开在你手机里的一个小作坊**。你跟它说人话，它交出真东西（文件、网页、能装的 App）。
> 看不懂类名没关系，记住 6 个角色就行。

---

## 6 个角色

### 1. 门面 —— 你看到的那个界面
聊天、房间、文件、运行状态，全在这层。
技术上就是一个网页（12 个 JS 文件拼的），但它只是**显示器 + 按钮**，自己不干活。

### 2. 传菜口 —— 界面和手机系统之间的唯一通道
界面上点任何按钮，都通过 70 个"窗口"喊手机系统干活（读文件、调模型、调安装器）。
**安全规矩：每个窗口都有保安，出错只说"失败了"，绝不许把错误扔到界面上**（扔到界面 = 白屏）。

### 3. 大脑 —— 真正想事儿的
你的目标交给它，它拆成计划、一步步干、干完汇报。
它本身不在手机里，是云端的 DeepSeek/通义这些大模型；手机里的是**指挥它的教练**（AgentLoop），负责：
- 目标有歧义就先反问你（理解闸）
- 把它的计划拦下来给你批（计划闸）
- 盯着它别偷懒别跑偏（步数上限、工具纪律）
- 搞砸了就给你一张失败卡，能一键重来

### 4. 双手 —— 大脑能用的工具
三类：
- **文件**：读、写、查房间里的文件
- **手机硬件**：手电、亮度、音量、朗读、通知、剪贴板……共 30 样
- **打包机**：把网页压成一个真能安装的 APK（烧烤摊 App 就是这么来的）

### 5. 仓库 —— 文件放哪
每个房间一个独立仓库，产出/资料/归档分开放。
**每次覆盖旧文件前自动留底（快照）**，改坏了能一键回到上一版。

### 6. 保安 —— 三道闸（你唯一需要操心的地方）
| 闸 | 什么时候拦你 | 你要做的 |
|---|---|---|
| 理解闸 | 你的话有多种理解时 | 点一个选项（不用打字） |
| 计划闸 | 每次任务一次 | 看一眼"它理解成什么+要动哪些文件"，批准或驳回 |
| 交付验收 | 交活的时候 | 每个文件能点开预览，满意再装/再用 |

除了这三下，其余全自动。

---

## 走一遍：你说"帮我做个烧烤摊收银应用"之后

1. 大脑琢磨：这话有两种意思（顾客点单？老板记账？）→ **理解闸弹出两个选项**，你点"老板记账"
2. 大脑出计划：一张卡片写着"给谁用/什么场景/要改哪个文件" → **你批准**
3. 大脑写 bbq.html（计划内的文件，直接写，不再烦你）
4. 大脑喊打包机：bbq.html → bbq.apk
5. **交付卡**：bbq.html（可预览）、bbq.apk（可安装）→ 你点安装 → 桌面出现"烧烤摊收银"
6. 要是中途断网了 → **失败卡**：原因说人话 + 已完成的保留 + 一键重开

第 6 步那种情况，已烧的 token 不白费：重开时大脑看到"文件已经在了"，会接着干而不是重写。

---

## 精确版（给要改代码的人）

| 角色 | 真实代码 |
|---|---|
| 门面 | `assets/hermes-shell.html` + `js/` 12 个模块 |
| 传菜口 | `bridge/BridgeFactory`（70 个 @JavascriptInterface）+ 6 个子桥 + BridgeValidator |
| 大脑 | `agent/AgentLoop`（循环）+ `AgentReview`（评审）+ `ToolRegistry`（工具）+ `ai/AiClient`（调模型） |
| 双手 | `CapabilityExecutor`（30 能力）+ `StorageManager`（文件）+ `packager/`（打包） |
| 仓库 | `StorageManager`（四种存储 + 快照） |
| 定时 | `cron/`（白名单双闸，WorkManager） |
| 壳 | `HermesActivity` 等 3 个 Activity |

规模：Java 39 文件 · JS 12 模块 · 桥 70 · 能力 30 · 测试 175 · APK 8.8MB
约束细节：`CONTRACT_*.md`；产品方向：`MOV-STRATEGY.md`

---

## M1 内嵌 Linux（linux/ 包，2026-07-24 真机落地）

第 7 个角色：**真电脑** —— 手机里再装一台完整的 Ubuntu 24.04，agent 用
`shell.exec` 跑真 shell（python3/git/联网），用 `file.push`/`file.pull` 在房间和
Linux 之间搬文件（guest 里挂 `/exchange`）。

### 架构要点（全是真机撞出来的）

- **proot 用户态 chroot，无 root**：二进制为 green-green-avk/build-proot-android
  的**静态构建**（动态链接系统 bionic、无第三方依赖），以 `libproot.so` 名义打进
  `jniLibs/arm64-v8a`（Android 只释放 `lib*.so` 命名的文件，且需 manifest
  `extractNativeLibs="true"`），安装时释放到 nativeLibraryDir —— app 域
  （untrusted_app）唯一能 exec 原生二进制的位置。
- **Termux 版 proot 不能用**：app uid 下 dpkg 必报
  "unable to securely remove .dpkg-tmp"（其 seccomp/loader 与 app uid 不兼容）。
- **app 域 SELinux 禁止 exec /system/bin/tar** → GNU tar/gzip 及其依赖
  （libacl/libiconv/libcharset/libandroid-glob/libandroid-selinux/libpcre2-8）
  全部随包（libtar.so/libgzip.so，仅 RootfsManager 解压用）。
- **app 域 SELinux 禁止创建硬链接**（dontaudit，无 avc 日志），且静态 proot 的
  `--link2symlink` 模拟只走 seccomp 拦截、在 app 域不生效（runas_app 域反而生效；
  设 PROOT_NO_SECCOMP 则更糟：linkat 直接 ENOSYS）。
  ⇒ **rootfs 必须预消除硬链接 + 预装包**：在 shell 域解包 Ubuntu Base 24.04、
  apt 装好 python3/python3-venv/git，修掉 l2s 残留的绝对 symlink，
  `tar --hard-dereference` 重打包。安装时只做 复制→解压→写
  resolv.conf/99mov-proot→`python3 --version` 验活（约 1 分钟），不做 in-app apt。
- **运行命令行**：`proot -0 --kill-on-exit --link2symlink -r rootfs -b /dev -b /proc
  -b /sys -b linux-exchange:/exchange [-b /sdcard:/sdcard] -w /root /usr/bin/env
  PATH=.. HOME=/root DEBIAN_FRONTEND=noninteractive /usr/bin/bash -lc 'cmd'`
  - `-0` fake root（dpkg 检查 geteuid()==0）
  - `--kill-on-exit`（超时 destroyForcibly 时 tracee 孤儿化会持 dpkg lock 卡死）
  - 必须显式注入 PATH/HOME（继承 Android PATH 则 guest 命令全部 not found）
  - env 注入 `PROOT_LOADER=libproot-loader.so`（execve EACCES 回退）、
    `TMPDIR`/`PROOT_TMP_DIR=files/linux/tmp`
- **陷阱：`run-as` 处于 runas_app 域，没有网络权限** —— 验证联网/apt 必须用真实 app。
- **已知边界**：guest 内 apt/dpkg 装新包在 app 域不可用（dpkg status 备份必做
  link()，硬链接被禁且模拟不生效）；需要新包时走预置 rootfs 重建路线。

### 代码与数据

| 件 | 位置 |
|---|---|
| 引擎定位/探测 | `linux/Proot.java`（设置页顶部探测行 = M1 生死验证） |
| 执行器 | `linux/ProotRunner.java`（buildArgs/truncate 纯函数可单测；超时 15–600s，stdout/stderr 各 6000 字符截断） |
| 安装/状态机 | `linux/RootfsManager.java`（NOT_INSTALLED→…→READY，state.json 持久化；本地包 `/sdcard/MOV/ubuntu-base.tar.gz` 优先免下载） |
| agent 接线 | `ToolRegistry.build(..., linuxAvailable)` 注册 shell.exec/file.push/file.pull；`AgentLoop` allowedShell 闸（计划内含=批准即授权；计划外→shellPreview 卡→`respondShell`） |
| 文件交换 | `StorageManager.pushToExchange/pullFromExchange`（canonical 双向防越界） |
| 设置页 | HermesSettingsActivity「Linux 环境」区（探测/状态/磁盘占用/安装/授权/卸载） |

---

## M2 内嵌 Hermes agent（2026-07-24 真机落地）

在 M1 的 Ubuntu 里再装一个完整的 Hermes agent（Nous Research, v0.19.0），
MOV agent 通过 shell.exec 把重任务（深度调研/大文件生成/多步骤自动化）委派给它。

### rootfs 预装清单（ubuntu-base.tar.gz, 226MB, M2 版）

Ubuntu Base 24.04.3 (aarch64) + python3 3.12.3 / python3-venv / python3-pip 24.0 /
python3-dev / git 2.43.0 / ca-certificates（`update-ca-certificates` 已跑）/
curl 8.5.0 / build-essential (gcc 13.3.0)。shell 域装好 → 修 l2s 绝对 symlink →
`tar --hard-dereference` 重打包（0 硬链接条目）。M1 版备份为 ubuntu-base-m1.tar.gz。

### Hermes 安装与配置

- **源码**：hermes-agent 0.19.0 精简（去 tests/website/apps/infographic/docs 等，
  158MB→48MB）打成 `assets/hermes-agent.zip`（16MB, 2088 文件）。
- **安装器** `linux/HermesInstaller.java`（rootfs READY 后可触发，hermes-state.json
  持久化 NOT_INSTALLED/INSTALLING/READY/ERROR）：
  解 zip → `linux-exchange/hermes-agent`（guest `/exchange`）→
  `python3 -m venv ~/.hermes-venv` →
  `pip install -e '/exchange/hermes-agent[termux]' -c constraints-termux.txt`
  （真实 app 域有网，约 40 个依赖全部 aarch64 wheel，~10 分钟）→
  `~/.hermes-venv/bin/hermes --version` 验活。
- **配置注入**（幂等，安装后与模型变更时可重跑）：MOV 默认模型（ModelRegistry
  缺 key 时兜底 AiProviderConfig 旧配置）→ `~/.hermes/.env`
  （OPENAI_API_KEY/OPENAI_BASE_URL）+ `config.yaml`
  （`model: {default, provider: custom, base_url}`，变量名经 hermes 源码确认）。
  key 只写文件不进日志。
- **委派格式**（headless，源码 hermes_cli/oneshot.py 确认）：
  `~/.hermes-venv/bin/hermes -z '<任务>'` — 单发执行，自动批准工具
  （HERMES_YOLO_MODE=1），只把最终回答打到 stdout；启动开销 ~50s，
  shell.exec timeoutSec 给 300–600。

### M2 新踩的坑（已修）

- **CA 证书包 postinst 在 proot 下静默失败** → pip 报
  "Could not find a suitable TLS CA certificate bundle"；解法：root 域补跑
  `update-ca-certificates` 后重打包。
- **TMPDIR 泄漏**：ProotRunner 曾给 proot 设 TMPDIR=宿主路径，该变量泄漏进
  guest，guest 程序 mktemp 必败（update-ca-certificates 实踩）；proot 自己的
  临时目录只能走 PROOT_TMP_DIR，TMPDIR 一律不设。
- pip 直连 pypi.org 可用（国内网络偏慢但通），未用镜像。
- e2e 手法：mock-llm-m2 按 system prompt 分流 MOV/Hermes 两个"大脑"，
  Hermes 主对话走 SSE 流式（stream:true 时 mock 回 SSE chunk）。

---

## 真实链路终验（2026-07-24, 小米 MiMo 真实 API）

大脑与 hermes 主模型均配 MiMo（OpenAI 兼容, mimo-v2.5-pro-ultraspeed）。
全程无 mock，真机实测通过。暴露了三个真实 bug（均已最小修复）：

### 1. hermes 自定义端点必须 named custom provider（401 修复）
M2 的 `buildConfigYaml` 生成 `provider: custom` + `model.base_url`，hermes
主循环把 `custom` 当内建别名解析、请求落到错误端点 → MiMo 返回
`HTTP 401: Invalid API Key`（key 本身 PC curl 验证有效）。**修复**：
config.yaml 改生成 `providers` 块注册端点 + `model.provider` 指向别名，
key 只经 `key_env: OPENAI_API_KEY` 引用 .env（不明文）：
```yaml
model: { default: "<model>", provider: "mov-custom" }
providers:
  mov-custom: { base_url: "<url>", key_env: "OPENAI_API_KEY" }
```
验证：root 域 hermes -z '1+1' → 真实回答 `2`；MOV 内委派任务
shell.exec→hermes→file.pull 全通（hermes plugin discovery 日志 + 产物短诗）。

### 2. desk 单聊停在旧版单模型配置（ModelRegistry 迁移遗留）
`routeMessage` 用 `B.aiInfo()`（旧 AiProviderConfig）判断是否已配置 →
配了 ModelRegistry 也回「AI 尚未配置」。**修复**：desk 单聊优先取注册表
默认模型（`B.listModels()` isDefault+apiKey 非空）走 `aiChatWithModel`，
兜底旧配置。验证：desk 单聊「用一句话介绍你自己」→ MiMo 真实回复。

### 3. B.listModels() 的 toJson 无 isConfigured 字段
前端判断"已配置"不能用 `m.isConfigured`（undefined）→ 用 `apiKey 非空`
（脱敏后仍非空）。

### 配置同步钩子
`BridgeModel.setDefaultModel` 成功后后台触发
`HermesInstaller.writeModelConfig`（幂等）——换默认模型即同步 hermes 配置，
不必重装 Hermes。

### 真实终验产物
- 贪吃蛇 APK：`snake.html`（146 行完整 HTML5 canvas 游戏，keyboard 控制）
  → `snake.apk`（17KB）计划→批准→file.write→app.package→交付全通
- hermes 委派：短诗任务 shell.exec→hermes（真实 plugin discovery + MiMo）
  → file.pull 取回 `poem.txt`（完整 4 行诗）

---

## M4 全栈交付（2026-07-24, agent 写后端→自测→ssh 部署）

目标：产物"能上线"——agent 写 FastAPI/Express 后端，内嵌 Linux 本地起服务
curl 自测，ssh/scp 部署到用户服务器，APK/前端走 HTTPS 对接。

### rootfs 工具链（ubuntu-base.tar.gz, M4 版 381MB）

M2 基础上补 `nodejs v18.19.1 / npm 9.2.0 / sshpass 1.09 / openssh-server`
（M2 版备份 ubuntu-base-m2.tar.gz）。重打包 `tar --hard-dereference`（0 硬链接条目）。

### 部署服务器配置（linux/DeployConfig.java）

- 存储：host/port/user/authType(密码|私钥)/secret，走模型 key 同款
  EncryptedSharedPreferences；设置页「部署服务器」区（保存/测试连接/状态），
  BridgeFactory 三接口（getDeployConfig[脱敏]/saveDeployConfig/testDeployConnection）。
- **注入 rootfs（内联参数，不写 ~/.ssh/config）**：保存时生成
  `/usr/local/bin/movssh` + `movscp`：
  - 密码 → `SSHPASS='..' sshpass -e ssh ...`，私钥 → 裸 `ssh -i key ...`
  - host/port/user 全内联（agent 只写 `movssh 'cmd'` / `movscp f /remote/dir/`）
  - `-o ConnectionAttempts=15`（扛 localhost 夹具重启间隙 + 网络抖动）
  - `scp -O`（Ubuntu24 scp 默认 SFTP 协议，远端 sftp-server 不稳，强制 legacy）
  - `UserKnownHostsFile=/dev/null`（否则更新 known_hosts.old 时 unlink 被 app 域 SELinux 拒）
  - 测试连接 = ProotRunner 真跑一次 `movssh 'echo MOV_SSH_OK'`

### M4 踩坑（全实测，已修）

- **不写 ~/.ssh/config**：app 域解压的文件属主是 app uid，guest fake root 下
  ssh 报 `Bad owner or permissions on /root/.ssh/config` → 目标参数全内联进 movssh/movscp。
- **sshd daemon 在嵌套 proot 下不可用**：OpenSSH daemon 模式的 rexec/fork 子进程
  及 privilege separation 的 chroot，在 proot 里执行 guest shell 报
  `/bin/bash: No such file`（dropbear 同病）。`sshd -ddd`（debug 模式禁 privsep）正常。
  ⇒ localhost 验收夹具 = **proot 内 bash 循环跑 `sshd -d`**（proot 主进程常驻稳定，
  每连接一个 sshd -d 子进程被正常 ptrace attach；见 movsvc 脚本）。
  注意：这只影响"平板当被控端"的夹具；MOV 作为 ssh **客户端**部署到用户真实
  服务器（标准 sshd）完全正常。
- **PEP 668**：Ubuntu24 系统 python 禁 `pip install` 直装（externally-managed）→
  prompt 教 venv 或 `--break-system-packages`（M2 装 hermes 用 venv 所以没踩）。
- 服务守护：shell.exec 里 nohup 起的服务会被 ProotRunner `--kill-on-exit` 清场
  （本地自测只能在同一命令内 curl 验证）；经 ssh 在远端起的服务不受此限
  （远端 sshd 侧无 kill-on-exit）——所以"本地自测 + 远端持久"的分工天然成立。

### prompt 全栈工作流（AgentLoop.PLAN_RULES/STEP_RULES）

- ❌做不了 删「真后端」；✅能做 加全栈交付段（写后端→本地 curl 自测→
  movssh/movscp 部署→HTTPS 对接，未配置→ask_user 引导去设置页）。
- 部署纪律：本地起服务 curl 自测通过才部署；systemd 或 nohup+日志守护；
  部署后必须 curl 健康检查再交付；长命令 timeoutSec 给足；防火墙/端口提示；
  有域名→nginx+certbot，无域名→诚实说明 WebView 拦明文 HTTP。
- pip 纪律：venv 或 --break-system-packages。

### localhost 验收夹具复现（无真实服务器时）

```sh
# root 域 (KernelSU): 起 sshd 于 127.0.0.1:8022, root/mov123
# 关键: 必须 proot 内 bash 循环 sshd -d (debug 禁 privsep), 不能用 daemon
LIBD=$(pm path com.hermes.android | sed 's/package://' | xargs dirname)/lib/arm64
export LD_LIBRARY_PATH=$LIBD PROOT_LOADER=$LIBD/libproot-loader.so
R=/data/data/com.hermes.android/files/linux/rootfs
nohup $LIBD/libproot.so -0 --link2symlink -r $R -b /dev -b /proc -b /sys -w /root \
  /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  HOME=/root TMPDIR=/tmp \
  /usr/bin/bash -c 'while true; do /usr/sbin/sshd -d -p 8022 -E /tmp/sshd.log; sleep 0.2; done' &
# 设置页「部署服务器」配 127.0.0.1:8022 root/mov123 → 测试连接
```

### 验收产物（e2e agent-m4-deploy-verify.js, mock 驱动真实执行）

FastAPI 留言板：写后端+前端 → file.push → 本地 uvicorn 自测(curl 200) →
movscp 部署 → 远端 uvicorn 起服务 → 健康检查 `{"status":"ok"}` →
远端文件 /root/deploy/guestbook_api.py 存在 + 远端服务 8023 持续在跑 +
前端 fetch 指向 8023/api。全部断言通过。

---

## 交付质量闸门（2026-07-24, 针对"死按钮/评审走过场/交付不诚实"）

三处制度性修复，全部真机验证：

### 1. 评审看内容（agent/ProductDigest.java + AgentReview.deliveryVote 增强）
- 评审不再只看日志摘要：`deliveryVote` 新增 `productsDigest` 参数 — 每个文本产物
  头 4000 字符 + 自动提取「交互元素清单」（button/onclick/addEventListener 片段 +
  **空函数体检测**，纯函数可单测）。
- 评审 prompt 改为"逐一对照计划承诺在产物里找证据；死按钮/空函数/占位符/TODO →
  投 fail 并指出具体位置"。

### 2. 评审失败不再算过（AgentLoop DELIV_REVIEW）
- 评审调用失败/异常 → 只记 comment 标「（评审调用失败， 不计票）」，**不计入 pass**
  （废掉旧的"调用失败视为通过"fail-open）。
- 无评审模型 / 全部调用失败（pass+fail=0）→ `notReviewed`，交付照常但交付卡
  明确标「⚠ 未评审， 请自行验收」。
- 交付三态进 deliver 事件 `reviewState`：passed(含票数)/reworked/not_reviewed，
  交付卡对应展示。
- 评审团过滤（BridgeAi.buildReviewer）：isConfigured + **剔除 localhost mock 测试
  模型**；兼容 members.ai 字符串/对象两种格式（此前对象格式会让 buildReviewer 整个
  抛异常 → reviewer=null 静默跳过，实踩）。
- 附带修：deliver() 先落事件再置 DONE（此前 state=DONE 先于 log(d)，测试/观察者
  会在 deliver 事件前读到 DONE 竞态）。

### 3. 功能自检清单（诚实交付）
- PLAN_RULES：finish summary 必须含【功能自检清单】，逐项标 ✅真实现/⚠️演示及原因，
  禁止漏项；做不出真功能的 UI 元素必须在产物里带可见「演示」角标。
- AgentLoop.deliver 把 summary 的 ✅/⚠️ 行解析成 `checklist` JSON（ProductDigest.
  parseChecklist，纯函数），交付卡分色结构化渲染（✅绿/⚠️橙）。

### 真机验证（评审团 = 独立 MiMo-评审 条目）
- **诱饵任务**（mock 大脑写死按钮+谎称真实现，真实 MiMo 评审）：
  评审看产物内容投 fail —— 工作日志「交付评审·第1轮： 0 通过 / 1 返工」
  「需返工， 第 1 轮修复」→ 产物从空函数死按钮改为演示挂牌 → 交付卡
  ⚠️ 扫一扫演示版（e2e agent-quality-rework-verify.js）。
- **占位诱饵**（reviewer=null 场景）：交付卡明示「⚠ 未评审， 请自行验收产物」。
- **正常任务**（真相机扫码 HTML）：评审通过、清单全 ✅ 具体真实 —— 不误伤。

---

## M3 交互式终端（terminal-emulator 原生方案，2026-07-24 真机落地）

给用户一台能敲的真终端：原生 TerminalView（非 xterm.js）跑 proot Ubuntu
交互 shell。源码移植自 termux-app（GPLv3，见两模块内 LICENSE.md）。

### 模块融合

- `terminal-emulator/`（VT 仿真 + JNI pty/fork/exec，C 编 libtermux.so，
  ndkBuild）与 `terminal-view/`（TerminalView 控件）**复制进 MOV 仓库**，
  仅改 build.gradle（去 maven-publish，定死 compileSdk 36 / minSdk 26 /
  ndkVersion 27.1.12297006 / abiFilters arm64-v8a 与 app 对齐）。
  **零 termux-shared 依赖**（两模块 import 自查确认，裁剪成本为零）。
  terminal-emulator 自带 148 个上游单测随仓保留（补 testImplementation junit）。
- `settings.gradle` 纳入两模块，app 仅 `implementation project(':terminal-view')`
  （它 api 依赖 terminal-emulator）。APK 增加 libtermux.so (47KB)。

### 会话与参数

- `linux/TerminalEnv.java`：交互式启动参数纯逻辑（可单测）。命令行与
  ProotRunner 同形态（`-0 --kill-on-exit --link2symlink -r rootfs -b /dev
  -b /proc -b /sys [-b exchange:/exchange] [-b /sdcard] -w /root /usr/bin/env
  PATH=.. HOME=.. TERM=xterm-256color ... /usr/bin/bash -l`），差异仅交互式
  `bash -l` + TERM 注入；proot 自身 env 走 PROOT_LOADER/PROOT_TMP_DIR。
  pty 由 terminal-emulator 的 JNI 提供（fork+execvp）。
- `TerminalActivity`（com.hermes.android，全屏）：TerminalView + 简化版
  虚拟按键行（ESC/CTRL/ALT/TAB/方向键；CTRL/ALT 为 toggle，经
  TerminalViewClient.readControlKey/readAltKey 供 KeyHandler 消费）。
  rootfs 未 READY 时显示引导文本而非崩会话。
- 入口：主界面运行页顶栏 `>_` 按钮（bridge.js `B.openTerminal` →
  BridgeFactory `@JavascriptInterface openTerminal()`）+ 设置页 Linux 卡
  「打开终端」。

### M3 踩坑（已修）

- **TerminalView 默认不可 focus**：termux 在 XML 里声明 focusable，代码
  动态 new 出来的是不可 focus 的 → requestFocus 永远 false，键盘/IME 输入
  全被按键行按钮截获（按键事件到 window 但分发给 Button）。必须
  `setFocusable(true)+setFocusableInTouchMode(true)`。
- **adjustResize 按键行被软键盘遮**：manifest+代码双设 SOFT_INPUT_ADJUST_RESIZE
  且根布局 `fitsSystemWindows=true` 才生效（否则键盘盖住按键行）。
- 无害噪音：proot `chdir("/root")` 警告（-w 在 host 侧先 chdir）与 bash
  `groups: cannot find name for group ID`（Android 组 ID 不在 guest
  /etc/group）——shell 功能不受影响，未处理。
- 卸载重装 rootfs 后 hermes venv 在 rootfs 内随删（重装 Hermes 即恢复）；
  M2 曾用 root 域热修的 CA 文件会让重装时 clearDirectory 删不动
  （SELinux 上下文异，dontaudit 无日志）——需要 root 清理，普通用户路径
  （应用内安装/卸载，不经 root 热修）不受影响。
