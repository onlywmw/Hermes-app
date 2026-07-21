# DESIGN: 打磨优化

版本: v1.0
日期: 2026-07-22
状态: 📐 design-ready

---

## 1. 版本迁移机制

### 问题

v3.1 → v4.0 改了存储路径、模型系统、房间数据结构。没有任何迁移代码。旧用户升级后 localStorage 格式不兼容、文件路径失效。

### 方案

启动时检查版本号 → 对比迁移列表 → 按需执行。

```java
// com.hermes.android.MigrationManager.java
public class MigrationManager {
    private static final String KEY_VERSION = "data_version";
    
    // 迁移列表: version -> 执行什么
    private static final Map<Integer, Runnable> MIGRATIONS = new LinkedHashMap<>();
    
    static {
        MIGRATIONS.put(1, MigrationManager::migrate_v1_storagePaths);   // /sdcard → getExternalFilesDir
        MIGRATIONS.put(2, MigrationManager::migrate_v2_roomMembers);    // 旧成员 → 新格式
        MIGRATIONS.put(3, MigrationManager::migrate_v3_msgDataToFiles); // localStorage msgData → 文件
    }
    
    public static void run(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("mov_meta", MODE_PRIVATE);
        int current = prefs.getInt(KEY_VERSION, 0);
        
        for (Map.Entry<Integer, Runnable> e : MIGRATIONS.entrySet()) {
            if (e.getKey() > current) {
                e.getValue().run();
                prefs.edit().putInt(KEY_VERSION, e.getKey()).apply();
            }
        }
    }
}
```

每条迁移做三件事：检查是否需要 → 执行 → 写完成标记。迁移失败不阻塞启动，记录日志。

### 影响文件

| 文件 | 改动 |
|------|------|
| `MigrationManager.java` | **新建** ~60行 |
| `HermesApplication.java` | `onCreate` 开头调 `MigrationManager.run(this)` |

---

## 2. app.js 拆分 — 按模块分文件

### 问题

`app.js` 230 行：事件绑定、新建房间 Sheet、房间操作 Sheet、Cron 创建、看板初始化、文件预览、子 tab 切换、帮助按钮、运行页刷新——全挤在一个文件。两个程序员加不同功能必然冲突。

### 方案

按 UI 模块拆成独立文件：

```
js/
  app.js          (入口, ~30行: 初始化 + 全局事件)
  app-chat.js     (~60行: 消息发送绑定、附件按钮、输入框)
  app-room.js     (~80行: 新建房间 Sheet、房间操作 Sheet、帮助按钮)
  app-board.js    (~30行: 看板事件绑定、应用管理)
  app-run.js      (~30行: 运行页刷新、Cron 创建、折叠行事件)
  app-files.js    (~40行: 文件预览关闭、新建文件 Sheet、子 tab 切换)
```

**加载顺序不变，依赖不变。** 每个文件只是从 `app.js` 搬出来的函数和事件绑定。

`app.js` 变成：

```javascript
/* app.js — 入口: 初始化 + 全局事件 */

/* 先加载所有子模块 (按依赖顺序) */
/* app-chat.js / app-room.js / app-board.js / app-run.js / app-files.js */

/* 初始化 */
initLang();
applyI18n();
renderRooms();
setTab('chat');
setTimeout(function(){ refreshRuntime(); renderSkillPage(); }, 600);
ev('MOV v3.2 ' + t('ready') + (B.present ? ' · ' + t('bridge.on') : ' · ' + t('bridge.off')));
```

### 影响文件

| 文件 | 改动 |
|------|------|
| `js/app.js` | 230→~30行 |
| `js/app-chat.js` | **新建** ~60行 (从 app.js 搬) |
| `js/app-room.js` | **新建** ~80行 (从 app.js 搬) |
| `js/app-board.js` | **新建** ~30行 (从 app.js 搬) |
| `js/app-run.js` | **新建** ~30行 (从 app.js 搬) |
| `js/app-files.js` | **新建** ~40行 (从 app.js 搬) |
| `hermes-shell.html` | 加 5 个 `<script src="js/app-xxx.js">` |

