# DESIGN: 砍看板

版本: v1.0
日期: 2026-07-22
status: design-ready

---

## 问题

看板占了一个底部 tab（33% 的导航面积），但它做的事情是"在 WebView 里跑四个小 HTML 应用（音乐、阅读、健身、笔记），用户还可以自己加 URL"。

这就是一个简陋的手机桌面。用户手机自带桌面比它好用一百倍。看板的数据不进房间、不调 AI、不和协作产生任何关系。它唯一的"存在理由"是文档里写的一句"看板是房间产出的落地终端"——但实际代码里没有任何一条路径能实现"房间产出部署到看板"。

**结论：砍掉底部 tab 入口。代码保留不删（以后可能拆成独立 App）。**

---

## 改动范围

只改两个地方：

### 1. HTML: 底部导航去掉"看板"

```html
<!-- 旧 -->
<button data-tab="chat" class="on">会话</button>
<button data-tab="board">看板</button>
<button data-tab="run">运行</button>

<!-- 新 -->
<button data-tab="chat" class="on">会话</button>
<button data-tab="run">运行</button>
```

### 2. JS: setTab 去掉 board 分支

```javascript
// render.js setTab() 
// 旧: if(t==='board'){initBoardIfNeeded();}
// 新: 删掉这行
```

---

## 不改的

- `js/board.js` — 保留
- `board-apps/*.html` — 保留
- `hermes-shell.html` 的 `#view-board` div — 保留
- `css/shell.css` 看板样式 — 保留
- `js/app-board.js` — 保留

**代码全部物理保留。** 只看不到入口。以后真要拆成 MOV Lite 时，这些文件直接拿过去用。

---

## 影响

| 维度 | 改前 | 改后 |
|------|------|------|
| 底部导航 | 会话 / 看板 / 运行 (3 栏) | 会话 / 运行 (2 栏) |
| 看板功能 | 可用 | 不可达（代码保留） |
| 用户认知 | "这个协作工具为什么有音乐播放器" | 不再困惑 |
| 代码量 | 不变 | 不变（只删入口） |

---

## 验收

- [ ] 底部只有"会话"和"运行"两个 tab
- [ ] 切 tab 正常，无 JS 报错
- [ ] 看板相关 JS 文件仍然加载（不报 404）
- [ ] 以后恢复看板只需在 HTML 加回一个 button
