# DESIGN: 多模型协作引擎 — MOV 核心闭环

版本: v1.0
日期: 2026-07-22
status: design-ready
依赖: DESIGN_SECURITY.md, DESIGN_REFACTOR.md

---

## 你做的不是聊天工具。是多模型工作台。

一个房间 = 一个项目。房间里有人（你+同事）和 AI（你配的多个大模型）。
你提议题 → 每个 AI 从自己的角色视角给观点 → 汇总成方案 → agent 执行 → 产出文件。

这才是 MOV 的核心闭环。之前的聊天、文件、看板——都是这个闭环的容器。

---

## 第 1 层: 多模型注册 (ModelRegistry)

### 当前

`AiProviderConfig` 是全局单例——只能配一个模型。所有 AI 调用共用一个 Key。

### 设计

```java
// com.hermes.android.model.ModelRegistry.java
public class ModelRegistry {
    
    // 每个模型一个配置
    public static class ModelConfig {
        public String id;          // "deepseek_v4"
        public String name;        // "DeepSeek V4"
        public String provider;    // "deepseek" | "openai" | "qwen" | "ollama"
        public String baseUrl;     // 空走默认
        public String apiKey;
        public String model;       // "deepseek-v4-flash"
        public String systemPrompt; // 可覆盖
        public String role;        // "通用" | "产品" | "技术" | "数据" | "自定义"
        public String color;       // 头像色 "#D97706"
        public boolean enabled;
        public boolean isDefault;  // 单聊模式用的默认模型
    }
    
    // 存储: SharedPreferences "mov_models" → JSONArray
    // CRUD: add / update / delete / list / setDefault / testConnection
    // 至少保留一个模型 (系统最低要求)
}
```

### 桥方法

```java
// HermesActivity → BridgeModel
@JavascriptInterface
public String listModels()       → JSONArray
public String addModel(json)     → {ok, id}
public String updateModel(json)  → {ok}
public String deleteModel(id)    → {ok}  // 至少保留一个
public String testModel(json)    → {ok, latencyMs} | {ok:false, error}
public String setDefaultModel(id)→ {ok}
```

### 设置页改造

```
现在: 一个页面配一个模型
改后:
  模型列表
  ┌── DeepSeek V4 ────────────── [默认] ──┐
  │  deepseek-v4-flash · ✅ 连接正常        │
  │  角色: 通用                             │
  │  [编辑] [测试] [删除]                   │
  └─────────────────────────────────────────┘
  ┌── Claude Opus ─────────────────────────┐
  │  claude-opus-4 · ✅ 连接正常            │
  │  角色: 技术                             │
  │  [编辑] [测试] [删除]                   │
  └─────────────────────────────────────────┘
  ┌── Qwen Max ────────────────────────────┐
  │  🔴 未配置 Key                          │
  │  [编辑]                                 │
  └─────────────────────────────────────────┘

  [＋ 添加模型]
```

### 运行页模型区

```
运行 tab → 模型区:
  DeepSeek V4   🟢 今天 42 次 · 3.2s   [默认]
  Claude Opus   🟢 今天 18 次 · 5.1s
  Qwen Max      🔴 未配置
  Ollama (本地) ⚫ 离线
```

### 影响文件

| 文件 | 改动 |
|------|------|
| `model/ModelRegistry.java` | **新建** ~180行 |
| `model/ModelConfig.java` | **新建** ~30行 |
| `bridge/BridgeModel.java` | **新建** ~50行 |
| `HermesActivity.java` | 注册 BridgeModel |
| `HermesSettingsActivity.java` | 重写：模型列表 + 编辑/添加 Sheet |
| `activity_hermes_settings.xml` | 重写布局 |
| `js/bridge.js` | B.listModels / addModel / deleteModel / testModel / setDefaultModel |
| `js/runtime.js` | 模型区改为遍历 B.listModels 渲染卡片 |
| `js/i18n.js` | model.* 新 key |

**码农分工**: 程序员 A — ModelRegistry + BridgeModel + 设置页（Java 层）。程序员 B — bridge.js + runtime.js + i18n（JS 层）。

---

## 第 2 层: 多模型真实讨论

### 当前

