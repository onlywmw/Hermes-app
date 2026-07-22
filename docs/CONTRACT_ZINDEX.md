# CONTRACT: 层级系统

版本: v1.0
日期: 2026-07-22
status: design-ready
交付对象: 前端程序员

---

## 问题诊断

当前 CSS 没有统一的 z-index 分层体系。值散落在 7 个不同的选择器中，互不关联。导致：

1. **底部 sheet（房间操作、新建文件、模板）z-index=9-10，底部导航栏 z-index=15。** sheet 从屏幕底部滑入，导航栏挡在 sheet 上方，用户看不到 sheet 被遮挡的底部区域。
2. **#sheetMask(11) 和 #sheetNew(12) 是 ID 选择器，但 `.sheet-mask(9)` 和 `.sheet(10)` 是 class 选择器。** ID 和 class 的 z-index 不一致——同一个视觉层级的元素被分在两个 z-index 上。
3. **`.dialog-mask(99)` 跳得太高，和 `.preview-mask(29)` 之间有 70 层空档。** 未来加新组件时不知道往哪放。
4. **没有文档规定每种 UI 元素应该在哪个层级。**

---

## 分层体系

```
100  全屏 overlay（预览、系统级弹窗）
 80  居中 dialog（新建房间、确认对话框）
 60  底部 sheet + sheet mask
 40  长按操作条、toast
 20  浮动按钮（FAB、返回箭头）
 15  底部导航栏
 10  内容区各 view
  5  内容区内部元素（房间卡片、工具卡片等）
```

### 规则

| 层级 | z-index 范围 | 元素 | CSS 选择器 |
|------|------------|------|-----------|
| overlay | 100 | 文件预览、版本历史、运行页详情 | `.preview-mask`, `.preview-overlay` |
| dialog | 80 | 居中弹窗 | `.dialog-mask`, `.dialog` |
| sheet | 60 | 底部面板 + 遮罩（房间操作、新建文件、模板、添加应用） | `.sheet-mask`, `.sheet` |
| action | 40 | 长按操作条 | `.msg-actions` |
| float | 20 | FAB、返回按钮 | `.fab`, `.back-float` |
| nav | 15 | 底部导航栏 | `.bnav` |
| content | 10 | 三个主 view | `.view` |
| card | 5 | 内容区内部元素 | `.room`, `.toolcall`, `.proc-card` |

### 实现约束（不可违反）

1. **所有 z-index 值必须写在这个文档里。** 新增 UI 组件时，先查表确定它属于哪个层级，再赋值。禁止随意选一个数字。
2. **同一层级的元素使用相同的 z-index 值。** 如果两个同层级元素重叠，通过 DOM 顺序决定谁在上（后出现的在上），不改 z-index。
3. **CSS 选择器和 z-index 绑定。** 例如所有底部 sheet 共用 `.sheet-mask{z-index:60}` 和 `.sheet{z-index:60}`，不单独为某个 sheet 设更高的 z-index。
4. **Sheet 的底部 padding 必须包含 bnav 高度。** 公式: `padding-bottom = 20px + env(safe-area-inset-bottom,0px) + var(--bnav-h)`。确保 sheet 内容滚动到底部时不会被导航栏遮挡。
5. **禁止用 ID 选择器覆盖 class 的 z-index。** `#sheetMask{z-index:11}` 和 `#sheetNew{z-index:12}` 必须删除，改用统一的 `.sheet-mask{z-index:60}` / `.sheet{z-index:60}`。

---

## 验收测试用例

### TC-Z01：底部 sheet 不被导航栏遮挡

```
Given: 房间操作 sheet 打开, 内容足够长需要滚动
When: 滚动到 sheet 底部
Then:
  1. 所有内容可见, 不被底部导航栏遮挡
  2. sheet 的最后一个操作行（删除）完全可见
  3. 关闭按钮（✕）在任何滚动位置都可见（它不在 sheet 滚动区内）
```

### TC-Z02：居中弹窗在最上层

```
Given: 文件预览 overlay 打开
When: 打开新建房间弹窗
Then:
  1. 新建房间弹窗出现在预览 overlay 上方
  2. 预览 overlay 被遮住但仍在 DOM 中
```

### TC-Z03：长按操作条不被遮住

```
Given: 聊天区滚到底部, 最后一条消息在底部导航上方
When: 长按该消息
Then:
  1. 操作条出现在导航栏上方, 不被遮挡
  2. 操作条可见且可点击
```

### TC-Z04：FAB 不被 sheet 遮住

```
Given: 房间列表, FAB 在右下角
When: 打开任何底部 sheet
Then:
  1. FAB 在 sheet 下方（被遮住, 符合预期——sheet 打开时用户不应该点 FAB）
  2. sheet 关闭后 FAB 恢复可见
```

---

## 改动清单

| 文件 | 改动 |
|------|------|
| `css/shell.css` | `.sheet-mask` z-index: 9→60 |
| `css/shell.css` | `.sheet` z-index: 10→60, padding-bottom 加 bnav-h |
| `css/shell.css` | 删除 `#sheetMask{z-index:11}` 和 `#sheetNew{z-index:12}` 两行 |
| `css/shell.css` | `.bnav` z-index: 15→15（不变, 但低于 sheet 的 60） |
| `css/shell.css` | `.dialog-mask` z-index: 99→80 |
| `css/shell.css` | `.preview-mask` z-index: 29→100, `.preview-overlay` z-index: 30→100 |
| `css/shell.css` | `.msg-actions` z-index: 20→40 |
| `css/shell.css` | `.fab` z-index: 5→20, `.back-float` z-index: 5→20 |

---

## 不做的事

- 不处理 board 相关 z-index（board 即将删除，P2）
- 不引入 CSS 变量 `--z-*`（当前项目风格是直接写数字，保持一致）
