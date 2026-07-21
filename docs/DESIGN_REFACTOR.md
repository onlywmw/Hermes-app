# DESIGN: 架构重构 — P1

版本: v1.0
日期: 2026-07-22
状态: 📐 design-ready

---

## 1. HermesActivity 拆分

### 问题

`HermesActivity.java` 885 行。一个内部类 `HermesBridge` 含 30+ 个 `@JavascriptInterface` 方法——AI、Cron、技能、存储、文件、设备信息、权限全挤在一起。三人协作必然冲突。

### 方案

按职责拆成 5 个 Bridge 类，统一注册到 WebView：

```
HermesActivity (壳, ~200行)
  └─ WebView.addJavascriptInterface(bridge, "HermesBridge")

BridgeFactory (新)
  └─ 聚合 5 个子 Bridge → 一个入口对象注入 WebView

com.hermes.android.bridge/
  BridgeDevice.java      ← execCommand + parseIntent + getDeviceInfo + getRuntimeStats
                           + getPermissionState + getWidgetInfo + openAppSettings
  BridgeAi.java          ← aiChatAsync + aiChat + councilAsync + getAiInfo + getLanguage
  BridgeFile.java        ← writeFile + readFile + deleteFile + listRoomFiles
                           + initRoom + initRoomStorage + pickFile
                           + listWorkFiles + saveWorkFile + listVersions + restoreVersion
                           + listInboxFiles + listArchiveFiles + writeArchive
  BridgeCron.java        ← listCronJobs + createCronJob + toggleCronJob + deleteCronJob
  BridgeSkill.java       ← listSkills + recordSkillUse + deleteSkill
  BridgeTemplate.java    ← listTemplates + saveTemplate + useTemplate
  BridgeNote.java        ← listNotes + saveNote + readNote + deleteNote
  BridgeChat.java        ← appendChatMessage + loadChatMessages + getRoomMeta

HermesActivity (精简后, ~200行)
  ├─ 创建 WebView + 配置 WebSettings
  ├─ 权限请求
  ├─ 生命周期 (onCreate/onResume/onDestroy)
  ├─ onCreate → BridgeFactory.registerAll(shell)
  ├─ 聊天历史持久化 (save/restore)
  └─ 文件选择器回调
```

### Bridge 基类

```java
// com.hermes.android.bridge.BaseBridge.java
public abstract class BaseBridge {
    protected final HermesActivity activity;
    protected final IntentParser parser;
    protected final CapabilityExecutor executor;
    protected final AiProviderConfig aiConfig;
    protected final CronManager cronManager;
    protected final SkillStore skillStore;

    public BaseBridge(HermesActivity activity) {
        this.activity = activity;
        this.parser = new IntentParser();
        this.executor = new CapabilityExecutor();
        this.aiConfig = new AiProviderConfig(activity);
        this.cronManager = new CronManager(activity);
        this.skillStore = new SkillStore(activity);
    }

    // 子类用 activity.evalJs(script) 回调 JS
}
```

### BridgeFactory

```java
public class BridgeFactory {
    public static Object createBridge(HermesActivity activity) {
        return new Object() {
            // execCommand + parseIntent (BridgeDevice)
            @JavascriptInterface
            public String execCommand(String text) {
                return new BridgeDevice(activity).execCommand(text);
            }
            @JavascriptInterface
            public String parseIntent(String text) {
                return new BridgeDevice(activity).parseIntent(text);
            }
            // ... 其余 30+ 方法同理委托
        };
    }
}
```

### 影响文件

| 文件 | 改动 |
|------|------|
| `HermesActivity.java` | 885 → ~200 行。Bridge 方法全移到子类 |
| `bridge/BaseBridge.java` | **新建** ~30行 |
| `bridge/BridgeDevice.java` | **新建** ~80行 |
| `bridge/BridgeAi.java` | **新建** ~100行 |
| `bridge/BridgeFile.java` | **新建** ~120行 |
| `bridge/BridgeCron.java` | **新建** ~30行 |
| `bridge/BridgeSkill.java` | **新建** ~30行 |
| `bridge/BridgeTemplate.java` | **新建** ~30行 |
| `bridge/BridgeNote.java` | **新建** ~30行 |
| `bridge/BridgeChat.java` | **新建** ~30行 |
| `bridge/BridgeFactory.java` | **新建** ~60行 |

### 验收

- 所有现有功能正常工作（Bridge 方法签名不变，JS 侧不需改动）
- 三个程序员同时改 BridgeAi、BridgeFile、BridgeCron 不冲突
- HermesActivity 不再包含任何 `@JavascriptInterface` 方法

