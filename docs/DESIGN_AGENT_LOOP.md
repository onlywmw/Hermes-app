# CONTRACT+DESIGN: AgentLoop — council 房 agentic 循环

版本: v1.1（按两轮专家意见修订）
日期: 2026-07-22
status: design-ready
交付对象: 后端 + 前端程序员
前置: council 计划执行修复已落地 (430d71f)、file.write 预览卡已落地、房间模型路由已通
分期: **v1 = 循环核心（无评审团）；v2 = 两个评审点 + 返工轮**

---

## 背景与目标

旧 council 是单向瀑布（各说一句 3 句话观点 → 主持人憋 nextSteps → 批准 → 执行 → 交付），无真对话、无验证、无迭代、无工具。
目标：按业界 agentic coding 范式重塑——感知→计划→工具执行→验证→迭代，工作日志是房间一等公民。

## 决策记录

| 问题 | 决策 |
|------|------|
| 适用范围 | 只改 council 房；单聊房与 desk 维持现状 |
| 自主权 | 计划闸 + 自动跑（详见"安全红线"） |
| 模型分工 | 注册表默认模型当大脑（与房间成员解耦）；房间 AI 成员中除大脑外的模型当评审团（v2）；成员只有大脑 → 跳过评审点 |
| 实现架构 | 原生驱动循环：Java AgentLoop + 模型输出 JSON 动作 + 原生执行回灌 |
| 并发 | 全局同一时刻只允许 1 个 loop 运行；新任务内部排队（无队列 UI） |
| 计量 | token 与时间：计划卡预估 / 执行中实时 / 交付卡实际 vs 预估 |

## 安全红线（替代 CONTRACT_ARCH §5 与 CONTRACT_SECURITY 约束5，同一 commit 修订）

> **所有 AI 文件写入必须经用户确认，无例外。**
> **确认粒度 = 计划闸：批准计划即授权计划内列出的文件路径。**
> **执行段 file.write 的路径必须在已批准计划内；计划外路径一律硬拒绝并回灌"此文件不在批准计划内"。**
> **agent 确需新文件 → 输出 `revise_plan` 动作 → 带新计划回计划闸重新批准。**
> **每次写入记录工作日志。**

## 状态机

```
PLANNING      大脑拿目标 + 自动注入房间现状(文件列表) → 计划草案(含文件路径清单)
[PLAN_REVIEW  v2: 草案 → 评审团并行点评(3 句话: 风险/遗漏/优先级)]
PLAN_GATE     计划卡(计划+评审意见+预估消耗) → 用户: 批准 / 驳回+补充(回灌重出)
EXECUTING     循环 ≤12 步: 大脑出 1 个动作 → Java 执行 → 结果回灌 → 继续
[DELIV_REVIEW v2: finish → 评审团投票: 通过 / 需返工+理由
              多数返工且迭代 <2 → 意见回灌, 独立 6 步预算修复一轮再评审]
DONE / FAILED / STOPPED
```

熔断（任一触发 → mov 如实汇报卡在哪）：单步 60s、连续 2 次动作解析失败、总步数 12（首轮）/ 6（每个返工轮，独立预算）、**纯执行时长 10 分钟（ask_user 挂起期间暂停计时）**、返工轮 ≤2。

## 工具协议

大脑每轮只输出一个 JSON 动作：

```json
{"action":"file.list"}
{"action":"file.read","path":"README.md"}
{"action":"file.write","path":"game.html","content":"…完整内容…"}
{"action":"device.cmd","text":"电量多少"}
{"action":"ask_user","question":"配色要深还是浅?"}
{"action":"revise_plan","reason":"需要新增样式文件","plan":[...]}
{"action":"finish","summary":"交付说明"}
```

- 容错：剥 markdown 围栏 → 取首个 `{` 到末个 `}` → 失败回灌重试（计 1 次，连续 2 次熔断）
- file.*：BridgeValidator+isSafe 锁死房间目录；file.write 额外受"安全红线"路径白名单约束
- device.cmd：**白名单挂在 IntentParser 解析后的 capability 上**（battery.status/system.info/network.info/process.list/volume.get/brightness.get/wifi.status），不是原始文本白名单；file.ls 不在此列（文件操作只走 file.* 动作）；写能力（电话/设置/删除类）循环内一律拒绝
- ask_user：挂起，用户下一条消息作为答案回灌；挂起暂停总时长计时；10 分钟不答按"用户未回复"继续
- **transcript 纪律**：file.write 的 content 不进 transcript，只记 `file.write game.html → 4.2KB 已写入`；file.read 结果截断 2k 字符并标注；需要回看内容用 file.read 重新读。transcript 超 8k 字符压缩旧步（只留 action+结果首行）

