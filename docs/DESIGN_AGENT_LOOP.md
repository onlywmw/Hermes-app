# DESIGN: AgentLoop — council 房 agentic 循环

版本: v1.0
日期: 2026-07-22
status: approved
关联: CONTRACT_ARCH.md（§5 随之修订）、CONTRACT_MODEL.md（新增 TC）

---

## 背景与目标

现状 council 房是单向瀑布：各模型并行说一句 3 句话观点（互不可见）→ 主持人一把梭汇总憋 nextSteps JSON → 批准 → 执行 file.write → 交付卡。痛点：模型间无真对话、plan 一次成型、执行不验证、失败不迭代、讨论中不能用工具。

目标：把"kimi 写代码的路线"（感知→计划→工具执行→验证→迭代）搬进 council 房。

## 决策记录（与用户确认）

| 问题 | 决策 |
|------|------|
| 适用范围 | 只改 council 房；单聊房与 desk 维持现状 |
| 自主权 | 计划闸 + 自动跑：批准一次后 agent 自动跑完循环，执行中不打断，可喊停。CONTRACT_ARCH §5「所有 AI 文件写入必须预览确认」修订为「计划闸内确认，执行段 file.write 免逐次确认」 |
| 模型分工 | 主从：注册表默认模型当大脑驱动循环（与房间成员解耦）；房间 AI 成员中除大脑外的模型当评审团（只动嘴不碰工具）；若房间成员只有大脑 → 跳过两个评审点直接进计划闸/交付 |
| 实现架构 | 原生驱动循环（方案 A）：新增 Java AgentLoop，模型输出 JSON 动作，Java 执行并回灌 |
| 心智模型 | 干活的人（agent=循环骨架+工具+可换大脑）+ 出主意的人（评审团）；工作日志是房间一等公民 |
| 计量 | token 与时间：计划卡预估、执行中实时、交付卡实际 vs 预估 |

## 状态机

```
PLANNING      大脑拿目标 + 自动注入房间现状(文件列表) → 计划草案
PLAN_REVIEW   草案 → 评审团并行点评(3 句话: 风险/遗漏/优先级)
PLAN_GATE     计划卡(含评审意见+预估) → 用户: 批准 / 驳回+补充(回灌重出)
EXECUTING     循环 ≤12 步: 大脑出 1 个动作 → Java 执行 → 结果回灌 → 继续
DELIV_REVIEW  finish → 评审团投票: 通过 / 需返工+理由
              多数返工且迭代 <2 → 意见回灌修复一轮再评审
DONE / FAILED / STOPPED
```

熔断（任一触发 → mov 如实汇报卡在哪）：单步 60s、连续 2 次动作解析失败、总步数 12、总时长 10 分钟、交付迭代 2 轮。

## 工具协议

大脑每轮只输出一个 JSON 动作：

```json
{"action":"file.list"}
{"action":"file.read","path":"README.md"}
{"action":"file.write","path":"game.html","content":"…完整内容…"}
{"action":"device.cmd","text":"电量多少"}
{"action":"ask_user","question":"配色要深还是浅?"}
{"action":"finish","summary":"交付说明"}
```

- 容错：剥 markdown 围栏 → 取首个 `{` 到末个 `}` → 失败回灌重试（计 1 次，连续 2 次熔断）
- 安全：file.* 走 BridgeValidator+isSafe 锁死房间目录；device.cmd 硬编码只读白名单（电量/系统信息/网络/进程/文件列表等），打电话/删改类循环内拒绝（不靠 prompt 自觉）
- ask_user：循环挂起，用户下一条消息作为答案回灌；10 分钟不答按"用户未回复"继续
- 上下文：transcript 逐步追加，超 8k 字符压缩旧步（只留 action+结果首行）

## 工作日志

每推进一步即时推 JS（复用 council 伪流式回调通道）。五类：

| 类型 | 内容 | 渲染 |
|------|------|------|
| plan | 计划/重计划 | 计划卡（含评审意见、预估、批准/驳回） |
| action | `file.write game.html` | 工具调用卡（金边可展开） |
| result | `已写入 4.2KB` / 错误原文 | 工具卡详情区 |
| review | 评审意见（计划/交付两处） | 卡片内评审区 |
| note | mov 短评、止损、喊停 | mov 气泡 / sysline |

## 评审点（不占循环步数）

- 计划评审：草案+目标 → 评审团并行各 3 句话（风险/遗漏/优先级）→ 附计划卡底部
- 交付评审：压缩日志+产出清单 → 并行投票「通过/需返工+一句理由」→ 多数返工且迭代<2 → 回灌修复；结论写进交付卡
- 复用 CouncilClient 的并行 CompletionService 模式；旧 council"3 句话观点"环节整体删除

## 计量

- AiClient 解析响应 usage（prompt_tokens/completion_tokens）；无 usage 的按字符数 ÷4 兜底
- 计划卡：`预计 ~Nk tokens · ~N 分钟`（计划步数 × 步均经验值 + 评审固定开销，常量可调，标注预估）
- 执行中状态行：`已用 Xk tokens · Ys` 实时刷新
- 交付卡：`实际 Xk · Y分Z秒（预估 …）`，评审消耗单独标一行小字

## 房间 UI

全部复用现有组件（计划卡/工具卡/交付卡/气泡/sysline）。执行中输入框变「执行中… ■」；阶段徽章沿用 `讨论中→待确认→执行中→已交付/已停止`。

## 改动清单

| 文件 | 改动 |
|------|------|
| `agent/AgentLoop.java`（新） | 状态机循环 ~300 行 |
| `agent/ActionParser.java`（新） | 动作 JSON 容错解析 ~70 行 |
| `agent/AgentReview.java`（新） | 两个评审点 ~80 行 |
| `ai/AiClient.java` | 解析 usage 字段 |
| `BridgeAi.java`/`BridgeFactory.java` | `agentStart(goal,roomId,cbId)`/`agentStop(loopId)`/`agentAnswer(loopId,text)` |
| `js/chat.js` | runCouncil → runAgentTask：日志流+计划卡+喊停+ask_user |
| `js/bridge.js` | B 封装 3 个新方法 |
| `CONTRACT_ARCH.md` | §5 修订为计划闸 |
| `CONTRACT_MODEL.md` | 新增 TC：循环/协议容错/评审/熔断/喊停/ask_user/计量 |
| `AgentLoopTest.java`（新） | ActionParser + 状态机单测 |

## 边界情况

| 场景 | 行为 |
|------|------|
| key 失效/网络断 | 第一步即错误卡，不进入循环 |
| 循环中切房 | genCounter 守卫：循环照跑、前端丢弃（同 council 现状），本期接受 |
| 执行中发新消息 | ask_user 挂起中 → 当答案；否则排队到循环结束作为新任务 |
| 大脑反复写同一文件 | 不干预（算迭代行为，计入步数） |

## 测试

- `AgentLoopTest`：ActionParser 容错（围栏/截断/畸形）+ 状态机迁移 + 熔断条件
- `./gradlew test` 全量回归
- tools/e2e 端到端：council 房发"做个贪吃蛇"→ 计划卡（预估+评审意见）→ 批准 → 工具日志流 → 交付卡（实际计量）→ 文件落盘

## 明确不做（YAGNI）

- 多任务并行/任务队列 UI（排队仅内部处理）
- 预估模型自校准（留实际计量数据接口，本期不做）
- 循环中切房后的日志回放（本期接受不可见）
- device.cmd 写能力（打电话/设置类）开放
