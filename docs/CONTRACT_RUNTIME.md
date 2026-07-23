# CONTRACT: 运行页

版本: v1.0
日期: 2026-07-22
status: design-ready
交付对象: 前端程序员

---

## 验收测试用例

### TC-RT01：进程卡片显示真实数据

```
Given: APP 已运行 >1 分钟
When: 切到运行 tab
Then:
  1. pid 非 "--"
  2. 运行时长格式如 "2h 13m" (非 "--")
  3. 内存使用非 "--", 进度条 >0
  4. AI 调用次数非 "--"
  5. 最近指令显示实际执行的最后一个能力名
```

### TC-RT02：模型列表从 ModelRegistry 动态渲染

```
Given: 配了 2 个模型 (DeepSeek 默认, Claude 非默认)
When: 切到运行 tab
Then:
  1. 模型区显示 3 行: NATIVE ENGINE + DeepSeek + Claude
  2. DeepSeek 标记"默认", 在线状态 🟢
  3. 点击 DeepSeek 行: 设默认 → toast
  4. 点击 "＋ 添加模型" 行: 弹出快捷添加 sheet (选厂商 + 填 Key, 见 CONTRACT_MODEL TC-M09);
     仅 ≡ 个人信息行与默认模型行仍跳原生设置页
```

### TC-RT03：模型全部未配置 → 显示引导

```
Given: 没有配置任何模型
When: 切到运行 tab
Then:
  1. 模型区显示 NATIVE ENGINE + 空状态文案
  2. 空状态含"去设置页添加模型"引导
  3. 不崩溃
```

### TC-RT04：Cron 创建 — 自然语言解析

```
Given: 用户在 Cron 输入框
When: 输入"每天早上 8:30 汇总邮件" → 点创建
Then:
  1. Cron 表达式 = "30 8 * * *"
  2. 任务卡片出现
  3. Toast "任务已创建"
  4. 输入框清空
```

### TC-RT05：Cron 创建 — 每 N 小时

```
Given: 输入"每 3 小时检查一次"
When: 点创建
Then:
  1. Cron = "0 */3 * * *"
  2. 有 notice: "最小周期 15 分钟"
```

### TC-RT06：Cron 删除

```
Given: 已有 1 个 Cron 任务
When: 点"删除" → confirm
Then:
  1. 任务卡片消失
  2. Toast "已删除"
```

### TC-RT07：通道折叠行 — 全部正常时只显示摘要

```
Given: AI 已配, Widget 已添加, 通知权限已授权
When: 切到运行 tab
Then:
  1. 通道折叠行显示 "全部正常" (不展开)
  2. 点折叠行 → 展开显示 4 个通道详情
```

### TC-RT08：权限折叠行 — 全部授权时隐藏

```
Given: 7 个权限全部已授权
When: 切到运行 tab
Then:
  1. 权限折叠行不存在 (完全隐藏)
```

### TC-RT09：个人信息行

```
Given: 用户在运行 tab
When: 查看个人信息行
Then:
  1. 显示用户名 (当前本地用户)
  2. 点 ≡ → 跳 AI 设置页
```

---

## 实现约束（不可违反）

1. **所有数据实时查询，不进缓存。** 每次切到运行 tab 都重新调 B.runtimeStats() / B.listModels() / B.listCron() 等。
2. **模型列表从 `B.listModels()` 动态生成，不硬编码。** 新增/删除模型后运行页自动反映。
3. **Cron 创建时 `parseCronToMinutes` 返回的最小间隔是 15 分钟（WorkManager 限制）。** 如果小于 15，返回结果中必须含 `notice` 字段告知用户。
4. **权限全部授权时 `rowPerms` 必须 `display:none`。** 不在 DOM 中占位。
5. **个人信息行的 `≡` 按钮调用 `B.openSettings()` 跳转到原生设置 Activity。** 不跳 WebView 内页面。

---

## 关联合同

- [CONTRACT_ARCH.md](CONTRACT_ARCH.md)
- [CONTRACT_SECURITY.md](CONTRACT_SECURITY.md) — Cron 白名单
- [CONTRACT_MODEL.md](CONTRACT_MODEL.md) — 模型列表数据源