## 工作日志（每步即时推 JS，复用 BridgeAi 的 cbId 回调机制）

| 类型 | 内容 | 渲染 |
|------|------|------|
| plan | 计划/重计划 | 计划卡（评审意见+预估+批准/驳回） |
| action | `file.write game.html` | 工具调用卡（金边可展开） |
| result | `已写入 4.2KB` / 错误原文 | 工具卡详情区 |
| review | 评审意见（v2，计划/交付两处） | 卡片内评审区 |
| note | mov 短评、止损、喊停 | mov 气泡 / sysline |

## 阶段徽章映射

| 状态机 | 徽章 |
|--------|------|
| PLANNING / PLAN_REVIEW | 讨论中 |
| PLAN_GATE | 待确认 |
| EXECUTING / DELIV_REVIEW | 执行中 |
| DONE | 已交付 |
| FAILED | 失败（badge err 红色，新增） |
| STOPPED | 已停止 |

## 计量

- AiClient 解析响应 usage（prompt_tokens/completion_tokens）；无 usage 按字符数 ÷4 兜底
- 计划卡：`预计 ~Nk tokens · ~N 分钟`（步数 × 步均经验值 + 评审固定开销，常量可调）
- 执行中状态行：`已用 Xk tokens · Ys` 实时刷新
- 交付卡：`实际 Xk · Y分Z秒（预估 …）`，评审消耗单独一行小字

## 验收测试用例

### TC-AL01：完整任务闭环 (v1)
```
Given: council 房, 默认模型已配置
When: 用户发"做一个贪吃蛇网页游戏" → 计划卡出现 → 点批准
Then:
  1. 工作日志流实时出现 (action/result 工具卡)
  2. 阶段徽章: 讨论中 → 待确认 → 执行中 → 已交付
  3. 交付卡含实际 tokens/耗时 与预估对比
  4. game.html 落盘 rooms/<id>/files/work/
```

### TC-AL02：计划外路径硬拒绝
```
Given: 已批准计划只列 game.html
When: 大脑输出 {"action":"file.write","path":"utils.js",...}
Then:
  1. 该动作被拒绝, 不执行写入
  2. 回灌 "此文件不在批准计划内"
  3. 工作日志记录该拒绝
```

### TC-AL03：revise_plan 逃生门
```
Given: 执行中大脑判断需要计划外文件
When: 输出 {"action":"revise_plan",...}
Then: 循环挂起, 新计划卡出现, 用户重新批准后按新白名单继续
```

### TC-AL04：动作解析容错
```
When: 大脑输出 markdown 围栏包裹 / 前后带废话的 JSON
Then: 正确提取并执行; 输出完全非 JSON → 回灌重试, 连续 2 次 → 熔断
```

### TC-AL05：device.cmd capability 白名单
```
When: 大脑输出 device.cmd "看看文件" (→file.ls) / "打电话给 10086" (→telephony.call)
Then: 均拒绝; "电量多少" (→battery.status) 放行
```

### TC-AL06：计划闸驳回与补充
```
When: 计划卡出现 → 用户驳回并补充"要触屏控制"
Then: 补充回灌大脑, 新计划卡出现, 原循环不产生任何写入
```

### TC-AL07：喊停
```
When: EXECUTING 中用户点 ■
Then: 当前步结束后停止, 徽章=已停止, 日志保留, 后续动作不再执行
```

### TC-AL08：ask_user 挂起
```
When: 大脑输出 ask_user → 用户 3 分钟后回复"深色"
Then: 循环继续且该 3 分钟不计入总时长; 回灌内容=用户回答
```

### TC-AL09：熔断
```
When: 连续 2 次解析失败 / 步数达 12 / 纯执行超 10 分钟
Then: 循环终止, mov 汇报卡点, 徽章=失败
```