---

## 2. Process 资源泄漏

### 问题

`CapabilityExecutor.java` 的 `doSystemInfo()` 和 `doProcessList()` 中：

```java
Process proc = Runtime.getRuntime().exec("cat /proc/meminfo");
BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
// ... 读取 ...
br.close();
// proc 从未 destroy() — 泄漏进程句柄
```

每次调用泄漏一个 Process 对象，长期运行耗尽 fd。

### 方案

所有 `Runtime.exec()` 用 try-with-resources 或 finally 确保 destroy：

```java
Process proc = null;
try {
    proc = Runtime.getRuntime().exec("cat /proc/meminfo");
    try (BufferedReader br = new BufferedReader(
             new InputStreamReader(proc.getInputStream()))) {
        // 读取 ...
    }
    proc.waitFor();
} catch (Exception ignored) {
} finally {
    if (proc != null) proc.destroy();
}
```

涉及的方法：
- `doSystemInfo()` — `cat /proc/meminfo`
- `doProcessList()` — `ps -ef`
- `doScreenCapture()` — `screencap`
- `doInputTap()` — `input tap`
- `doInputSwipe()` — `input swipe`

### 影响文件

| 文件 | 改动 |
|------|------|
| `CapabilityExecutor.java` | 5 个方法加 finally destroy() |

### 验收

- 连续执行 `设备信息` 100 次 → lsof 查看 fd 数量不增长

---

## 3. Council 真实化 + 并行调用

### 问题

`CouncilClient.java` 中 3 个 AI 角色串行调用 + 1 次汇总调用 = 4 次网络请求串行。用户等 4× 单次延迟。

`council.js` 的 `runFitCouncil()` 是 98 行硬编码 `sleep()` 剧本——fit 房间首次进入不会真正调 AI。

### 方案

#### 3.1 并行化 Council 调用

```java
// 旧 (串行, 4x 延迟)
for (String[] role : ROLES) {
    AiClient client = new AiClient(config, systemPrompt);
    AiResponse resp = client.chat(topic, emptyHistory);
    messages.put(...);
}

// 新 (并行, 1x 延迟)
ExecutorService exec = Executors.newFixedThreadPool(3);
List<Future<JSONObject>> futures = new ArrayList<>();

for (String[] role : ROLES) {
    futures.add(exec.submit(() -> {
        AiClient client = new AiClient(config, role[2]);
        AiResponse resp = client.chat(topic, emptyHistory);
        return new JSONObject()
            .put("who", role[0])
            .put("role", role[1])
            .put("content", resp.success ? resp.content : "调用失败");
    }));
}

// 等所有返回 (最慢的一个决定总延迟)
for (Future<JSONObject> f : futures) {
    messages.put(f.get(30, TimeUnit.SECONDS));
}
exec.shutdown();
```

#### 3.2 替换硬编码剧本

`chat.js` 的 `enterRoom()` 中：

```javascript
// 旧
if (r.id === 'fit' && !r.played) {
    r.played = true;
    runFitCouncil(id);  // 硬编码剧本
}

// 新
if (r.id === 'fit' && !r.played) {
    r.played = true;
    // 用真实 Council 替代硬编码剧本
    runFitCouncilDemo(id);  // 推送种子消息 → 调 B.councilAsync
}
```

`runFitCouncilDemo`：推送 fit 房间的种子消息（产品/技术/数据三视角）→ 调真实 `B.councilAsync` → 后续交互走正常流程。不再用 `sleep()` 模拟延迟。

#### 3.3 线程池从当前 HermesActivity 移到 CouncilClient 内部

`HermesActivity` 的 `aiExecutor` (2 线程) 被 Council 独占时，普通 AI 对话被阻塞。

```java
// CouncilClient 内部
private static final ExecutorService COUNCIL_POOL =
    Executors.newFixedThreadPool(3);
```

### 影响文件

| 文件 | 改动 |
|------|------|
| `CouncilClient.java` | 并行化 → 3 线程并发 |
| `HermesActivity.java` | aiExecutor 保留（普通 AI 对话用） |
| `js/council.js` | `runFitCouncil()` 替换为真实调用 |
| `js/chat.js` | `enterRoom()` 改 fit 入口逻辑 |

### 验收

- Council 讨论从 4× 延迟 → ~1.5× 延迟（3 并行 + 1 汇总）
- fit 房间首次进入 → 看到真实 AI 讨论（不是 sleep 假数据）
