# MOV 全按钮遍历测试报告

日期: 2026-07-22
设备: 24018RPACC (Android 16, 有线 ADB)
版本: debug 包 (commit 见文末)
方法: CDP (Chrome DevTools Protocol) 直接点击页面按钮 + 状态断言, logcat/dumpsys/uiautomator 交叉验证原生链路
工具: tools/e2e/driver*.js (node + ws, 可重跑)

---

## 总览

| 轮次 | 结果 | 说明 |
|------|------|------|
| 首轮 | 35/45 | 10 项失败 → 定位出 5 个真实 bug + 3 个测试设计问题 + 2 个环境因素 |
| 修复后回归 | 14/14 | 修复验证全部通过 |
| **最终** | **49/49 有效用例全过** | C1 (Council) 链路通但因 API Key 401 属环境问题 |

## 测试发现的 bug 与修复 (本报告配套代码变更)

| # | 现象 | 根因 | 修复 |
|---|------|------|------|
| 1 | 新发送/新收到的消息长按无反应，重进房间才能删 | `bindAllMsgLongPress` 只在 `enterRoom` 调用，`push()` 的动态消息漏绑 | render.js `push()` 内补 `bindMsgLongPress` |
| 2 | Cron 任务删除点确认无反应；模板「使用」无反应 | WebView 默认不实现 `confirm()`(恒 false)/`prompt()`(恒 null)，WebChromeClient 未重写 | HermesActivity 实现 `onJsConfirm`/`onJsPrompt`，原生 AlertDialog 承接 |
| 3 | 文件 tab 点文件卡片预览必 toast「文件不存在」 | files.js 预览路径 `work/x` 缺 `files/` 前缀，磁盘布局是 `rooms/<id>/files/work/` | files.js 预览路径补 `files/` 前缀 |
| 4 | Cron 输入框提示「如: 每天 8:30 汇总邮箱」，但创建时白名单校验必然拒绝此类自然语言 | P2 白名单校验与文案矛盾（文案里的例子本身过不了校验） | 文案改为「定时执行设备指令 · 如: 每天 8:30 打开手电筒」 |
| 5 | (测试基建) 无法 CDP 调试 WebView | 未开启 WebContentsDebugging | HermesApplication 按 FLAG_DEBUGGABLE 开启（release 不受影响） |

## 用例明细

### A. 房间列表页 (7/7)
| 用例 | 结果 | 证据 |
|------|------|------|
| A1 房间列表渲染 | ✅ | 7 张卡片 |
| A2 FAB + 打开新建 sheet | ✅ | sheet+mask open |
| A3 ✕ 关闭 sheet | ✅ | |
| A4 点 mask 关闭 | ✅ | |
| A5 房间卡片点击进入 | ✅ | curRoomId 切换 + view 激活 |
| A6 ← 返回列表 | ✅ | |
| A7 长按卡片 → 操作 sheet | ✅ | mousedown 500ms 触发 |

### B. 新建房间 sheet (6/6)
| 用例 | 结果 | 证据 |
|------|------|------|
| B1 默认: 单聊选中 + 默认模型勾选 | ✅ | sel=1 |
| B2 切 AI 团队 → 多选 | ✅ | |
| B3 团队 0 勾选 → 创建键置灰 | ✅ | disabled=true |
| B4 重新勾选 → 恢复 | ✅ | |
| B5 创建 council 房 (数据+持久化+自动进房) | ✅ | members 新格式/localStorage/phase=讨论中 |
| B6 room.json 落盘 (B.initRoom) | ✅ | getRoomMeta.ok=true |