### TC-AL10：交付评审返工 (v2)
```
Given: 评审团 2/3 投"需返工+理由"
When: 迭代计数 <2
Then: 意见回灌, 大脑带独立 6 步预算修复一轮, 再次评审; 迭代=2 仍不通过 → DONE 并标注评审保留意见
```

## 实现约束（不可违反）

1. **file.write 路径白名单由代码强制执行**（计划批准时提取路径集合），不靠 prompt 自觉。
2. **device.cmd 白名单挂在解析后 capability 上**，file.ls 除外（文件操作只走 file.*）。
3. **transcript 内容规则（2026-07-23 修订，e2e R4 血案）**：
   - file.write 的 content **不进** transcript（结果只记"N 字符已写入"）；
   - file.read 的结果**必须全文**进 transcript——曾用 oneLine 压成 120 字符，大脑永远看不到自己读到的内容，只能反复重读烧干步数；
   - transcript 压缩窗 16k 留尾，阈值必须 ≥ 一次完整读取页（32k 页只保尾部，改文件任务的甜点区 ≤14k）。
4. **全局单 loop 互斥**：第二个任务排队；AskUser 挂起暂停总时长计时。
5. **两个合同同步修订**：CONTRACT_ARCH §5 与 CONTRACT_SECURITY 约束5 必须在功能 commit 中一同更新， wording 以本文"安全红线"为准。
6. **评审团 (v2) 复用 CouncilClient 的并行 CompletionService 模式，但 AgentReview 是新类**——CouncilClient 本身不支持投票/返工。
7. **工作日志五类全部走 BridgeAi cbId 回调机制推送**，与旧 council 内容流无关（旧"3 句话观点"环节整体删除）。
8. **评审返工用独立 6 步预算**，与主执行预算 12 步分开计；返工 ≤2 轮，仍不通过 → DONE 并标注评审保留意见。
9. **file.read 分页协议**：offset/length 参数，单次 ≤32k；结果必须带显式页脚——未完给续读 offset，读完给"文件读完, 共 N 字符"确定信号。无确定信号时大脑会对上限产生幻觉（把 6.6k 文件当成被截断然后反问用户，e2e R3 现场）。
10. **计划必须完整覆盖到交付**（PLAN_RULES 强制）：修改类任务 = 读取(如需要) + file.write(每个要改的文件) + app.package(需要 APK 时)，禁止只规划读取/调查的畸形计划——批准 1 步"只读"计划后循环必然卡死或复读（e2e R4 现场）。
11. **JS 启动竞态缓冲**：原生循环启动即吐日志，JS 回调未拿到 loopId 期间到达的日志先缓冲后回放（`_agentLogBuf`），禁止直接丢弃——计划卡若被丢，循环在计划闸挂 10 分钟，用户视角即"卡死"。

## 改动清单

| 文件 | 改动 | 分期 |
|------|------|------|
| `agent/AgentLoop.java`（新） | 状态机循环 ~380 行 | v1 |
| `agent/ActionParser.java`（新） | 动作 JSON 容错解析 ~70 行 | v1 |
| `agent/AgentReview.java`（新） | 计划/交付评审 ~100 行 | v2 |
| `ai/AiClient.java` | 解析 usage 字段 | v1 |
| `BridgeAi.java`/`BridgeFactory.java` | agentStart/agentStop/agentAnswer | v1 |
| `js/chat.js` | runCouncil → runAgentTask：日志流+计划卡+喊停+ask_user | v1 |
| `js/bridge.js` | B 封装 3 个新方法 | v1 |
| `js/render.js` | PHASE_BADGE 加「失败」 | v1 |
| `CONTRACT_ARCH.md` §5 + `CONTRACT_SECURITY.md` 约束5 | 同步修订为"安全红线" | v1 |
| `AgentLoopTest.java`（新） | 解析容错+状态机+白名单+熔断单测 | v1 |

## 明确不做（YAGNI）

- 任务队列 UI、多 loop 并行（全局单 loop，排队仅内部）
- App 被杀后的 loop 崩溃恢复（v1 接受状态丢失）
- 预估模型自校准（留实际计量数据接口）
- 循环中切房后的日志回放（沿用 genCounter 守卫，前端丢弃）
- device.cmd 写能力开放
