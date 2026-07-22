# CONTRACT: 多模型系统

版本: v1.2 (新增运行页快捷添加/长按管理 TC-M09/M10)
日期: 2026-07-22
status: design-ready
交付对象: 后端 + 前端程序员

---

## 验收测试用例

### TC-M01：添加模型

```
Given: ModelRegistry 为空
When: addModel({name:"DeepSeek V4", provider:"deepseek", apiKey:"sk-xxx", model:"deepseek-v4-flash"})
Then:
  1. 返回 {ok:true, id:"xxx"}
  2. listModels() 包含该模型
  3. apiKey 加密存储（EncryptedSharedPreferences），listModels 返回时脱敏 (sk-****)
```

### TC-M02：删除最后一个模型被拒绝

```
Given: 只有 1 个模型
When: deleteModel(id)
Then:
  1. 返回 {ok:false, error:"至少保留一个模型"}
  2. 模型仍在列表中
```

### TC-M03：设置默认模型

```
Given: 模型 A (isDefault=true), 模型 B (isDefault=false)
When: setDefaultModel(B.id)
Then:
  1. A.isDefault = false
  2. B.isDefault = true
  3. 全局默认模型 = B
```

### TC-M04：Council 并行调用 — 所有模型成功

```
Given: 3 个模型均已配置, topic="提升首页性能"
When: discussAsync(topic, [A,B,C], context, callback)
Then:
  1. callback.onReply 被调用 4 次（3 个模型各一次 + 1 次汇总）
  2. 第一次回调延迟 ≤ 最慢模型的响应时间
  3. 每个回调的 type: "model" 含 who/name/role/content
  4. 汇总回调的 type: "summary" 含 summary/nextSteps
  5. 汇总使用默认模型执行
```

### TC-M05：一个模型超时，其他正常

```
Given: 模型 A 网络不通, B 和 C 正常
When: discussAsync(topic, [A,B,C], context, callback)
Then:
  1. B 和 C 正常回调 (type:"model")
  2. A 超时后回调 (type:"model", success:false, content:"调用超时")
  3. 汇总仍执行 (基于 B 和 C 的回复)
  4. 总等待时间 = TIMEOUT_SECONDS (30s), 不无限阻塞
```

### TC-M06：默认模型离线 → 汇总用第一个成功的模型

```
Given: 默认模型 A 离线, B 和 C 在线
When: Council 讨论完成, 需要汇总
Then:
  1. 汇总退化到 B (第一个成功返回的在线模型)
  2. 不报错, 正常返回 summary
```

### TC-M07：流式渲染 — 先到先显

```
Given: 3 个模型, B 最快 (3s), A 中等 (5s), C 最慢 (8s)
When: discussAsync
Then:
  1. JS 侧第一个收到 B 的回复 (3s 后)
  2. 第二个收到 A 的回复 (5s 后)
  3. 第三个收到 C 的回复 (8s 后)
  4. 最后收到汇总
```

### TC-M08：添加模型只填 Key

```
Given: 设置页"添加模型"表单, ModelPresets 含 13 个厂商预设
When: 用户在厂商 Spinner 中选择 "智谱 GLM"
Then:
  1. etBaseUrl 自动填充 "https://open.bigmodel.cn/api/paas/v4" (无 /v1 后缀)
  2. etModel 自动填充默认模型 "glm-4.7-flash" (用户可改, 推荐模型下拉可选 glm-5.2)
  3. 厂商备注 "注意无 /v1 后缀；glm-4.7-flash 免费" 显示在表单中
When: 用户点击"获取 API Key"按钮
Then:
  4. 浏览器打开 "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"
When: 用户选择 "Ollama"
Then:
  5. "获取 API Key"按钮隐藏或置灰, 提示"本地模型无需 Key"
When: 用户只填 apiKey 后保存 (baseUrl/model 留空)
Then:
  6. 后端 getEffectiveBaseUrl/getEffectiveModel 走 ModelPresets 默认值, 调用成功
```

### TC-M09：运行页快捷添加模型 (选厂商 + 填 Key 即接入)

```
Given: 运行页模型区, "＋ 添加模型" 行
When: 点击该行
Then:
  1. 弹出底部 sheet (不跳原生设置页), 含 13 厂商单选列表 (彩色头像+显示名+备注), 默认选中 DeepSeek
  2. 切换厂商 → 高级区 baseUrl/模型名按 ModelPresets 自动填充 (可改)
  3. "获取 API Key →" 点击 → BridgeDevice.openUrl 打开该厂商 keyConsoleUrl (仅 http/https)
  4. 选 Ollama → Key 框隐藏, 提示"本地模型无需 Key"
  5. 非 Ollama 且 Key 为空 → 保存按钮置灰; 输入 Key → 可保存
  6. "测试连接" → B.testModel(json, cb) 异步回调 (aiExecutor 线程), toast 显示 连接正常·latencyMs / 连接失败·error
  7. 保存 → B.addModel → toast + 关 sheet + 模型出现在列表
When: 长按任一已注册模型行 → 管理菜单选"编辑"
Then:
  8. 同一 sheet 以编辑模式打开, 标题"编辑模型", 字段预填; Key 框留空并提示 "已保存 sk-1****abcd · 留空则保持不变"
  9. Key 留空保存 → BridgeModel.updateModel 保留原 Key (listModels 已脱敏, 前端无法回填, 后端特判空 Key=不覆盖)
```

