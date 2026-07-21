# DESIGN: MOV 匿名统计 v1.0

版本: v1.0
日期: 2026-07-21
状态: 📐 design-ready

---

## 原则

1. **只传统计，不传内容** — 聊天文字、文件名、Key、设备信息一律不上报
2. **用户主动开启** — 默认关闭，设置页开关，"帮助改进 MOV"
3. **本地聚合，一天一次** — 在本地把计数累加成摘要，每 24h 发一次
4. **完全可查** — 设置页可预览即将上报的原始 JSON，用户随时能看
5. **零依赖** — 不需要第三方 SDK，HttpURLConnection 直发

---

## 数据结构

### 上报的 JSON (一次典型)

```json
{
  "v": "3.1",
  "install": "2026-07-21T08:30:00Z",
  "day": "2026-07-21",
  "rooms": {
    "total": 5,
    "single": 3,
    "council": 2
  },
  "cmds": {
    "torch.on": 12,
    "torch.off": 8,
    "battery.status": 5,
    "brightness.set": 3,
    "tts.speak": 2,
    "file.write": 7,
    "file.read": 15,
    "council": 4,
    "clipboard.get": 3
  },
  "ai": {
    "provider": "deepseek",
    "model": "deepseek-v4-flash",
    "calls": 42,
    "avgLatencyMs": 3200,
    "maxLatencyMs": 12000,
    "errors": 2
  },
  "cron": {
    "total": 3,
    "active": 2,
    "executed": 5
  },
  "skills": {
    "total": 3,
    "triggered": 7
  },
  "sessions": {
    "count": 3,
    "totalMinutes": 45
  },
  "crashes": 0
}
```

### 不上报的内容 (明确禁止)

- 聊天文字 / 指令文本
- 文件名 / 文件路径 / 文件内容
- API Key / Base URL (只报 provider 名和 model 名)
- IP 地址 / 地理位置 / 设备型号
- 用户身份 / 房间名称

---

## 本地采集

### Java: StatsCollector.java (新文件)

`CapabilityExecutor.java` 同目录下新建。

```java
package com.hermes.android;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.UUID;

/**
 * 匿名使用统计。统计数字只在内存和 SharedPreferences 中累积，
 * 每 24h 聚合一次上报。用户可随时关闭或预览。
 */
public class StatsCollector {

    private static final String PREFS = "mov_stats";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_LAST_REPORT = "last_report_at";

    // 当天累计 (内存中, App 重启后从 prefs 恢复)
    private int aiCalls = 0;
    private long aiTotalMs = 0;
    private long aiMaxMs = 0;
    private int aiErrors = 0;
    private int sessionCount = 0;
    private long sessionStartMs = 0;
    private long sessionTotalMs = 0;
    private int cronExecuted = 0;
    private int crashCount = 0;
    private String aiProvider = "";
    private String aiModel = "";

    private final Context ctx;
    private final SharedPreferences prefs;
    private final String installId;

    public StatsCollector(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // 安装 ID: 首次生成, 之后不变。纯随机, 不关联设备。
        String id = prefs.getString(KEY_INSTALL_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_INSTALL_ID, id).apply();
        }
        this.installId = id;
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // ========== 计数方法 (由 CapabilityExecutor / HermesActivity 调用) ==========

    public void recordCommand(String capabilityId) {
        // 每次命令执行后调用
    }

    public void recordAiCall(String provider, String model, long latencyMs, boolean success) {
        aiProvider = provider;
        aiModel = model;
        aiCalls++;
        aiTotalMs += latencyMs;
        if (latencyMs > aiMaxMs) aiMaxMs = latencyMs;
        if (!success) aiErrors++;
    }

    public void recordCronExecuted() { cronExecuted++; }

    public void recordCrash() { crashCount++; }

    public void onSessionStart() {
        sessionCount++;
        sessionStartMs = System.currentTimeMillis();
    }

    public void onSessionEnd() {
        if (sessionStartMs > 0) {
            sessionTotalMs += (System.currentTimeMillis() - sessionStartMs);
            sessionStartMs = 0;
        }
    }

    // ========== 聚合 & 上报 ==========

    public boolean shouldReport() {
        long last = prefs.getLong(KEY_LAST_REPORT, 0);
        return (System.currentTimeMillis() - last) > 24 * 60 * 60 * 1000;
    }

    public JSONObject buildReport() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", "3.1");
            o.put("install", prefs.getString(KEY_INSTALL_ID, ""));
            o.put("day", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()));

            // AI 统计
            JSONObject aiObj = new JSONObject();
            aiObj.put("provider", aiProvider);
            aiObj.put("model", aiModel);
            aiObj.put("calls", aiCalls);
            aiObj.put("avgLatencyMs", aiCalls > 0 ? aiTotalMs / aiCalls : 0);
            aiObj.put("maxLatencyMs", aiMaxMs);
            aiObj.put("errors", aiErrors);
            o.put("ai", aiObj);

            // Session 统计
            JSONObject sess = new JSONObject();
            sess.put("count", sessionCount);
            sess.put("totalMinutes", sessionTotalMs / 60000);
            o.put("sessions", sess);

            o.put("cronExecuted", cronExecuted);
            o.put("crashes", crashCount);

            // 重置当日计数器
            resetCounters();

            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void resetCounters() {
        aiCalls = 0; aiTotalMs = 0; aiMaxMs = 0; aiErrors = 0;
        cronExecuted = 0; crashCount = 0;
        sessionCount = 0; sessionTotalMs = 0;
        // 保留 sessionStartMs 用于跨天会话
    }

    public void markReported() {
        prefs.edit().putLong(KEY_LAST_REPORT, System.currentTimeMillis()).apply();
    }

    /**
     * 用户可预览的即将上报 JSON (设置页展示用)
     */
    public String getPreviewJson() {
        return buildReport().toString();
    }
}
```

