# DESIGN: P2 — 看板彻底删除

版本: v2.0
日期: 2026-07-22
status: design-ready

---

## 验收测试用例

### TC-P2-01：底部导航只有两个 tab

```
Given: MOV v4.0 全新安装
When: 打开 APP, 看首屏
Then:
  1. 底部导航显示: [会话] [运行]
  2. "看板" tab 不存在
  3. 两个 tab 各占 50% 宽度（不是 33%）
  4. 点击"会话" → 房间列表
  5. 点击"运行" → 运行页
```

### TC-P2-02：看板相关 JS 文件不存在

```
Given: 构建 APK
When: 检查 APK 内容
Then:
  1. assets/js/board.js 不存在
  2. assets/js/app-board.js 不存在
  3. assets/board-apps/ 目录不存在或其下无任何文件
  4. hermes-shell.html 不含任何 board 相关 script 标签
```

### TC-P2-03：看板相关 CSS 已清理

```
Given: 构建 APK
When: 检查 assets/css/shell.css
Then:
  1. 不含 .board-trigger
  2. 不含 .board-panel
  3. 不含 .board-grid
  4. 不含 .board-app-card
  5. CSS 文件大小 ≤ 改前大小 - board 相关行数
```

### TC-P2-04：看板相关 HTML 已清理

```
Given: 构建 APK
When: 检查 assets/hermes-shell.html
Then:
  1. 不含 id="view-board"
  2. 不含 id="boardFrame"
  3. 不含 id="boardTrigger"
  4. 不含 id="boardPanel"
  5. 不含 id="boardAddSheet"
```

### TC-P2-05：现有功能不受影响

```
Given: 看板已删除
When: 执行以下操作
Then:
  1. 新建房间 → 成功
  2. 发送消息 → 成功
  3. 执行设备指令 → 成功
  4. 打开文件 tab → 成功
  5. 运行页所有模块正常 → 成功
  6. 构建通过 → 成功
```

---

## 实现约束（不可违反）

1. **必须物理删除文件，不能只注释代码。** `git rm`，不是注释掉。
2. **CSS 清理必须精确到行。** 搜索 `.board-` 前缀，逐条删除。不要批量替换（可能误删 `.board` 无关的样式）。
3. **`render.js` 的 `setTab()` 中的 `if(t==='board')` 分支必须删除。** 如果 JS 中其他地方引用了 `_boardApps` / `_boardActive` / `_boardHideTimer` / `_boardInited`，也一并删除。
4. **`localStorage` 中的 `mov_board_apps_v1` key 不清理。** 用户以后想用回看板（从 git history 恢复代码），数据还在。不占什么空间。
5. **构建后必须验证 APK 中的 assets 目录不包含被删除的文件。** 用 `unzip -l app.apk | grep board` 确认零结果。

---

## 删除文件清单（物理删除, git rm）

```
app/src/main/assets/js/board.js
app/src/main/assets/js/app-board.js
app/src/main/assets/board-apps/music.html
app/src/main/assets/board-apps/reader.html
app/src/main/assets/board-apps/fitness.html
app/src/main/assets/board-apps/notes.html
app/src/main/assets/board-apps/  (空目录, 一并删除)
```

---

## 改动清单

| 文件 | 改动 |
|------|------|
| `hermes-shell.html` | 删除 `#view-board` div（整个区块） |
| `hermes-shell.html` | 删除 `<button data-tab="board">` |
| `hermes-shell.html` | 删除 `<script src="js/board.js">` |
| `hermes-shell.html` | 删除 `<script src="js/app-board.js">` |
| `css/shell.css` | 删除 ~50 行 `.board-` 前缀样式 |
| `js/render.js` | 删除 `if(t==='board')` 分支 |
| `js/render.js` | 删除任何 `_boardApps` / `_boardActive` 等引用（如果有） |
| `js/store.js` | 删除 `mov_board_apps_v1` 的读写代码（如果有） |
| 6 个文件 | git rm（见上方清单） |
