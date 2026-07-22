# DESIGN: 混合架构 — 云端编排 + 端侧执行

版本: v1.0
日期: 2026-07-22
status: design-ready

---

## 核心思路

不要把"聊天室"上云，把**AI 编排逻辑**上云。

```
┌─ 手机 (端侧, 本地主体) ──────────────────────┐
│                                                │
│  房间 / 消息 / 文件 / 设备控制 — 全在本地       │
│                                                │
│  用户发起 Council 讨论                          │
│    → 手机构造上下文 (议题 + 房间描述 + 文件列表) │
│    → 发给云端网关 (一次 HTTP POST)              │
│    → 云端并行调模型 + 汇总                      │
│    → 返回 JSON: {messages, summary, nextSteps}  │
│    → 手机端渲染卡片                             │
│    → 用户点"批准" → CapabilityExecutor 执行     │
│                                                │
└────────────────────────────────────────────────┘
          │                          ↑
          │  POST /council           │ JSON 响应
          ▼                          │
┌─ 云端 (无状态网关) ────────────────────────────┐
│                                                │
│  收到: {topic, modelIds, context, roomFiles}   │
│    → 并行调 DeepSeek / Claude / Qwen           │
│    → 默认模型汇总                              │
│    → 提取 nextSteps                            │
│  返回: {messages, summary, nextSteps}           │
│                                                │
│  不存任何状态。不存消息。不存文件。              │
│                                                │
└────────────────────────────────────────────────┘
```

---

## 得到了什么

| 维度 | 纯端侧 (现在) | 混合 (改后) |
|------|-------------|-----------|
| 并行调用延迟 | 手机等 4 次 API 往返 | 云端一次汇总，手机只等一个 HTTP 响应 |
| API Key 管理 | 分散在每个用户的手机里 | 集中在云端，一人维护 |
| 模型限流 | 无 | 云端统一限流，防滥用 |
| 本地文件/设备 | 端侧控制 | **不变**——端侧仍然是绝对主体 |
| 断网 | 不能用 | 自动降级到本地模式（端侧已有 CouncilClient） |
| 工程改动 | — | 写一个转发 API + 手机端改一行 URL |

---

## 云端网关

### 接口

```
POST https://api.mov.app/v1/council
Content-Type: application/json
Authorization: Bearer <user_token>

{
  "topic": "如何提升首页加载速度",
  "models": [
    {"id":"deepseek_v4","role":"通用"},
    {"id":"claude_opus","role":"技术"}
  ],
  "context": "房间: 产品V2.0。当前文件: src/Home.tsx, package.json。前一轮讨论: ...",
  "summaryModel": "deepseek_v4"
}
```

### 响应

```json
{
  "messages": [
    {"who":"deepseek_v4","name":"DeepSeek V4","role":"通用",
     "content":"建议优先优化图片懒加载和 bundle split。"},
    {"who":"claude_opus","name":"Claude Opus","role":"技术",
     "content":"从技术角度, CDN + SSR 可以解决大部分问题。"}
  ],
  "summary": "共识: 懒加载优先。分歧: CDN vs bundle split 的优先级。",
  "nextSteps": [
    {"action":"file.write","target":"cdn-analysis.md","detail":"CDN 方案调研"},
    {"action":"file.write","target":"bundle-split.md","detail":"Bundle split 实现方案"}
  ]
}
```

### 实现

一个无状态的 HTTP 服务。选型：Cloudflare Workers / Vercel Edge Function / Deno Deploy——免费额度足够小团队用。

核心逻辑 50 行：

```javascript
// cloud-gateway/council.js
export default {
  async fetch(request) {
    const { topic, models, context, summaryModel } = await request.json();
    
    // 并行调用
    const replies = await Promise.all(models.map(async (m) => {
      const resp = await fetch(m.provider === 'deepseek' 
        ? 'https://api.deepseek.com/v1/chat/completions'
        : 'https://api.anthropic.com/v1/messages', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${getKey(m.id)}` },
        body: JSON.stringify(buildBody(m, topic, context))
      });
      return { who: m.id, name: m.name, role: m.role, 
               content: extractContent(await resp.json()) };
    }));
    
    // 汇总
    const summary = await summarize(topic, replies, summaryModel);
    
    return Response.json({
      messages: replies,
      summary: summary.text,
      nextSteps: extractSteps(summary.text)
    });
  }
};
```

**不存任何东西。** 没有数据库。没有会话。没有文件。收到请求 → 调模型 → 返回结果 → 遗忘。

---

## 端侧改动

### CouncilClient.java

```java
// 当前: 本地并行调用
public String discuss(String topic, List<String> modelIds, String context) {
    // ExecutorService + Future + 本地 AiClient 调用...
}