### 命令计数 (在 CapabilityExecutor 中埋点)

`CapabilityExecutor.execute()` 中已有：

```java
public CommandResult execute(Context ctx, ParsedCommand cmd) {
    // ... 现有逻辑 ...
    lastCmdName = cmd.getCapability();
    CMD_COUNT.incrementAndGet();
    // 新增: stats 记录
    // statsCollector.recordCommand(cmd.getCapability());
    return result;
}
```

`StatsCollector` 实例在 `HermesActivity` 中初始化并传递给需要的组件。

---

## 上报通道

### 最简单的免费后端: Supabase

```
POST https://<project>.supabase.co/rest/v1/mov_stats
Headers:
  apikey: <anon_key>
  Content-Type: application/json
Body: { ... stats JSON ... }
```

Supabase 免费额度: 500MB 数据库 + 50,000 月活用户。一个人用的话，永远免费。

### 上报逻辑 (在 StatsCollector 中)

```java
public void tryReport() {
    if (!isEnabled() || !shouldReport()) return;

    new Thread(() -> {
        try {
            JSONObject report = buildReport();
            URL url = new URL(REPORT_ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.getOutputStream().write(report.toString().getBytes());
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                markReported();
            }
            conn.disconnect();
        } catch (Exception e) {
            // 静默失败, 下次再试
        }
    }).start();
}
```

---

## 设置页开关

在 `HermesSettingsActivity` 底部新增:

```
┌─────────────────────────────┐
│ 帮助改进 MOV          [开关]│
│ 匿名发送使用统计, 不含任何   │
│ 个人或聊天内容              │
│                             │
│ [预览即将上报的数据]         │
└─────────────────────────────┘
```

点击"预览" → dialog 显示原始 JSON 文本。

---

## 触发时机

| 事件 | 位置 |
|------|------|
| `onCreate` → `onSessionStart()` | HermesActivity |
| `onDestroy` → `onSessionEnd()` | HermesActivity |
| `CapabilityExecutor.execute()` | recordCommand |
| `aiChatAsync` 回调 | recordAiCall |
| `CronManager` 执行 | recordCronExecuted |
| `window.onerror` (JS 侧) + Java 未捕获异常 | recordCrash |

---

## 你需要做的外部准备

1. 注册 [supabase.com](https://supabase.com) 免费账号
2. 创建一个项目 → 建表:

```sql
CREATE TABLE mov_stats (
  id SERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  data JSONB NOT NULL
);
```

3. 拿到 `SUPABASE_URL` 和 `SUPABASE_ANON_KEY`
4. 写入 `StatsCollector.java` 的两个常量

---

## 需要改的文件

| 文件 | 说明 |
|------|------|
| `StatsCollector.java` | **新建** — 核心统计类 |
| `CapabilityExecutor.java` | 命令计数埋点 (2 行) |
| `HermesActivity.java` | 会话统计埋点 + AI 调用统计 + crash 捕获 + tryReport 触发 |
| `CronManager.java` | Cron 执行统计 |
| `activity_hermes_settings.xml` | 设置页 UI: 开关 + 预览按钮 |
| `HermesSettingsActivity.java` | 开关逻辑 + 预览弹窗 |
