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

## 实现约束（不可违反）

1. **存储根路径：`context.getExternalFilesDir(null) + "/mov/"`。** 禁止硬编码 `/sdcard/mov/`。`StorageManager.BASE` 由 `init(Context)` 运行时设置。
2. **文件写入前必须过 `BridgeValidator.checkPath()` + `BridgeValidator.checkContent()`。** 不过不写盘。
3. **每个文件操作必须通过 `isSafe(base, target)` 检查。** 禁止绕过。
4. **快照文件命名：`{path}_{timestamp}`。** path 中的 `/` 替换为 `_`。timestamp 格式 yyyyMMdd_HHmmss。
5. **所有磁盘 IO 在调用线程执行。** `saveWorkFile`/`restoreVersion` 是同步方法，调用方负责放到子线程。
6. **index.json 读写不是原子的。** 并发写同一个文件时，后写的覆盖先写的。代码不需要处理并发——JS 单线程，不存在并发写同一房间的场景。
7. **`listDir` 必须过滤 `.` 开头的隐藏文件和目录。** `.meta`、`.mov` 不对外暴露。
