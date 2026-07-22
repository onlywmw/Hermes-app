# DESIGN: 新建房间 — 极简化重设计

版本: v1.0
日期: 2026-07-22
status: design-ready

---

## 问题诊断

当前新建房间是两步向导 + 模型勾选 + 动态 seed——过度设计。226 行代码，和 `app.js` 原有代码冲突，`closeSheet`/`closeAllSheets`/`openSheetExclusive` 三个函数互相调用形成复杂的锁逻辑，sheet 互斥系统不可靠。

## 方案：回到最简单

新建房间 = 填名字 + 点创建。什么模式、什么模型、什么描述——全部去掉。

```
┌─────────────────────────────┐
│ 新建房间               [✕]  │
│                             │
│ 项目名                      │
│ ┌─────────────────────────┐ │
│ │                         │ │
│ └─────────────────────────┘ │
│                             │
│ [      创建房间      ]       │
└─────────────────────────────┘
```

- 只有一个输入框：项目名
- 不填 → 默认 "新项目"
- 点创建 → 房间生成，模式默认 `single`，成员默认 `['mov']`
- 创建后自动进入房间，seed 是通用欢迎语
- 以后用户可以在房间操作 sheet 里改名，或者在运行页改模型配置

**议会模式以后再加。** 现在多模型功能还没有真实落地，议会模式的模型勾选没有意义。

## 改动

### 删掉

- `app-room.js` 第 20-145 行：整个两步向导 + renderModelPicker + btnCreate 逻辑
- `app-room.js` 第 6-18 行：`_sheetOpen` / `closeAllSheets` / `openSheetExclusive`（这一套互斥锁不再需要——只有一个 sheet）
- `hermes-shell.html` sheetNew 里的第二步 HTML（sheetStep2 整个 div）
- `hermes-shell.html` sheetNew 里的描述输入框（newRoomDesc）

### 保留

- `hermes-shell.html` sheetNew 的第一步（sheetStep1）：项目名输入框 + 创建按钮
- FAB 按钮

### 新建

`app-room.js` — 极简创建逻辑，约 15 行：

```javascript
/* 新建房间 */
$('fabNew').addEventListener('click',function(){
  $('sheetMask').classList.add('open');
  $('sheetNew').classList.add('open');
  $('newRoomName').value='';
  $('newRoomName').focus();
});

$('sheetMask').addEventListener('click',function(){
  $('sheetMask').classList.remove('open');
  $('sheetNew').classList.remove('open');
});

$('btnSheetClose').addEventListener('click',function(){
  $('sheetMask').classList.remove('open');
  $('sheetNew').classList.remove('open');
});

$('btnCreate').addEventListener('click',function(){
  var name=$('newRoomName').value.trim()||'新项目';
  var id='r'+Date.now();
  ROOMS.splice(1,0,{
    id:id, name:name, mode:'single', members:['mov'],
    phase:'已交付', last:'MOV 已就绪', time:'现在',
    unread:0, played:false, msgs:[],
    seed:[{t:'agent',who:'mov',h:'我是 MOV。直接下达指令或提问即可。'}]
  });
  $('sheetMask').classList.remove('open');
  $('sheetNew').classList.remove('open');
  $('newRoomName').value='';
  B.initRoomStorage(id);
  renderRooms();persistRooms();enterRoom(id);
});
```

### 不再需要

- `closeAllSheets()`
- `openSheetExclusive()`
- `_sheetOpen`
- `newMode`
- `_pickedModels`
- `renderModelPicker`
- `updatePickHint`
- `btnStep1Next`
- `btnStep1Cancel`
- `btnStep2Back`
- 整个 sheetStep2 HTML

## 改动清单

| 文件 | 操作 |
|------|------|
| `js/app-room.js` | 删第 1-145 行（互斥锁+两步向导+模型勾选），替换为上面的极简版 |
| `hermes-shell.html` | 删 sheetStep2 div、newRoomDesc textarea、btnStep1Next/btnStep1Cancel、btnStep2Back、mopt 模式卡片、modelPicker div |
| `css/shell.css` | 删 `.mpick` `.mpick-wrap` `.mopt-detail` `.sh-label` `.step-foot` 等新建房间专用样式（如果有） |