Council 是假的。`council.js` 硬编码剧本 + sleep()。`CouncilClient.java` 能真调 AI 但是串行的。房间创建时写死 `['claude','gpt-5','gemini']`——这三个名字和用户实际配的模型无关。

### 设计

#### 2.1 房间创建：选模型成员

新建房间第二步（"拉 AI 团队"）→ 勾选列表从 ModelRegistry 动态生成：

```
┌─────────────────────────────────┐
│ 选择参与的 AI                   │
│                                 │
│ ☑ DeepSeek V4  (默认, 必选)    │
│ ☑ Claude Opus                   │
│ ☐ Qwen Max      (未配置)        │
│ ☐ Ollama (本地)  (离线)         │
│                                 │
│ 至少选 2 个模型                 │
└─────────────────────────────────┘
```

房间数据模型 `members` 从硬编码字符串改为模型 ID 引用：

```javascript
// 旧
members: ['claude', 'gpt-5', 'gemini']

// 新
members: {
  human: [{who: 'you', role: 'owner'}],
  ai: ['deepseek_v4', 'claude_opus']   // ModelRegistry ID
}
```

#### 2.2 讨论流程

```
用户发议题 "如何提升首页加载速度"
  ↓
Hermes 构造上下文:
  - 房间描述
  - 已有的文件列表
  - 前几轮讨论摘要 (如果有)
  ↓
并行调用所有参与模型 (最多 3 个, 用线程池)
  每个模型收到相同的上下文 + 自己的角色 prompt:
  
  DeepSeek (通用): "你是 DeepSeek, 给出你的观点。简洁, 3 句话以内。"
  Claude (技术): "你是 Claude, 从技术架构角度回答。简洁, 3 句话以内。"
  ↓
各模型回复收齐 → 聊天区按序展示:
  [DS] DeepSeek · 通用: "建议先优化图片懒加载和 bundle split"
  [CL] Claude · 技术: "从技术角度, CDN + SSR 可以解决大部分问题"
  ↓
Hermes 汇总 → 推送到聊天区:
  ── HERMES 汇总 ──
  共识: 懒加载优先。分歧: CDN vs bundle split 的优先级。
  ↓
用户追问 "那 CDN 怎么配?" → 新一轮
```

#### 2.3 CouncilClient 改造

```java
// 并行调用
public CouncilResult discuss(String topic, List<String> modelIds) {
    ExecutorService exec = Executors.newFixedThreadPool(3);
    List<Future<ModelReply>> futures = new ArrayList<>();
    
    for (String id : modelIds) {
        ModelConfig model = registry.get(id);
        futures.add(exec.submit(() -> {
            AiClient client = new AiClient(model);
            AiResponse resp = client.chat(topic, emptyHistory);
            return new ModelReply(model.id, model.name, model.role, 
                                   resp.success ? resp.content : "调用失败");
        }));
    }
    
    // 等所有返回
    CouncilResult result = new CouncilResult();
    for (Future<ModelReply> f : futures) {
        result.replies.add(f.get(30, TimeUnit.SECONDS));
    }
    
    // 汇总 (用默认模型)
    String summary = summarize(topic, result.replies);
    result.summary = summary;
    
    exec.shutdown();
    return result;
}
```

#### 2.4 替换 fit 硬编码剧本

`enterRoom` 中 fit 房间 → 保留种子消息（让用户理解 Council 是什么）→ 但不再用 `sleep()` 假数据。用户发第一条消息就触发真实讨论。

#### 2.5 讨论→结构化输出

每轮讨论结束时，默认模型（或用户指定的模型）做汇总，输出结构：

```json
{
  "summary": "共识: 懒加载优先。分歧: CDN vs bundle split。",
  "votes": [
    {"model": "DeepSeek", "opinion": "懒加载 + bundle split"},
    {"model": "Claude", "opinion": "CDN + SSR"}
  ],
  "nextSteps": [
    "DeepSeek 调研 CDN 方案",
    "Claude 写 bundle split 实现方案"
  ]
}
```

这个 JSON 直接驱动第 3 层执行。

### 影响文件

