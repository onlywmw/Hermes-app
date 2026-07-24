# CONTRACT: 存储系统

版本: v1.0
日期: 2026-07-22
status: design-ready
交付对象: 后端程序员

---

## 验收测试用例

### TC-S01：创建房间时初始化存储目录

```
Given: 用户创建新房间 id="r12345"
When: initRoomStorage("r12345") 被调用
Then:
  1. 目录 getExternalFilesDir/mov/rooms/r12345/files/work/ 存在
  2. 目录 .../work-snapshots/ 存在
  3. 目录 .../inbox/ 存在
  4. 目录 .../archive/ 存在
  5. 目录 .../.meta/ 存在
  6. 文件 .../.meta/index.json 存在, 内容 {"files":[]}
  7. 所有路径在 getExternalFilesDir 下, 不在 /sdcard/ 根目录
```

### TC-S02：写入产出文件 → 自动快照旧版本

```
Given: rooms/r1/files/work/src/a.ts 已存在 (v1, 内容 "old")
When: saveWorkFile("r1", "src/a.ts", "new", "DeepSeek")
Then:
  1. a.ts 当前内容 = "new"
  2. work-snapshots/ 下存在快照文件 (文件名含 a.ts 和时间戳)
  3. 快照内容 = "old"
  4. .meta/index.json 中 a.ts 的记录: author="DeepSeek", size=3
  5. listVersions("r1", "src/a.ts") 返回至少 1 个版本
```

### TC-S03：恢复旧版本

```
Given: a.ts v1="old", v2="new"
When: restoreVersion("r1", "src/a.ts", snapshotName)
Then:
  1. a.ts 当前内容 = "old" (v1 恢复)
  2. 恢复前 v2 被快照保存 (不丢失)
  3. 返回 {"ok":true}
```

### TC-S04：写入文件被锁定 → 拒绝

```
Given: a.ts 被 lock("r1", "src/a.ts", "DeepSeek") 锁定
When: 另一个请求 saveWorkFile("r1", "src/a.ts", "new", "Claude")
Then:
  1. 返回 {"ok":false, "error":"文件被 DeepSeek 锁定"}
  2. a.ts 内容不变
```

### TC-S05：锁超时自动释放

```
Given: a.ts 被锁定, lock.expires_at = 5分钟前
When: 另一个请求 saveWorkFile
Then:
  1. 旧锁被忽略 (过期)
  2. 文件正常写入
```

### TC-S06：搜索文件名

```
Given: 3 个房间, 每个有 5 个文件, 文件名含 "login"
When: 搜索 "login"
Then:
  1. 返回所有匹配文件 (跨房间)
  2. 每个结果含 roomId, path, type, author
  3. 不返回 inbox/archive 类型的文件 (只搜 work)
```

### TC-S07：写入超大内容被拒绝

```
Given: content.length > 5MB
When: saveWorkFile(...)
Then:
  1. BridgeValidator.checkContent 返回错误
  2. 文件不写入磁盘
  3. JS 侧收到 {"ok":false, "error":"内容过大 (>5MB)"}
```

### TC-S08：路径包含 ".." 被拒绝

```
Given: path = "../../etc/hosts"
When: saveWorkFile(...)
Then:
  1. BridgeValidator.checkPath 返回错误
  2. 文件不写入
```

---

## 发送到桌面（产出物一键上桌面，点开全屏即玩）

### 链路

```
文件 tab 长按产出卡 → 操作菜单「发送到桌面」
→ BridgeFile.pinFileShortcut(roomId, path, label)
→ ShortcutManagerCompat.requestPinShortcut (系统弹窗确认, 正常行为)
→ 桌面图标 → 点击 → HtmlViewerActivity (singleTask, exported=true)
→ StorageManager.resolveWorkFile 校验 → 全屏 WebView 加载
  rooms/<roomId>/files/work/<path>
```

### 安全约束

1. **roomId/path 全过 `BridgeValidator`；`resolveWorkFile` canonical 后必须在 work 目录内**，文件不存在显示错误页，不崩溃。
2. **label 消毒：去控制字符 + 限 20 字**（`BridgeValidator.sanitizeLabel`），空则回退文件名。
3. **HtmlViewerActivity 不注册 HermesBridge**（纯展示）；URL 白名单只放行 work 目录内 `file://` 导航，http/https/其他 file 一律拦截。WebView 开 JS + DOM storage（游戏需要），关 content 访问。
4. **「发送到桌面」仅产出 (work) 文件可见**；资料/归档不提供（查看器只读 work 目录）。
5. **MIUI 需「桌面快捷方式」权限**：`requestPinShortcut` 返回 false 时桥返回含引导的错误文案（设置→应用管理→MOV→权限管理→桌面快捷方式）。**实测 MIUI 陷阱（2026-07-23, MIUI Pad）**：未授权时 `requestPinShortcut` 仍返回 `true` 但静默不添加（`appops get` 可见 `MIUIOP(10017): ignore` + rejectTime）；`appops set <pkg> 10017 allow` 或用户手动授权后直接添加成功，且不再弹系统确认框。
6. **HtmlViewerActivity 必须显式 `setWebChromeClient(new WebChromeClient())`**：不设置时 WebView 静默吞掉 JS `alert()`（游戏"游戏结束"提示会丢）；且 alert 弹窗期间 `Runtime.evaluate` 阻塞（CDP 验证脚本需先 dismiss）。