// 改后: 优先走云端，失败降级本地
public String discuss(String topic, List<String> modelIds, String context) {
    String cloudResult = tryCloudGateway(topic, modelIds, context);
    if (cloudResult != null) return cloudResult;
    
    // 云端不可用 → 降级本地
    return discussLocal(topic, modelIds, context);
}
```

### tryCloudGateway

```java
private String tryCloudGateway(String topic, List<String> modelIds, String context) {
    try {
        URL url = new URL(CLOUD_GATEWAY_URL + "/v1/council");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);
        
        JSONObject body = new JSONObject();
        body.put("topic", topic);
        body.put("models", buildModelsArray(modelIds));
        body.put("context", context);
        body.put("summaryModel", registry.getDefault().id);
        
        // 发送请求...
        // 返回响应...
        
        return responseBody;
    } catch (Exception e) {
        Log.w(TAG, "Cloud gateway unreachable, fallback to local");
        return null; // 触发降级
    }
}
```

### 改动量

| 文件 | 改动 |
|------|------|
| `CouncilClient.java` | 加 `tryCloudGateway()` 方法 (~30行) + 原 discuss 改名 discussLocal |
| `HermesActivity.java` | 不加（CouncilClient 内部切换，桥方法不变） |
| JS 侧 | 零改动（返回的 JSON 格式不变） |

**手机端改 ~40 行 Java，前端零改动。** 因为云端返回的 JSON 结构和当前 `CouncilClient.discuss()` 返回的完全一致。

---

## 怎么处理 API Key

### 方案 A（推荐）：Key 存云端环境变量

```
Cloudflare Workers: 环境变量 DEEPSEEK_KEY, CLAUDE_KEY
手机端: 只发 modelId，不发 Key
```

用户不需要在手机上填 Key。云端统一管理。这是最大的体验提升——当前的"配 API Key"步骤直接消失。

### 方案 B：Key 仍在端侧，云端不存

手机端发送请求时附上 Key。云端用完即丢，不持久化。但网络传输有泄漏风险。

**建议 A。** API Key 是用户最大的摩擦点——去掉它，用户只需要知道"我有哪些模型可以用"。

---

## 降级策略

| 情况 | 行为 |
|------|------|
| 云端正常 | 走云端 |
| 云端超时 (5s 连接 / 30s 读写) | 自动降级本地 |
| 云端返回错误 | 自动降级本地 |
| 用户关掉"使用云端网关"开关 | 始终走本地 |
| 飞行模式 | 直接走本地 |

降级对用户透明——感受不到云端是否存在。顶多是"这次讨论有点慢"（因为走了本地串行）。

---

## AI 网关开源

网关代码开源 MIT。任何人都能自己部署。用户可以用 MOV 官方的网关（默认），也可以一键部署自己的网关到 Cloudflare（免费额度）。

**这意味着**：即使用户不信任 MOV 官方网关，他可以 5 分钟部署自己的实例。API Key 存在他自己的 Cloudflare 账号里。零信任问题。

---

## 需要改的文件

### 新增

| 文件 | 内容 |
|------|------|
| `cloud-gateway/` | **新仓库** — Cloudflare Workers 网关代码 (~100行 JS) |
| `cloud-gateway/wrangler.toml` | Cloudflare 部署配置 |
| `cloud-gateway/README.md` | 一键部署指南 |

### 端侧改动

| 文件 | 改动 |
|------|------|
| `CouncilClient.java` | 加 `tryCloudGateway()` + 降级逻辑 (~40行) |
| `HermesSettingsActivity.java` | 加"使用云端网关"开关 |
| `js/runtime.js` | 模型区如果走云端, 显示"☁ 云端编排"标识 |
| `js/i18n.js` | 加 3 个 key |

---

## 未解决问题

1. 云端网关的用户认证——Bearer token 怎么生成和分发？
2. 多用户场景下，云端怎么区分不同用户的模型配额？
3. 开源网关的自部署指南需要写多详细——目标用户是开发者，可以用 wrangler CLI
4. 当前免费方案（Cloudflare Workers 免费 10 万次/天）够不够？
