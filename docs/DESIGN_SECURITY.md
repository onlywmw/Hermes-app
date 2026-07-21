# DESIGN: 安全修复 — P0

版本: v1.0
日期: 2026-07-22
状态: 📐 design-ready

---

## 1. /sdcard 硬编码路径 → Scoped Storage

### 问题

`CapabilityExecutor.java` 和 `StorageManager.java` 中所有路径硬编码为 `/sdcard/mov/...`。Android 11+ Scoped Storage 下，直接写 `/sdcard/` 会被拒绝。Manifest 未声明 `MANAGE_EXTERNAL_STORAGE`。当前能跑是因为旧安装覆盖保留了旧权限，全新安装或系统更新后文件功能全挂。

### 方案

全面迁移到 `Context.getExternalFilesDir()`：

```java
// 旧
private static final String BASE = "/sdcard/mov/";

// 新
private final File baseDir;

public StorageManager(Context context) {
    this.baseDir = new File(context.getExternalFilesDir(null), "mov");
    this.baseDir.mkdirs();
}

// 所有路径从 baseDir 派生:
// rooms/   → new File(baseDir, "rooms/")
// personal/ → new File(baseDir, "personal/")
// templates/ → new File(baseDir, "templates/")
```

### 影响文件

| 文件 | 改动 |
|------|------|
| `CapabilityExecutor.java` | `ROOMS_BASE` 改为构造注入 or 从 Context 获取 |
| `StorageManager.java` | `BASE` 常量 → 实例字段，构造时注入 |
| `HermesActivity.java` | 初始化时传 Context 给 StorageManager |

### 迁移兼容

旧数据在 `/sdcard/mov/` 下。首次启动 v4.0：
1. 检查 `context.getExternalFilesDir(null)/mov/` 是否为空
2. 如果为空且 `/sdcard/mov/` 存在 → 复制到新路径
3. 迁移完成 → 写标记，不再重复

### 验收

- Android 11+ 全新安装：创建房间 → 文件功能正常
- 旧用户升级：旧文件自动迁移到新路径

---

## 2. Widget Receiver exported=true

### 问题

`AndroidManifest.xml` 中 `HermesWidgetProvider` 的 `android:exported="true"`。任何第三方应用可以发 `ACTION_EXECUTE` Intent 附带任意指令字符串，触发 `IntentParser.parse()` → `CapabilityExecutor.execute()`——包括文件读写、发短信、打电话。

### 方案

#### 2.1 加权限保护

```xml
<receiver
    android:name=".widget.HermesWidgetProvider"
    android:exported="true"
    android:permission="com.hermes.android.permission.EXECUTE_WIDGET">
```

自定义权限 `EXECUTE_WIDGET` 签名级保护——只有同签名的应用能触发。小组件是系统发起的，系统持有签名级权限不受影响。

#### 2.2 指令白名单

在 `HermesWidgetProvider.onReceive()` 中加白名单过滤：

```java
private static final Set<String> ALLOWED_COMMANDS = Set.of(
    "打开手电筒", "关闭手电筒", "电量多少", "当前音量",
    "WiFi状态", "震动", "亮度调到 128", "设备信息",
    "截屏", "ip地址", "应用列表", "联系人",
    "最近短信", "读取剪贴板"
);

// executeCommand 中:
if (!ALLOWED_COMMANDS.contains(command)) {
    Toast.makeText(context, "❌ 小组件不支持此指令", Toast.LENGTH_SHORT).show();
    return;
}
```

**白名单策略**：只允许小组件预置的 14 个快捷指令。不支持"打电话"、"写文件"等危险操作。

### 验收

- 小组件点击指令 → 正常执行
- 第三方应用发 `ACTION_EXECUTE` Intent → 被权限拒绝

---

## 3. 存储型 XSS

### 问题

`render.js` 的 `rebuildMsgs()` 从 `localStorage` 取出 `d.h` 直接 `innerHTML` 注入：

```javascript
// 危险
td.innerHTML = d.h;
```

攻击面：
- `board.js` 中用户添加的 iframe URL 无校验 → 恶意页面可写 localStorage
- `chat.js` 种子消息里含 `<code>` 标签 → 已验证可注入 HTML
- `store.js` 从 localStorage 恢复 `msgData` → 直接走 `rebuildMsgs`

WebView 中 XSS 可调用 `window.HermesBridge` 的所有 30+ 个 Java 桥方法。

### 方案

#### 3.1 消息渲染：不要 innerHTML，用 textContent

```javascript
// 改前
d.innerHTML = '<div class="bubble">' + inner + '</div>';

// 改后
var bubble = document.createElement('div');
bubble.className = 'bubble';
bubble.textContent = content;
d.appendChild(bubble);
```

对于需要保留的 `<code>` 标签：白名单标签，用 `document.createElement` 构建，不用字符串拼接。

```javascript
// 安全的 <code> 渲染
function safeBubble(html) {
    var div = document.createElement('div');
    // 只提取 <code> 标签内容，其余全转 textContent
    var parts = html.split(/(<code>.*?<\/code>)/g);
    parts.forEach(function(part) {
        if (part.startsWith('<code>') && part.endsWith('</code>')) {
            var code = document.createElement('code');
            code.textContent = part.slice(6, -7);
            div.appendChild(code);
        } else {
            div.appendChild(document.createTextNode(part));
        }
    });
    return div;
}
```

#### 3.2 工具卡片：component 化

`toolNode()` 已经用 `document.createElement` 构建 DOM，不拼接 HTML 字符串。保持。

#### 3.3 投票/计划/交付卡片：保持 innerHTML 但加输入校验

这些卡片来自 `CouncilClient.java` 的 AI 返回内容 → 服务器端可控。但 AI 返回不可信。

**最简方案**：渲染前 strip 掉 `<script>`、`onerror`、`onclick` 等事件处理器。

```javascript
function sanitize(html) {
    return html
        .replace(/<script[^>]*>.*?<\/script>/gi, '')
        .replace(/\son\w+\s*=\s*"[^"]*"/gi, '')
        .replace(/\son\w+\s*=\s*'[^']*'/gi, '');
}
```

#### 3.4 board.js iframe URL 校验

```javascript
function isValidUrl(url) {
    try {
        var u = new URL(url);
        return u.protocol === 'https:' || u.protocol === 'http:';
    } catch(e) { return false; }
}

function loadBoardApp(id) {
    var app = _boardApps.find(function(a) { return a.id === id; });
    if (!app) return;
    if (app.type === 'url' && !isValidUrl(app.source)) {
        B.toast(t('board.invalidUrl'));
        return;
    }
    $('boardFrame').src = app.source;
}
```

### 影响文件

| 文件 | 改动 |
|------|------|
| `js/render.js` | `mkMsg()` 和 `rebuildMsgs()` 改用 textContent + 安全 code 渲染 |
| `js/render.js` | 新增 `sanitize()` 函数 |
| `js/board.js` | `loadBoardApp()` 加 URL 校验 |
| `js/council.js` | `toolNode()` 保持（已用 DOM API） |

### 验收

- 房间数据中包含 `<img src=x onerror=alert(1)>` → 不执行脚本
- 用户添加 iframe URL 为 `javascript:alert(1)` → 被拒绝
- 投票/计划/交付卡片正常渲染（不丢样式）
