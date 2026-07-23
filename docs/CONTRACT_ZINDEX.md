# CONTRACT: 层级系统

版本: v2.0
日期: 2026-07-22
status: design-ready
交付对象: 前端程序员

---

## 当前状态

程序员已经修了主问题：`.sheet-mask{z-index:29}` `.sheet{z-index:30}` 已高于 `.bnav{z-index:15}`。底部 sheet 不再被导航栏遮挡。

## 剩余问题

1. **`#sheetMask{z-index:11}` 和 `#sheetNew{z-index:12}` 是历史残留的 ID 选择器。** 这些 ID 已不存在于 HTML（新建房间现在用 `#newRoomMask` + `#newRoomSheet`），但 CSS 里还留着。应删除。
2. **`.dialog-mask{z-index:99}` 是死代码。** 新建房间已改回底部 sheet，不再用居中 dialog。应删除。
3. **`.preview-mask(29)` 和 `.sheet-mask(29)` 共用 z-index。** 功能上不冲突（不会同时打开），但规范上应分层。

---

## 分层体系（当前实现, v2.0）

```
 99   .dialog-mask           ← 🔴 死代码, 待删除
 30   .sheet .preview-overlay ← 底部 sheet + 文件预览 overlay
 29   .sheet-mask .preview-mask ← sheet 遮罩 + 预览遮罩
 20   .msg-actions            ← 长按操作条
 15   .bnav                   ← 底部导航栏
  5   .fab .file-fab .back-float ← 浮动按钮
  5   .abar                   ← 顶栏
```

### 规则

| 层级 | z-index | 元素 | 备注 |
|------|---------|------|------|
| sheet | 29-30 | 所有底部 sheet + 遮罩, 文件预览 + 遮罩 | mask=29, panel/overlay=30 |
| action | 20 | 长按操作条 | 高于 nav(15), 低于 sheet(29) |
| nav | 15 | 底部导航栏 | — |
| float | 5 | FAB, 文件FAB, 返回按钮, 顶栏 | 内容区之上, nav 和 sheet 之下 |

---

## 验收测试用例

### TC-Z01：底部 sheet 不被导航栏遮挡

```
Given: 房间操作 sheet 打开, 内容足够长需要滚动
When: 滚动到 sheet 底部
Then:
  1. 所有内容可见, 不被底部导航栏遮挡
  2. sheet 的最后一个操作行完全可见
  3. sheet 的 max-height:92% 确保顶部不超出屏幕
```

### TC-Z02：长按操作条在 sheet 之下

```
Given: 房间操作 sheet 打开
When: 长按 sheet 外的消息
Then:
  1. 操作条(z-index:20) 出现在 sheet(z-index:30) 下方
  2. 操作条不可点击（被 sheet 遮罩拦截）
```

### TC-Z03：FAB 在 sheet 之下

```
Given: 任何底部 sheet 打开
When: 查看 FAB 位置
Then:
  1. FAB(z-index:5) 在 sheet 遮罩(z-index:29) 下方
  2. FAB 不可点击
```

---

## 实现约束（不可违反）

1. **所有 sheet 使用统一的 `.sheet-mask{z-index:29}` 和 `.sheet{z-index:30}`。** 禁止单个 sheet 用 ID 选择器覆盖 z-index。
2. **删除 `#sheetMask{z-index:11}` 和 `#sheetNew{z-index:12}`。** 这两个 ID 已不存在于 HTML，CSS 规则是死代码。
3. **删除 `.dialog-mask{z-index:99}`。** 新建房间已改用 sheet，不再需要居中 dialog。
4. **新增 UI 组件时，先查本文档确定层级，再赋值。** 禁止随意选一个数字。

---

## 改动清单

| 文件 | 改动 |
|------|------|
| `css/shell.css` | 删除 `#sheetMask{z-index:11}` 一行 |
| `css/shell.css` | 删除 `#sheetNew{z-index:12}` 一行 |
| `css/shell.css` | 删除 `.dialog-mask{...}` 整个规则块（如果不再使用） |

---

## 关联合同

- [CONTRACT_ARCH.md](CONTRACT_ARCH.md)