---

## 3. i18n 旧名字清理

### 问题

多处残留旧产品名和硬编码模型名：

| key | 当前值 | 问题 |
|-----|--------|------|
| `sheet.councilDesc` | "claude / gpt-5 / gemini 讨论 → 投票 → hermes 执行" | 硬编码了三个模型的旧名字。用户配的不是这些 |
| `council.converge` | "hermes 汇总" | 旧产品名 |
| `council.done` | "hermes 汇总" (en) | 同上 |
| `sheet.singleDesc` | "hermes 一对一" | 同上 |
| `rooms.agentLine` | "com.hermes.android" | 包名暴露 |
| `rooms.running` | 多处 | "hermes-agent" 残留 |

### 方案

全局替换：

```
"hermes" → "MOV"
"claude / gpt-5 / gemini 讨论 → 投票 → hermes 执行" → "多模型各抒己见 → 汇总 → MOV 执行"
"hermes-agent" → "mov-agent"
"com.hermes.android" → "原生能力引擎 · 30+ 接口"
```

### 影响文件

`js/i18n.js` — ~10 行改动。

---

## 4. 运行页 — 开发者指标下沉

### 问题

pid、JVM 内存、指令计数对普通用户毫无意义。它们应该在"关于"页或可折叠的"开发者信息"下面，而不是运行页首屏。

### 方案

进程卡片拆分：

```
运行页首屏:
  MOV 运行正常 · 已运行 2h 13m    ← 一行状态
  AI 模型 (DeepSeek / Claude / ...) ← 核心信息
  Cron 任务                        ← 核心信息

设置 → 关于:
  pid / JVM 内存 / 指令计数 / 版本号 / 安装ID
```

进程卡片保留"运行状态"和"运行时长"——用户看得懂且在乎。内存和指令计数移到关于页。

JS 改动：`refreshProcess()` 不再渲染内存条和指令条。这两个字段移到关于页的新 section。

### 影响文件

| 文件 | 改动 |
|------|------|
| `hermes-shell.html` | 运行页 PROC 卡片精简；关于页加开发者信息（或在运行页底部折叠） |
| `js/runtime.js` | `refreshProcess()` 精简 |

---

## 6. 关键路径自动化测试

### 问题

150+ 测试点靠人肉跑。每次改代码要重跑 2 小时。聊天消息的 push → persist → rebuild 链路、Council 的 modelIds → 并行调用 → 汇总链路——一旦出 bug 很难第一时间发现。

### 方案

**第一优先级：消息持久化循环**

```
测试: push 一条消息 → persistRooms() → 重建 ROOMS → rebuildMsgs()
       → 验证渲染出来的 DOM 内容 == 原始消息
```

这个测试可以完全在 JS 单元测试环境跑（Node.js + jsdom），不需要 Android 设备。

**第二优先级：IntentParser 回归**（已有 87 用例，保持）

**第三优先级：ModelRegistry CRUD**

```
测试: add → get → update → list → delete → 验证最后为空
```

JUnit，已有 ModelRegistry 类，直接写测试。

### 新增测试文件

| 文件 | 内容 | 环境 |
|------|------|------|
| `app/src/test/java/com/hermes/android/ModelRegistryTest.java` | add/get/update/delete/list/setDefault 循环 | JUnit |
| `app/src/test/java/com/hermes/android/MigrationManagerTest.java` | 迁移版本号递增、重复执行幂等 | JUnit |
| `tests/js/chat-persistence.test.js` | push → persist → rebuild 循环 | Node.js（以后） |

---

## 实施顺序

```
1. i18n 清理 (10行, 5分钟)
2. app.js 拆分 (纯搬家, 30分钟, 不改逻辑)
3. 迁移机制 (Java, 60行, 30分钟)
4. 运行页精简 (JS+HTML, 20分钟)
5. 自动化测试 (按需加)
```

1-4 总共约 1.5 小时，一人即可完成。