### TC-S09：发送到桌面 → 全屏打开

```
Given: 房间 r1 work 目录有 snake.html
When: 文件 tab 长按 snake.html → 发送到桌面 → 系统弹窗确认添加
Then:
  1. 桌面出现以文件名命名的图标
  2. 点击图标 → HtmlViewerActivity 全屏打开 snake.html, JS/localStorage 可用, 游戏可玩
  3. 返回键退出查看器
```

### TC-S10：路径遍历被拒绝

```
Given: pinFileShortcut("desk", "../../x", "t")
When: 调桥
Then:
  1. BridgeValidator.checkPath 返回错误
  2. 不创建任何快捷方式
```

### TC-S11：快捷方式指向已删除文件

```
Given: 桌面图标已添加, 之后 snake.html 被删除
When: 点击桌面图标
Then:
  1. HtmlViewerActivity 显示错误页 (文件不存在或已被删除)
  2. 不崩溃, 返回键正常退出
```

---

## 打包成应用（产出 HTML 一键打成真 APK 安装）

### 链路

```
文件 tab 长按产出 .html 卡 → 操作菜单「打包成应用」→ 输入应用名 (≤16 字符)
→ HermesBridge.buildApk(roomId, path, appName, cbId) (后台线程, 回调式)
→ BridgeFile.buildApk → PackageBuilder.build:
  1. resolveWorkFile 取 HTML (限 .html/.htm, ≤5MB)
  2. 复制 assets/shell-template.apk (PC 预编译壳, 含 classes.dex + 占位符 manifest)
  3. ApkAssembler: AxmlPatcher 在二进制 AXML string pool 等长替换
     包名占位 (24 字符 com.movgen.app0000000000 → com.movgen.a+12随机)
     应用名占位 (16 字符 MOVAPPPLACEHOLDR → 消毒后 label 空格补足)
     注入 assets/app.html = 房间 HTML
  4. apksig (com.android.tools.build:apksig) v1+v2 签名, minSdk 26
→ 成功: FileProvider (com.hermes.android.fileprovider) 授权
  + ACTION_VIEW "application/vnd.android.package-archive" 调起系统安装器
→ 系统安装确认弹窗 (正常行为) → 桌面独立图标 → 点开即玩
```

### 关键决策与约束

1. **路线 = PC 预编译壳 + 手机只组装**。壳工程 `templates/webapp-shell/`（独立 settings.gradle，主工程不 include），重建命令 `./gradlew -p templates/webapp-shell assembleRelease --offline`。壳 Activity 纯 framework 零 androidx，APK ≈3.8KB。手机端零外部依赖（不要 Termux/root/Linux）。
2. **签名密钥共享内嵌**：`assets/movgen-sign.p12`（PKCS12, alias=movgen, 口令 movgen123, RSA2048, CN=MOV Generated Apps, 30 年）。所有 MOV 安装产出的 APK 共享同一"MOV 生成"签名身份——同包名可互相覆盖升级；**不可用于应用商店分发**。口令以明文存于源码是有意为之（本地工具属性），文档如实说明。
3. **包名必须恰好 24 字符且不含空格**：AXML string pool 等长替换要求。`genPackageName(roomId, htmlName)` = "com.movgen.h" + md5(roomId + "/" + 文件名) 前 12 位 hex = 24（稳定包名，同房间同 HTML 重复打包 = 覆盖升级，支持菜单迭代）；无参随机版仅作兜底。禁止用 padRight 补包名（尾部空格 = 非法包名）。label 空格补足 16 UTF-16 单元（尾部空格显示不可见）。
4. **等长替换失败即抛异常**：替换串编码后字节数必须与原占位符完全一致；模板缺占位符时 ApkAssembler 抛 IOException（提示重建壳模板）。
5. **resources.arsc 包名补丁是双保险**：无资源的壳模板 arsc 只有表头+空全局 string pool（无 package chunk），`patchArscPackageName` 跳过并返回 0（不算失败）；包名由 manifest 决定。
6. **安全约束**：roomId/path 全过 BridgeValidator；限 work 区 `.html/.htm`（扩展名检查先于存在性检查）；≤5MB；label 经 `sanitizeLabel` 限 16 单元，空回退文件名再回退 "MOV App"。输出在 `files/packager/<pkg>.apk`（应用私有目录，FileProvider files-path 暴露）。
7. **REQUEST_INSTALL_PACKAGES 权限**：manifest 已声明。MIUI 首次安装弹「是否允许 MOV 安装应用」，勾选"记住我的选择"后后续安装只弹一次「继续安装」。**实测 MIUI 行为（2026-07-23, MIUI Pad）**：点"允许"后系统会自动补完此前挂起的安装 intent（可能一次装上多个待装包）；**短时间内多次应用内安装会触发风控**「MOV频繁安装应用」滑块拼图验证（人工验证，自动化无法代过）——属平台反诈机制，正常使用频率不会触发。
8. **壳 WebView 必须重写 `onJsAlert/onJsConfirm`**：默认弹窗依赖 Activity 主题，`Theme.NoTitleBar.Fullscreen` 下实测不渲染——`alert()` 阻塞 JS 线程但无界面，游戏假死（此前"画面冻结"即此因）。修复为显式 `AlertDialog` + `Theme_DeviceDefault_Dialog_Alert`。
9. **壳开启 `WebView.setWebContentsDebuggingEnabled(true)`**：生成应用暴露 `webview_devtools_remote_<pid>` 供 CDP 诊断/E2E（本地侧载工具定位，文档如实说明）。
10. **JS 入口仅 work 区 .html 可见**（fopsApk 显示条件与 fopsPin 同规则 + 扩展名过滤）。