### TC-M10：模型行长按管理菜单

```
Given: 运行页至少 1 个已注册模型
When: 长按模型行 (非 __native/__add)
Then:
  1. 直接弹管理 sheet (空 text 长按, 不弹确认条), 含: 设为默认 / 编辑 / 删除
  2. 已是默认的模型 → "设为默认"项隐藏
  3. 长按后 300ms 内 click 被 lpSuppressClick 抑制, 不触发"设默认/跳设置"
When: 管理菜单选"设为默认"
Then:
  4. B.setDefaultModel → toast + 关 sheet + 列表刷新
When: 管理菜单选"删除"
Then:
  5. sheet 内二次确认, 文案带模型名 "删除模型 {name}？此操作不可撤销。"
  6. 确认 → B.deleteModel → toast + 列表消失该行
When: 仅剩 1 个模型时删除
Then:
  7. 后端拒绝, toast "至少保留一个模型", 模型仍在
```

---

## 厂商预设表 (ModelPresets)

13 家 OpenAI 兼容厂商的单一数据源: `model/ModelPresets.java`。ModelConfig 的空值默认、设置页的自动填充、JS 侧的厂商列表 (`getProviderPresets()`) 都从它取，禁止各自硬编码。

| key | 显示名 | baseUrl | 默认模型 | 获取 Key 页面 | 备注 |
|---|---|---|---|---|---|
| deepseek | DeepSeek | https://api.deepseek.com/v1 | deepseek-v4-flash | platform.deepseek.com/api_keys | 需先充值 |
| moonshot | Kimi | https://api.moonshot.cn/v1 | kimi-k2.6 | platform.moonshot.cn/console/api-keys | |
| zhipu | 智谱 GLM | https://open.bigmodel.cn/api/paas/v4 | glm-4.7-flash | bigmodel.cn/usercenter/proj-mgmt/apikeys | 无 /v1 后缀; flash 免费 |
| qwen | 通义千问 | https://dashscope.aliyuncs.com/compatible-mode/v1 | qwen3.7-plus | bailian.console.aliyun.com | 需先开通百炼 |
| doubao | 豆包 | https://ark.cn-beijing.volces.com/api/v3 | doubao-seed-2-0-mini-260428 | console.volcengine.com/ark | 需实名+开通模型服务 |
| spark | 讯飞星火 | https://spark-api-open.xf-yun.com/v1 | generalv3.5 | console.xfyun.cn/services/bm35 | 填 APIPassword; lite 免费 |
| minimax | MiniMax | https://api.minimaxi.com/v1 | MiniMax-M2.5 | platform.minimaxi.com | 模型 ID 大小写敏感 |
| baichuan | 百川智能 | https://api.baichuan-ai.com/v1 | Baichuan4-Turbo | platform.baichuan-ai.com/console/apikey | 需实名 |
| stepfun | 阶跃星辰 | https://api.stepfun.com/v1 | step-3.5-flash | platform.stepfun.com/interface-key | |
| hunyuan | 腾讯混元 | https://tokenhub.tencentmaas.com/v1 | hy3 | console.cloud.tencent.com/tokenhub | 新平台 TokenHub |
| yi | 零一万物 | https://api.lingyiwanwu.com/v1 | yi-lightning | platform.lingyiwanwu.com/apikeys | 维护停滞 |
| openai | OpenAI | https://api.openai.com/v1 | gpt-4o-mini | platform.openai.com/api-keys | |
| ollama | Ollama | http://192.168.1.100:11434/v1 | llama3 | （无） | 本地模型无需 Key |

约定:
- 每个预设的 `models` 数组第一个 = `defaultModel`，第二个 = 旗舰模型。
- 未知 provider key 的 baseUrl/model 兜底走 deepseek 默认，displayName 原样返回 key。
- `ollama.keyConsoleUrl = ""`，UI 需特殊处理（隐藏/置灰"获取 API Key"按钮）。
- 存量 provider key (deepseek/openai/qwen/ollama) 的默认行为不变。

---

## 实现约束（不可违反）

1. **Council 并行调用必须用 `CompletionService`，禁止串行。** `MAX_PARALLEL = 3`。
2. **每个模型调用超时 = 30 秒。** `Future.get(30, TimeUnit.SECONDS)`。超时不阻塞其他模型。
3. **汇总模型选择顺序：默认模型 → 第一个成功返回的模型。** 默认离线不报错，退化。
4. **API Key 必须用 `EncryptedSharedPreferences` 存储，降级时（加密不可用）弹 toast 警告、不静默降级。**
5. **`listModels()` 返回给 JS 时必须脱敏 apiKey。** 内部 `listJsonFull()` 才返回完整 Key（仅 Java 内部使用）。
6. **`discussAsync` 回调在 `aiExecutor` 线程中执行，JS 回调通过 `evalJs` 抛回主线程。** 禁止在 `doInBackground` 中直接操作 UI。
