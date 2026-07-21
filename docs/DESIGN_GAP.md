# PLAN: 验收缺口修复

版本: v1.0
日期: 2026-07-22
来源: 多模型闭环验收报告

---

## 缺口分级

| 优先级 | 缺口 | 影响 |
|--------|------|------|
| P0 | JS bridge 缺模型方法 | 前端无法列出/添加/删除模型，运行页模型区不工作 |
| P0 | 新建房间缺模型勾选 UI | 第 2 层"选哪些模型参与"无法使用 |
| P1 | /sdcard 硬编码路径 | Android 11+ 全新安装文件功能崩溃 |
| P1 | Widget receiver 无权限保护 | 第三方应用可触发设备命令执行 |
| P1 | XSS: innerHTML 注入 | WebView 中可调用 Java 桥方法 |
| P2 | Process 资源泄漏 | 长期运行耗尽文件描述符 |
| P2 | HermesActivity God Class | 三人协作冲突 |
| P2 | 文档死引用 | 新人被误导 |

---

## P0-1: JS bridge 补模型方法

### 问题

`HermesActivity` 已有 Java 桥方法（listModels/listModelsFull/addModel/updateModel/deleteModel/setDefaultModel），但 `bridge.js` 没有封装——前端调不了。

### 方案

`bridge.js` 加 5 行：

```javascript
listModels:   function(){ try{return b?JSON.parse(b.listModels()):[];}catch(e){return[];} },
listModelsFull:function(){ try{return b?JSON.parse(b.listModelsFull()):[];}catch(e){return[];} },
addModel:     function(m){ try{return b?JSON.parse(b.addModel(m)):{ok:false};}catch(e){return{ok:false};} },
deleteModel:  function(id){try{return b?JSON.parse(b.deleteModel(id)):{ok:false};}catch(e){return{ok:false};} },
setDefaultModel:function(id){try{if(b)b.setDefaultModel(id);}catch(e){}},
```

### 影响文件

`js/bridge.js` — 5 行

---

## P0-2: 新建房间加模型勾选

### 问题

新建房间第二步"拉 AI 团队"仍显示旧文案，没有从 ModelRegistry 动态生成勾选列表。

### 方案

`app.js` — 新建房间第二步：

```javascript
// 旧: 硬编码 <div class="mopt sel" data-mode="council">...
// 新: 动态渲染模型勾选列表

function renderModelChecklist() {
    var models = B.listModels();
    var h = '';
    models.forEach(function(m) {
        var disabled = !m.enabled || !m.configured ? ' disabled' : '';
        var note = !m.configured ? ' (未配置)' : (!m.enabled ? ' (已禁用)' : '');
        h += '<div class="mopt' + (m.isDefault ? ' sel' : '') + disabled
          + '" data-model="' + esc(m.id) + '">'
          + '<b>' + esc(m.name || m.provider) + '</b>'
          + '<span>' + esc(m.role) + ' · ' + esc(m.model) + note + '</span></div>';
    });
    $('modelChecklist').innerHTML = h;
    // 绑定多选逻辑...
}
```

HTML 改动：第二步的"议会"卡片改为 `#modelChecklist` 容器，动态填充。

### 影响文件

| 文件 | 改动 |
|------|------|
| `hermes-shell.html` | 第二步: 静态"议会/单聊"→ 动态 `#modelChecklist` |
| `js/app.js` | `renderModelChecklist()` + 新建时读选中模型 ID |

---

## P1-1: /sdcard → getExternalFilesDir

### 问题

`StorageManager.BASE = "/sdcard/mov/"` — Android 11+ 直接写外部存储根目录会被拒绝。

### 方案

`StorageManager` 改为实例类（不再是纯静态方法），构造时接收 Context：

```java
public class StorageManager {
    private final File baseDir;
    
    public StorageManager(Context context) {
        this.baseDir = new File(context.getExternalFilesDir(null), "mov");
        this.baseDir.mkdirs();
    }
    
    // 所有路径从 baseDir 派生，不再用 BASE 常量
}
```

`HermesActivity` 初始化时 `new StorageManager(this)`。

### 迁移

首次启动检查旧路径 `/sdcard/mov/` → 如果有数据且新路径为空 → 复制迁移。

### 影响文件

| 文件 | 改动 |
|------|------|
| `StorageManager.java` | `BASE` 常量 → 实例字段 + 构造注入 |
| `HermesActivity.java` | 初始化 StorageManager 实例 |

