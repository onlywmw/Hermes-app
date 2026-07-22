# DESIGN: AI 写文件 — 强制预览 & 审批

版本: v1.0
日期: 2026-07-22
status: design-ready

---

## 问题

AI 可以通过 `file.write` 直接写磁盘。Council 的 `nextSteps` 可以批量写文件。用户点一下"批准"，AI 生成的内容直接落盘——用户在文件写完之前完全看不到内容。

攻击场景：AI 被 prompt injection → 输出恶意文件 → 用户批准 → 落盘。虽然 `isSafe()` 防了路径遍历，但防不了内容层面的攻击（超大文件撑爆磁盘、覆盖关键文件不提示、写入非预期内容）。

**当前审批是一个"批准"按钮，用户不知道批准了什么。**

---

## 方案：写前预览卡片

AI 每次要写文件时，**不直接写**。先在聊天区插入一张**预览卡片**，展示要写的内容。用户审阅后点"保存"才真正落盘。

### 预览卡片的结构

```
┌─ AI 请求写入文件 ──────────────────────────┐
│                                             │
│ 文件: src/Login.tsx                         │
│ 大小: 1.2KB · 来源: DeepSeek · Council 讨论  │
│                                             │
│ ⚠ 此文件已存在 (当前: 2.3KB, v3)            │  ← 仅在覆盖时显示
│                                             │
│ ┌─ 预览 ──────────────────────────────────┐ │
│ │ import { useState } from 'react';       │ │
│ │                                         │ │
│ │ export function Login() {               │ │  ← 内容完整展示
│ │   const [email, setEmail] = useState(); │ │     超过 100KB 只显示前 2KB
│ │   ...                                   │ │
│ │ }                                       │ │
│ └─────────────────────────────────────────┘ │
│                                             │
│ [对比旧版本]  [保存]  [放弃]                 │
└─────────────────────────────────────────────┘
```

### 三种场景

| 场景 | 行为 |
|------|------|
| **新建文件** | 显示文件路径 + 内容预览。用户点"保存"→ 写入。点"放弃"→ 不写。 |
| **覆盖已有文件** | 额外显示"此文件已存在 (当前 vN, X KB)" + [对比旧版本] 按钮 |
| **超大内容 (>100KB)** | 预览只显示前 2KB。底部加"⚠ 内容过大, 前 2KB 预览。完整内容将写入 X KB。" |
| **二进制文件** | 不预览内容。显示文件类型 + 大小 + "⚠ 二进制文件, 无法预览内容" |

### 对比旧版本

用户点[对比旧版本] → overlay 并排显示旧版（左）和新版（右），差异行高亮（+ 绿 / - 红）。

---

## 交互流程

```
AI 说: "我写了 src/Login.tsx" (或 Council nextSteps 触发)
  ↓
不直接调 B.writeFile()
  ↓
在聊天区插入预览卡片 (mkFileWritePreview)
  ↓
用户审阅
  ├─ [保存] → B.writeFile → 工具卡片 → 文件落盘
  ├─ [放弃] → 卡片消失, 内容丢弃
  └─ [对比旧版本] → overlay 并排 diff → 关闭
```

---

## 技术实现

### Council nextSteps 执行改造

```javascript
// 旧: 直接执行
for each step: CapabilityExecutor.execute(step.action, step.args)

// 新: 先展示预览卡片, 用户确认后才执行
function executeStep(step, roomId) {
  if (step.action === 'file.write') {
    // 不写盘, 先展示预览
    push(id, mkFileWritePreview({
      path: step.target,
      content: step.detail,
      author: step.author,
      onSave: function() {
        B.writeFile(roomId, step.target, step.detail);
        push(id, toolNode('file.write', step.target, '0.3s', 'exit 0'));
      },
      onDiscard: function() { /* 卡片消失 */ }
    }));
  } else {
    // 非文件操作: 直接执行 (notification.post, tts.speak 等)
    B.cmd(step.action + ' ' + step.args);
    push(id, toolNode(...));
  }
}
```

### 消息数据模型

预览卡片作为新的消息类型 `{t:'fileWritePreview', ...}` 存入 msgData，WebView 重载后卡片可恢复（但保存/放弃按钮变为不可用，因为上下文丢失）。

```javascript
{
  t: 'fileWritePreview',
  path: 'src/Login.tsx',
  content: '...',
  size: 1280,
  author: 'DeepSeek',
  exists: true,
  oldSize: 2300,
  expired: false  // WebView重载后=true, 按钮置灰
}
```

### 文件大小限制

`CapabilityExecutor.doFileWrite()` 已有 5MB 内容限制。预览卡片**不额外限制**——用户看到什么，保存后就写入什么。5MB 是硬上限。

### 安全性

| 威胁 | 缓解 |
|------|------|
| AI 被注入写恶意内容 | 用户看到内容才点保存。不看内容点保存 = 用户自己的选择 |
| 超大文件撑爆磁盘 | 5MB 硬限制 (BridgeValidator + CapabilityExecutor 双重) |
| 覆盖关键文件 | 覆盖已有文件时提示"已存在" + 提供 diff 对比 |
| 批量写文件 | 每个文件独立一张预览卡片, 独立确认 |

---

## 不需要改的

- `CapabilityExecutor.doFileWrite` — 不改。写完后的落盘逻辑不变。
- `StorageManager.saveWorkFile` — 不改。版本快照逻辑不变。
- `BridgeValidator` — 不改。参数校验在预览卡片确认前已经跑过。
- Council 讨论流程 — 不改。`nextSteps` 提取逻辑不变，只改变执行方式。

---

## 改动清单

| 文件 | 改动 |
|------|------|
| `js/render.js` | 新增 `mkFileWritePreview()` — 渲染预览卡片 |
| `js/chat.js` | `runCouncil` 回调中 nextSteps 执行改为先预览再落盘 |
| `css/shell.css` | 新增 `.file-write-preview` 卡片样式 |
| `js/i18n.js` | 新增 key: fileWritePreview.* |

---

## 验收

- [ ] AI 在 Council 中产出 file.write 步骤 → 聊天区出现预览卡片, 未实际写盘
- [ ] 预览卡片显示文件路径、大小、来源、完整内容
- [ ] 点"保存"→ 文件落盘, 工具卡片出现
- [ ] 点"放弃"→ 卡片消失, 文件未落盘
- [ ] 覆盖已有文件时提示"已存在"
- [ ] 超大内容 (>100KB) 仅显示前 2KB 预览