| 文件 | 改动 |
|------|------|
| `CouncilClient.java` | 重写：接收 modelIds → 并行调用 → 汇总 → 结构化输出 |
| `bridge/BridgeAi.java` | councilAsync 改为接收 modelIds |
| `js/chat.js` | enterRoom 不再调 runFitCouncil；sendMsg 改 council 入口 |
| `js/council.js` | 删除硬编码剧本（或保留为 demo 但不再触发） |
| `js/app.js` | 新建房间第二步：ModelRegistry 动态勾选列表 |
| `js/store.js` | ROOM.members 改数据结构 |
| `hermes-shell.html` | 新建房间第二步改动态勾选 |

**码农分工**: 程序员 A — CouncilClient 重写 + BridgeAi 改造（Java 层）。程序员 B — 聊天流集成 + 新建房间动态勾选（JS 层）。

---

## 第 3 层: 方案→agent 执行

### 设计

第 2 层的汇总输出 `nextSteps` 驱动执行：

```
Hermes 汇总 → 提取步骤:
  1. "DeepSeek 调研 CDN 方案"      → agent 动作: file.write (写调研文档)
  2. "Claude 写 bundle split 方案" → agent 动作: file.write (写实现方案)
  ↓
Hermes 逐条执行:
  ┌── 执行步骤 1 ──────────────────────┐
  │ ▸ file.write                        │
  │ 参数: cdnsolutions.md              │
  │ 内容: DeepSeek 生成的调研文档        │
  │ 1.2s · ✅ exit 0                    │
  └─────────────────────────────────────┘
  
  ┌── 执行步骤 2 ──────────────────────┐
  │ ▸ file.write                        │
  │ 参数: bundle-split.md              │
  │ 0.8s · ✅ exit 0                    │
  └─────────────────────────────────────┘

全部完成 → Hermes 通知:
  "方案已执行完毕。产出: cdnsolutions.md, bundle-split.md。
   在文件 tab 可以查看。[不满意, 修改重跑]"
```

### 执行流程

```
用户点"批准并执行" (或 Hermes 自动)
  ↓
steps = JSON.parse(councilResult.nextSteps)
  ↓
for each step:
  result = CapabilityExecutor.execute(step.action, step.args)
  push 工具卡片到聊天区 (和现有 runDeviceCommand 复用 toolNode)
  ↓
全部完成 → push 交付物卡片 + sysline
```

### 审批可选

用户可以在房间设置里选：
- **自动执行**：讨论完就执行，不需要批准
- **手动审批**：讨论完展示方案 → 用户点"批准" → 才执行

默认：手动审批。

### Agent 能力扩展

现有 CapabilityExecutor 已有 34 个能力。执行步骤可以调用任意一个：

```
{action: "file.write",    args: {path, content}}
{action: "notification.post", args: {content}}
{action: "tts.speak",    args: {text}}
{action: "file.ls",      args: {path}}
{action: "http.get",     args: {url}}         ← 需要新增
```

### 影响文件

| 文件 | 改动 |
|------|------|
| `CouncilClient.java` | 已有 `nextSteps` 输出（第 2 层已做） |
| `CapabilityExecutor.java` | 新增 `http.get` 能力（如果步骤需要） |
| `js/chat.js` | runCouncil 回调中：收到 summary → 推送到聊天 → 自动/手动执行 steps |
| `js/render.js` | `toolNode` 复用 |

**码农分工**: 程序员 A — `http.get` 新能力 + 执行管线（Java）。程序员 B — 审批/执行 UI + 交付物渲染（JS）。

---

## Hermes 定义

Hermes 是**一个函数，不是 agent。** 它是 CouncilClient 中的汇总逻辑 + 步骤提取逻辑。

- 输入：所有模型回复 + 默认模型
- 输出：summary（文本摘要）+ nextSteps（JSON 步骤数组）
- 不单独调用 LLM——用默认模型做汇总，本身不自带独立的 system prompt
- 在 UI 和文档中，Hermes 不作为独立 AI 角色出现。不叫"主持"，不叫"执行"，不拟人化
- 步骤执行由 CapabilityExecutor 完成，Hermes 不参与执行

---

## 实施估算

以下为粗略估算（×3 后）。实际排期由团队根据 velocity 确定。