---

## P1-2: Widget 权限保护

### 问题

`AndroidManifest.xml` — `HermesWidgetProvider` receiver `exported="true"` 无权限保护。

### 方案

两种方式任选其一：

**A: 权限保护（推荐）**

```xml
<receiver
    android:name=".widget.HermesWidgetProvider"
    android:exported="true"
    android:permission="com.hermes.android.permission.EXECUTE_WIDGET">
```

自定义权限 `EXECUTE_WIDGET` signature 级——只有同签名应用能发 Intent。系统（小组件宿主）持有签名级权限不受影响。

**B: 指令白名单**（额外防线）

在 `HermesWidgetProvider.executeCommand()` 中，只允许预置 14 个快捷指令。拒绝"打电话"、"写文件"等危险操作——即使攻击者突破了权限保护也做不了危险动作。

### 影响文件

| 文件 | 改动 |
|------|------|
| `AndroidManifest.xml` | receiver 加 `android:permission` |
| `HermesWidgetProvider.java` | executeCommand 加白名单过滤 |

---

## P1-3: XSS 修复

### 问题

`render.js` 的 `rebuildMsgs()` 和 `mkMsg()` 用 `innerHTML` 直接注入从 localStorage 恢复的内容。

### 方案

**消息气泡**：改用 `document.createElement` + `textContent`。需要保留 `<code>` 标签的走白名单提取。

**工具/投票/计划/交付卡片**：这些来自 AI 返回（服务器端可控）或本地生成。渲染前加 `sanitize()` 函数去掉 `<script>` 和事件处理器 `onerror/onclick`。

**board.js iframe URL**：加 `isValidUrl()` 校验——只允许 `http:` 和 `https:` 协议，拒绝 `javascript:`。

### 影响文件

| 文件 | 改动 | 行数 |
|------|------|------|
| `js/render.js` | `mkMsg()`: 气泡用 textContent + 安全 code 渲染 | ~15 |
| `js/render.js` | 新增 `sanitize()` 函数 | ~10 |
| `js/board.js` | `loadBoardApp()`: 加 URL 校验 | ~5 |

---

## P2-1: Process 泄漏

### 问题

`CapabilityExecutor` 中 5 个方法调 `Runtime.exec()` — Process 对象从未 `destroy()`。

### 方案

每个 `Runtime.exec()` 调用的 `finally` 块中加 `proc.destroy()`。涉及方法：

- `doSystemInfo()` — `cat /proc/meminfo`
- `doProcessList()` — `ps -ef`
- `doScreenCapture()` — `screencap`
- `doInputTap()` — `input tap`
- `doInputSwipe()` — `input swipe`

### 影响文件

`CapabilityExecutor.java` — 5 个方法，每个加 2-3 行 finally 块。

---

## P2-2: HermesActivity 拆分

### 问题

885 行，30+ 桥方法全在一个内部类。三人协作必然冲突。

### 方案

按 DESIGN_REFACTOR §1 执行——拆成 8 个子 Bridge + BridgeFactory 聚合。每个子 Bridge 独立文件，独立维护。

### 影响文件

见 `DESIGN_REFACTOR.md` §1 文件清单。**这个可以和 P0/P1 并行做——不改桥方法签名，JS 侧零感知。**

---

## P2-3: 文档死引用

### 问题

`CLAUDE.md` 引用 `DESIGN_FILE_FIXES.md`（不存在）。`README.md` 引用 `DESIGN_BOARD_V1.md`、`DESIGN_FILE_FIXES.md`、`PLAN_ROOM_V3.md`——三个文件都已删除。

### 方案

见 `DESIGN_SYNC.md` 执行清单。删死引用 + 统一版本号。

---

## 执行建议

```
码农 A (Java):            
  P0-2 新建房间模型勾选 (HermesActivity桥已有, 只做HTML+JS)
  → P1-1 ScopedStorage
  → P1-2 Widget权限
  → P2-1 Process泄漏

码农 B (JS):              
  P0-1 bridge.js补方法 (5行)
  → P0-2 模型勾选UI (和A配合)
  → P1-3 XSS修复
  → P2-3 文档死引用
```

P0-1 和 P0-2 有依赖：先做 bridge 方法 → 模型勾选才能调 `B.listModels()`。
