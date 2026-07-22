# CONTRACT: 多模型系统

版本: v1.0
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

---

## 实现约束（不可违反）

1. **Council 并行调用必须用 `CompletionService`，禁止串行。** `MAX_PARALLEL = 3`。
2. **每个模型调用超时 = 30 秒。** `Future.get(30, TimeUnit.SECONDS)`。超时不阻塞其他模型。
3. **汇总模型选择顺序：默认模型 → 第一个成功返回的模型。** 默认离线不报错，退化。
4. **API Key 必须用 `EncryptedSharedPreferences` 存储，降级时（加密不可用）弹 toast 警告、不静默降级。**
5. **`listModels()` 返回给 JS 时必须脱敏 apiKey。** 内部 `listJsonFull()` 才返回完整 Key（仅 Java 内部使用）。
6. **`discussAsync` 回调在 `aiExecutor` 线程中执行，JS 回调通过 `evalJs` 抛回主线程。** 禁止在 `doInBackground` 中直接操作 UI。