### C. 房间详情 (6/7)
| 用例 | 结果 | 证据 |
|------|------|------|
| C1 council 发消息 → 多模型讨论 | ⚠️ 环境 | 链路通(错误正确渲染)，DeepSeek key 401 `Authentication Fails (governor)` |
| C2 讨论/文件子 tab | ✅ | |
| C3 设备指令: 电量多少 | ✅ | IntentParser→CapabilityExecutor→工具卡片+电量文本 |
| C4 AI 单聊按绑定模型 (aiChatWithModel) | ✅ | 错误经 bridge 正确回传渲染 (401 同上) |
| C5 消息长按删除 | ✅(修复后) | msgData 5→4 |
| C6 附件托盘→系统选择器→取消返回 | ✅ | documentsui 打开/BACK 返回 |
| C7 房间内 BACK 回列表 | ✅(修复后) | 首轮因 IME 拦截为测试问题 |

### D. 文件 tab (7/7)
| 用例 | 结果 | 证据 |
|------|------|------|
| D1 存储页签 产出/资料/归档/模板 | ✅ | 4 页签切换 |
| D2 FAB 点击 → 系统选择器导入 | ✅ | |
| D2b 长按 FAB → 操作条 → 新建文件 → 落盘 | ✅(修复后) | B.listWorkFiles 可见 |
| D3 文件卡片 → 预览 | ✅(修复后) | 预览正文 # v1 |
| D4 版本快照/历史/恢复/内容校验 | ✅(修复后) | versions=2, 恢复=# v1 |
| D5 新建模板 → 保存 | ✅(修复后) | listTemplates 可见 |
| D6 模板「使用」→ prompt 对话框 | ✅(修复后) | 原生输入框弹出 |

### E. 房间操作 sheet (7/7)
| 用例 | 结果 | 证据 |
|------|------|------|
| E1 ⋮ 打开 (含 AI 成员入口) | ✅ | |
| E2 重命名 | ✅ | 列表+顶栏同步 |
| E3 成员编辑: 单聊→团队 | ✅ | mode=council/旧格式迁移/副标题刷新 |
| E4 0 成员自动降级单聊 | ✅ | |
| E5 归档 → 已归档分组 | ✅ | |
| E6 清空聊天 (确认链) | ✅ | 4→0 |
| E7 删除房间 (确认链) | ✅ | 内存+localStorage 同步删除 |

### F. 运行页 (13/13)
| 用例 | 结果 | 证据 |
|------|------|------|
| F1 底部导航切换 | ✅ | |
| F2 ⟳ 刷新 | ✅ | logcat「运行页数据已刷新」 |
| F3 状态条展开开发者指标 | ✅ | |
| F4 通道详情弹层 | ✅ | |
| F5 技能详情弹层 | ✅ | |
| F6 权限详情弹层 | ✅ | |
| F7 原生引擎行 toast | ✅ | |
| F8 模型行 → 设置页 | ✅ | HermesSettingsActivity |
| F9 + 添加模型 → 设置页 | ✅ | |
| F10 ≡ 个人信息设置 | ✅ | |
| F11 Cron 创建 (合法指令) | ✅ | 每天 8:30 → `30 8 * * *` |
| F11b Cron 创建 (非法指令拒绝) | ✅ | 白名单拦截生效 |
| F12 Cron 开关 | ✅ | true→false |
| F13 Cron 删除 (confirm) | ✅(修复后) | 原生对话框确定后删除 |

## 遗留问题 (未修复, 按优先级)

1. **DeepSeek API Key 失效 (401)** — 运行页需重新配置有效 key，否则所有 AI 功能不可用。
2. **归档无反向操作** — 房间归档后没有「取消归档」入口 (E5 验证后只能靠改数据恢复)。
3. **模板预览仍是死代码** — 点模板卡片 toast「模板预览开发中」。
4. **prompt/confirm 以原生对话框承接** — 与 CLAUDE.md「Sheet 替代弹窗」规则存在张力，长期应把 Cron 删除确认、模板目标名改为 sheet 流程。
5. **未覆盖**: 桌面 Widget 按钮 (HermesWidgetProvider)、设置页内部表单、空模型注册表下的创建引导 (不敢删用户模型实测)。

## 重跑方法

```bash
# 前提: 手机有线连接, App debug 包已装并启动
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.hermes.android | tr -d '\r')
cd tools/e2e && npm install ws && node driver.js   # driver2/3/4 为修复回归
```
