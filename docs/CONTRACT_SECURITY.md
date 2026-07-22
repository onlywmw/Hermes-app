# CONTRACT: 安全

版本: v1.0
日期: 2026-07-22
status: design-ready
交付对象: 后端 + 前端程序员

---

## 验收测试用例

### TC-SEC01：API Key 加密存储

```
Given: 用户添加模型, 填写 apiKey="sk-1234567890abcdef"
When: ModelRegistry.addModel(...)
Then:
  1. SharedPreferences 中存储的值 ≠ "sk-1234567890abcdef" (已加密)
  2. listModels() 返回的 apiKey = "sk-1****cdef" (脱敏)
  3. listModelsFull() 返回完整 Key (仅 Java 内部)
```

### TC-SEC02：加密不可用时拒绝保存

```
Given: Android Keystore 不可用
When: ModelRegistry 初始化
Then:
  1. 不降级到明文 SharedPreferences
  2. isEncrypted() 返回 false
  3. JS 侧 toast "⚠ 加密存储不可用, API Key 无法保存"
  4. 用户仍可添加模型, 但 apiKey 字段不持久化 (重启后丢失)
```

### TC-SEC03：路径遍历被拦截

```
Given: path = "../../etc/hosts"
When: BridgeValidator.checkPath(path)
Then:
  1. 返回错误 JSON: {"ok":false,"error":"路径越界: ../../etc/hosts"}
  2. 文件操作不执行
```

### TC-SEC04：超大内容被拦截

```
Given: content.length = 6MB
When: BridgeValidator.checkContent(content)
Then:
  1. 返回错误 JSON: {"ok":false,"error":"内容过大 (>5MB)"}
```

### TC-SEC05：Cron 执行白名单外的 action

```
Given: Cron 任务配置 action="input.tap"
When: Cron 触发
Then:
  1. 不执行
  2. 日志: "BLOCKED: Cron 不允许执行 input.tap (白名单外)"
  3. 任务状态: FAIL
```

### TC-SEC06：Cron 执行白名单内的 action

```
Given: Cron 任务配置 action="battery.status"
When: Cron 触发
Then:
  1. 正常执行
  2. 任务状态: OK
```

### TC-SEC07：Widget receiver 权限保护

```
Given: 第三方应用发送 ACTION_EXECUTE Intent
When: HermesWidgetProvider.onReceive
Then:
  1. 权限检查失败 (缺少 EXECUTE_WIDGET 权限)
  2. 指令不执行
```

### TC-SEC08：消息渲染不执行脚本

```
Given: 消息内容 = "<img src=x onerror=alert(1)>"
When: mkMsg 渲染该消息
Then:
  1. 内容显示为纯文本 "<img src=x onerror=alert(1)>"
  2. 图片不加载, alert 不执行
```

---

## 实现约束（不可违反）

1. **所有桥方法的路径参数必须通过 `BridgeValidator.checkPath()` 校验。** 禁止直接在桥方法中拼接路径。
2. **所有桥方法的内容参数必须通过 `BridgeValidator.checkContent()` 校验。** 5MB 硬上限。
3. **Cron 白名单硬编码在 `HermesCronWorker.ALLOWED_ACTIONS`。** 只允许查询类操作。修改白名单 = 改这个常量 + 更新本 CONTRACT 的 TC-SEC05/06。
4. **Widget receiver 必须声明 `android:permission`。** 且 `executeCommand()` 必须过白名单检查。
5. **`render.js` 的 `mkMsg()` 必须用 `textContent` 设置用户输入文本。** 禁止 `innerHTML` 拼接用户输入。需要保留 `<code>` 标签时用 `createElement('code')` + `textContent`，不可拼接字符串。