| 层 | 内容 | 保守估算 |
|----|------|---------|
| 第 1 层 | ModelRegistry + 设置页改造 + JS 模型区 | 3 天 |
| 第 2 层 | CouncilClient 重写 + 聊天流集成 + 模型选择 UI | 3 天 |
| 第 3 层 | http.get + 执行管线 + 审批/交付 UI | 2 天 |
| **总计** | | **~8 天，两人并行 ~5 天** |

---

## 未解决问题

1. CouncilClient 当前已重写为真实并行调用，但 `council.js` 的硬编码 `runFitCouncil()` 尚未从 fit 房间移除
2. 多轮追问（用户追问后所有模型再各回一轮）的上下文构造策略未定义——是传全文还是传摘要？
3. 汇总模型的选取逻辑：永远用默认模型？还是允许用户指定？如果默认模型离线怎么办？
4. 步骤执行失败（如 file.write 报错）的回滚策略——跳过继续还是全部停止？

---

## 验收标准

### 第 1 层

- [ ] 设置页可添加/编辑/删除多个模型
- [ ] 每个模型独立测试连接
- [ ] 至少保留一个模型（删除最后一项被拒绝）
- [ ] 运行页模型区显示所有模型状态

### 第 2 层

- [ ] 新建房间时可从已注册模型中勾选参与者
- [ ] 发送议题 → 所有选中模型并行回复
- [ ] 回复带各自头像/名字/角色标签
- [ ] 讨论结束后显示 Hermes 汇总
- [ ] 支持多轮追问

### 第 3 层

- [ ] 汇总输出结构化 JSON（步骤列表）
- [ ] Hermes 按步骤逐条执行
- [ ] 执行结果作为工具卡片显示在聊天区
- [ ] 全部完成后显示交付物卡片
- [ ] 手动审批模式下，方案需要用户点"批准"才执行
# SKILL: 技能定义

版本: v1.0
日期: 2026-07-22
status: draft

---

## 定义

技能 = 一个**可复用的 prompt 模板 + 触发词**。用户说触发词 → MOV 自动填入 prompt 模板 → 发给指定 AI 模型 → 返回结果。

不是快捷指令（那走 IntentParser）。不是 Cron（那走定时调度）。不是模板（那是文档格式）。

---

## 数据结构

```json
{
  "id": "proxy-test",
  "name": "代理节点连通性测试",
  "desc": "用本地内核测试代理节点真实连通性",
  "activation": "click",              // 当前: click (点击卡片)。future: trigger_word (聊天输入触发词)
  "trigger": "/test-proxy",           // future: 当 activation=trigger_word 时生效
  "prompt": "请帮我测试以下代理节点...", // 发给 AI 的 prompt 模板
  "model": "deepseek_v4",            // 用哪个模型
  "source": "auto",                  // "auto"(自动生成) | "manual"(用户创建)
  "status": "stable",                // "stable" | "revising"
  "revisions": 0,
  "uses": 0,
  "lastUsed": 0
}
```

---

## 生命周期

```
当前：点击技能卡片 → SkillStore.recordUse → toast。vNext：用户说触发词 → IntentParser 匹配 → 路由到 SkillStore → 取 prompt 模板 → 发给指定 AI → 返回结果。如果同时命中设备指令，设备指令优先。
```

---

## 与相邻概念的边界

| 概念 | 是什么 | 边界 |
|------|--------|------|
| **指令** | IntentParser 关键词匹配，走 CapabilityExecutor | 不调用 AI，纯本地执行 |
| **技能** | 触发词 → prompt 模板 → AI 调用 | 调用 AI，不执行本地设备操作 |
| **Cron** | 定时触发，可触发指令或技能 | 是调度层，不是执行层 |
| **模板** | 文档格式，被人复制使用 | 不自动执行，不调用 AI |

---

## 未解决问题

1. 当前 SkillStore 只有 3 个种子技能（硬编码），没有创建/编辑 UI
2. "自动生成"技能的触发条件未定义——什么情况下 MOV 会自动沉淀技能？
3. 技能和 Council 的关系——技能可以用在议会讨论中吗？如果可以，多个模型怎么分配？
4. 技能需要独立 tab 还是合并到运行页——当前代码两者都存在，需收敛
