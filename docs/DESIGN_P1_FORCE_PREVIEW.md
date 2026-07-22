# DESIGN: P1 — 强制预览, 删除"自动执行"

版本: v2.0
日期: 2026-07-22
status: design-ready

---

## 验收测试用例

### TC-P1-01：Council 产出文件 → 预览卡片 → 用户保存

```
Given: Council 讨论完成, nextSteps 包含 [{action:"file.write", target:"cdn-analysis.md", detail:"..."}]
When: 聊天区出现预览卡片, 用户点"保存"
Then:
  1. 文件写入磁盘, 路径 = room_id/files/work/cdn-analysis.md
  2. 文件内容 = 预览卡片展示的完整内容（字节比对）
  3. 卡片上"保存"按钮变为"已保存 ✓"并禁用（防重复提交）
  4. Toast "已保存"
  5. 退出房间再进入, 预览卡片不出现（已转为工具卡片或消失）
  6. 文件 tab 能看到该文件
```

### TC-P1-02：用户放弃

```
Given: 预览卡片已展示
When: 用户点"放弃"
Then:
  1. 文件不写入磁盘
  2. 卡片从 DOM 移除
  3. 对应的 msgData 记录标记 deleted=1
  4. 退出房间再进入, 卡片不出现
```

### TC-P1-03：退出房间再回来，卡片仍可用

```
Given: 预览卡片已展示, 用户未操作
When: 用户退出房间 → 进入另一个房间 → 返回原房间
Then:
  1. 预览卡片仍在聊天区
  2. "保存"和"放弃"按钮仍可点击
  3. 卡片内容未变
```

### TC-P1-04：WebView 被销毁后卡片不再可用

```
Given: 预览卡片已展示, 用户未操作
When: APP 被系统杀死 → 重新打开 → 进入该房间
Then:
  1. 预览卡片仍在（msgData 持久化了）
  2. "保存"和"放弃"按钮**置灰不可点击**（上下文丢失, 无法确认操作来源）
  3. 卡片底部显示"⚠ 操作已过期, 如需写入请重新触发 AI 生成"
```

### TC-P1-05：覆盖已有文件时提示

```
Given: 预览卡片 target = "src/Login.tsx", 该文件已存在 (v2, 2.3KB)
When: 预览卡片展示
Then:
  1. 卡片顶部显示 "⚠ 此文件已存在 (当前 v2, 2.3KB)"
  2. 出现 [对比旧版本] 按钮
  3. 点 [对比旧版本] → overlay 并排显示旧版(左)和新版(右), 差异行高亮 (+绿/-红)
```

### TC-P1-06：超大内容 (>100KB)

```
Given: AI 生成的文件内容 = 120KB
When: 预览卡片展示
Then:
  1. 卡片预览区只显示前 2KB
  2. 预览区底部显示 "⚠ 内容过大 (120KB), 仅展示前 2KB。保存后完整内容写入磁盘。"
  3. 用户仍可点"保存"（完整 120KB 写入）
```

### TC-P1-07：Cron 配置了 file.write → 不自动执行

```
Given: Cron 任务配置了 action="file.write"
When: Cron 触发
Then:
  1. 文件**不**写入磁盘
  2. 聊天区推送一条系统消息: "⏰ Cron「xxx」想写入文件: yyy.md。请确认。"
  3. 消息附带预览卡片
  4. 用户下次打开 APP 进入该房间时看到这条消息
```

### TC-P1-08：Cron 白名单外的 action 被拦截

```
Given: Cron 任务配置了 action="input.tap"
When: Cron 触发
Then:
  1. 不执行
  2. 日志: "BLOCKED: Cron 不允许执行 input.tap (白名单外)"
  3. 任务状态: FAIL
  4. 不推送任何消息（不打扰用户）
```

---

## 实现约束（不可违反）

1. **"自动执行"模式必须从代码中删除。** 不是隐藏开关——是删掉相关分支。全局只有一种模式：手动审批。
2. **预览卡片在 msgData 中的 type 必须是 "fileWritePreview"。** 重渲染时根据 type 恢复卡片 DOM 结构。
3. **卡片过期判断：** WebView session 内 `expired=false`。APP 重启后所有 fileWritePreview 卡片 `expired=true`。
4. **Cron 白名单只允许查询类操作。** 具体名单：`help, torch.on, torch.off, battery.status, system.info, brightness.get, brightness.set, volume.get, volume.set, wifi.status, vibrate, clipboard.get, network.info, process.list, file.ls`。**禁止** `file.write, file.read, file.delete, file.mkdir, http.get, notification.post, tts.speak, clipboard.set, toast, input.tap, input.swipe, screen.capture, sms.recent, contacts.list, telephony.call, app.list, app.launch, location.get, camera.photo`。
5. **保存按钮必须防重复提交。** 点击后立即 `disabled=true` + 文 字变"保存中..."。写入完成后变"已保存 ✓"。写入失败恢复可用。

---

## 预览卡片生命周期

```
创建: AI 产出 file.write 步骤 / Cron 触发 file.write / 用户手动触发 file.write
活跃: 用户未操作, "保存"和"放弃"按钮可点击, expired=false
终结条件（任一满足即销毁或归档）:
  a. 用户点"保存" → 文件落盘 → 卡片转为工具卡片（file.write 执行结果）
  b. 用户点"放弃" → DOM 移除 + msgData 标记 deleted=1
  c. APP 被杀 → 卡片保留但 expired=true → 重启后按钮置灰
```

---

## 改动清单

| 文件 | 改动 | 行数 |
|------|------|------|
| `js/chat.js` | Council nextSteps 执行改为先插预览卡片 | ~20 |
| `js/render.js` | 新增 `mkFileWritePreview()` | ~40 |
| `js/render.js` | rebuildMsgs 新增 fileWritePreview 类型恢复 | ~10 |
| `HermesCronWorker.java` | ALLOWED_ACTIONS 替换为新白名单 | 改一行 |
| `DESIGN_MULTI_MODEL.md` | 删除"自动执行"描述 | — |