### TC-S12：HTML 打包成 APK → 安装 → 独立图标可玩

```
Given: 房间 work 目录有 snake.html
When: 文件 tab 长按 snake.html → 打包成应用 → 应用名"贪吃蛇" → 开始打包
Then:
  1. 桥回调 ok=true, 返回 packageName (com.movgen.a*) 与 sizeBytes
  2. 系统安装器弹窗出现 (MIUI 首次先引导未知来源授权)
  3. 确认安装后桌面出现"贪吃蛇"独立图标
  4. 点击图标 → 全屏打开, 游戏可玩
  5. dumpsys package | grep com.movgen 可查到该包
```

### TC-S13：打包参数校验

```
Given: buildApk(r1, "../../etc/x", "t") / buildApk(r1, "notes.md", "t") / buildApk(r1, "ghost.html", "t")
When: 调桥
Then:
  1. 路径遍历被 BridgeValidator 拒绝
  2. 非 .html/.htm 被拒绝 ("只支持打包 HTML 文件")
  3. 文件不存在返回错误, 不产出任何 APK
```

---

## 实现约束（不可违反）

1. **存储根路径：`context.getExternalFilesDir(null) + "/mov/"`。** 禁止硬编码 `/sdcard/mov/`。`StorageManager.BASE` 由 `init(Context)` 运行时设置。
2. **文件写入前必须过 `BridgeValidator.checkPath()` + `BridgeValidator.checkContent()`。** 不过不写盘。
3. **每个文件操作必须通过 `isSafe(base, target)` 检查。** 禁止绕过。
4. **快照文件命名：`{path}_{timestamp}`。** path 中的 `/` 替换为 `_`。timestamp 格式 yyyyMMdd_HHmmss。
5. **所有磁盘 IO 在调用线程执行。** `saveWorkFile`/`restoreVersion` 是同步方法，调用方负责放到子线程。
6. **index.json 读写不是原子的。** 并发写同一个文件时，后写的覆盖先写的。代码不需要处理并发——JS 单线程，不存在并发写同一房间的场景。
7. **`listDir` 必须过滤 `.` 开头的隐藏文件和目录。** `.meta`、`.mov` 不对外暴露。

---

## Linux 交换目录（M1 内嵌 Linux, 2026-07-24）

房间 ⇆ 内嵌 Ubuntu 的唯一文件通道。**不进 rooms/ 树**，布局：

```
context.getFilesDir()/
  linux/
    rootfs/               Ubuntu 24.04 rootfs (proot -r 目标)
    ubuntu-base.tar.gz    安装包暂存 (解压成功即删)
    state.json            RootfsManager 状态 {"state":"READY","msg":""}
    tmp/                  proot TMPDIR/PROOT_TMP_DIR
  linux-exchange/         guest 内挂 /exchange (proot -b)
```

- **file.push**：`rooms/<id>/files/work/<path>` → `linux-exchange/<文件名>`（二进制拷贝）
- **file.pull**：`linux-exchange/<文件名>` → `rooms/<id>/files/work/<文件名>`（取回 APK 后可走"打包成应用"安装链路）
- 两侧都过 `isSafe()` canonical 校验；exchange 侧限 linux-exchange 前缀，禁止 `..` 越界
- RootfsManager 卸载时清空 linux/rootfs 与 linux-exchange 内容；state.json 的
  DOWNLOADING/EXTRACTING/BOOTSTRAPPING 在进程重启后一律回落 NOT_INSTALLED
- 本地包优先：`/sdcard/MOV/ubuntu-base.tar.gz` 存在且已授 MANAGE_EXTERNAL_STORAGE 时
  免下载（预置包：shell 域装好 python3/git 后 `tar --hard-dereference` 重打包，
  消除 app 域无法创建的硬链接）

---

## 关联合同

- [CONTRACT_ARCH.md](CONTRACT_ARCH.md)
