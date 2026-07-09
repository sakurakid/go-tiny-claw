# go-tiny-claw 面试速查稿

这份文档用于面试前快速复习。建议面试时按“自我介绍 -> 项目背景 -> 架构亮点 -> 技术难点 -> 结果与反思”的顺序讲，不要一上来堆模块名。

## 1 分钟自我介绍

面试官您好，我主要做 Java 后端和 AI Agent 工程化方向的实践。最近我重点做了一个 Java 版 Agent Harness 项目，名字叫 go-tiny-claw。

这个项目不是简单调大模型 API，而是参考 Go 版 tiny-claw / claw 的工程思想，用 Java 实现了一套可运行的 Agent 底座。核心包括 ReAct 主循环、Provider 抽象、工具注册表、Session 记忆、上下文压缩、错误自愈、死循环干预、多智能体 Subagent，以及 Token 和费用监控。

我在这个项目里主要关注两个点：第一是怎么让大模型真正能稳定使用工具完成任务；第二是怎么把上下文、错误、成本、长程任务这些工程问题控制住。整体上它更像一个微型 Claude Code / OpenClaw 的 Java 实验版。

## 30 秒项目介绍

go-tiny-claw 是一个 Java 实现的 Agent Harness。它把大模型、工具、会话记忆、错误恢复、多智能体委派和费用监控拆成清晰模块，让模型可以通过 `read_file`、`write_file`、`edit_file`、`bash` 等工具完成真实工作区任务。

一句话：我做的不是聊天机器人，而是一个能调用工具、能恢复状态、能控制风险、能观测成本的 Agent 执行引擎。

## 项目整体架构

```text
用户输入
  -> Session 写入用户消息
  -> PromptComposer 组装 System Prompt / AGENTS.md / Skills
  -> AgentEngine 执行 ReAct 主循环
     -> Provider 调用真实模型
     -> ToolRegistry 分发工具调用
     -> RecoveryManager 注入错误恢复建议
     -> ReminderInjector 检测 Doom Loop
     -> Compactor 控制上下文长度
     -> Reporter 输出状态到终端或飞书
  -> Session 保存历史和资源账单
```

核心模块可以这样讲：

- `Schema`：统一消息、工具调用、工具结果、工具定义。
- `Provider`：屏蔽 OpenAI-compatible / Anthropic-compatible API 差异。
- `ToolRegistry`：统一管理本地工具，负责路由模型发起的 tool call。
- `AgentEngine`：ReAct 主循环，负责模型推理、工具执行和 Observation 回写。
- `Session`：会话状态和 Working Memory。
- `PromptComposer`：动态组装系统提示词、项目规则和 Skill。
- `Compactor`：在发给模型前压缩上下文，避免爆上下文窗口。
- `RecoveryManager`：工具报错时注入下一步排查建议。
- `ReminderInjector`：连续失败时打断死循环。
- `SubagentTool`：主 Agent 可以派出只读子 Agent 做深度探索。
- `CostTracker`：统计 API 耗时、Token 和会话费用。

## 面试重点亮点

### 亮点 1：ReAct 主循环不是只聊天，而是能真正执行工具

可以这样说：

我把 Agent 的执行拆成“模型决策 -> 工具执行 -> 结果回写 -> 再决策”的 ReAct 循环。模型不直接操作文件，而是输出结构化 ToolCall，Java 侧通过 `ToolRegistry` 分发到具体工具，比如读取文件、写文件、编辑文件、执行 bash。

关键点：

- 工具调用和工具结果都用统一 `Schema` 表达。
- 工具可以并发执行，但 Observation 会按原始 tool call 顺序写回。
- 工具结果保留 `toolCallId`，避免模型 API 因工具链路不连续报错。

面试官追问“为什么要有 ToolRegistry”时：

ToolRegistry 相当于插件中心。AgentEngine 不需要知道每个工具怎么执行，只需要把模型请求的工具名和参数交给 Registry。这样新增工具时不用改主循环，符合开闭原则。

### 亮点 2：Session 和 Working Memory 解决多轮任务状态问题

可以这样说：

一开始 Agent 每次运行都从零开始，这不适合飞书群、CLI 多轮任务或者长程开发任务。所以我引入了 `Session`，每个会话有自己的历史消息、工作区和资源统计。

难点是不能无限把历史塞给模型，所以我做了 Working Memory：

- Session 保存全量历史。
- 每次运行只取最近若干条消息。
- 如果截断后第一条是孤儿工具 Observation，就丢弃它，避免 API 报工具调用链不完整。

这块体现的是：全量状态和模型短期上下文要分开管理。

### 亮点 3：Compactor 控制上下文膨胀

可以这样说：

工具返回很容易特别大，比如读日志、读源码。如果全量塞回模型，会导致上下文成本暴涨甚至 OOM。所以我做了 `Compactor`。

策略是：

- System Prompt 永远保留。
- 最近几条消息作为保护区。
- 远期大 Observation 直接掩码。
- 近期超大 Observation 保留头尾，中间截断。

重点是：Compactor 只压缩“本轮发给模型的临时上下文”，Session 里仍保存完整历史。这样既省 token，又不破坏真实记录。

### 亮点 4：Recovery 错误自愈

可以这样说：

工具失败后，如果只把裸错误交给模型，模型经常会瞎猜。所以我加了 `RecoveryManager`，它会根据工具名和错误特征注入系统级修复建议。

例子：

- `edit_file` 找不到 `old_text`：提示先 `read_file` 获取最新内容。
- 命中多个替换位置：提示增加上下文。
- 文件不存在：提示先 `ls` / `find`。
- bash 命令不存在：提示换当前系统可用命令。

这不是替模型重试，而是把错误变成带行动指导的 Observation，让模型下一轮自己修正。

### 亮点 5：Reminder 死循环干预

可以这样说：

大模型做长任务时有一个常见问题：在同一个错误上反复重试，浪费 token。我做了 `ReminderInjector`，对连续失败的工具调用做指纹统计。如果同一个工具参数连续失败多次，就注入 `[SYSTEM REMINDER]` 强制打断。

它会告诉模型：

- 停止原样重试。
- 换策略。
- 如果无法解决，明确告诉用户需要人工帮助。

这个模块解决的是 Agent 的稳定性问题。

### 亮点 6：Plan Mode 支持长程任务断点续传

可以这样说：

长程任务不能只靠模型记忆，所以我做了 Plan Mode。开启后，PromptComposer 会强制模型先检查 `PLAN.md` 和 `TODO.md`：

- 新任务：创建计划和 TODO。
- 旧任务：读取已有计划，从第一个未完成任务继续。
- 每完成一步，必须立即把 TODO 打勾。

这样即使进程重启，短期上下文丢失，Agent 也可以通过文件系统恢复任务状态。

### 亮点 7：Subagent 多智能体委派

可以这样说：

主 Agent 如果自己读大量文件，上下文会被污染。所以我加了 `SubagentTool`。主 Agent 可以调用 `spawn_subagent` 派出一个 Explorer Subagent 去搜索和读取文件。

设计重点：

- 子 Agent 有独立上下文，不污染主 Agent。
- 子 Agent 只拿只读 Registry，不能写文件和编辑文件。
- 子 Agent 最后只返回摘要报告。
- 主 Agent 根据报告继续决策。

为什么要 `AgentRunner` 接口：

`SubagentTool` 在 tools 包，`AgentEngine` 在 engine 包。如果 Tool 直接依赖 Engine，会形成包耦合甚至循环依赖。所以我抽了 `AgentRunner` 接口，SubagentTool 只依赖接口，真正由 AgentEngine 实现 `runSub(...)`。

可以用一个比喻讲：

主 Agent 是架构师，子 Agent 是只读调查员。调查员可以翻资料，但不能改代码，最后交一份报告给架构师。

### 亮点 8：Observability 费用监控

可以这样说：

Agent 进入真实使用后，成本和延迟必须可观测。所以我做了 `CostTracker`，用装饰器模式包住真实 Provider。

调用链是：

```text
AgentEngine -> CostTracker -> RealProvider -> 大模型 API
```

Provider 负责把 API 原生 Usage 填进 `Schema.Message`，CostTracker 读取 Usage，统计：

- API 耗时。
- 输入 Token。
- 输出 Token。
- 当前会话累计费用。

这个设计的好处是 Engine 完全不用关心费用逻辑，后续换模型或者关闭监控都很方便。

## 技术难点与回答模板

### 难点 1：怎么保证工具调用历史合法？

回答：

大模型 API 对工具调用顺序很敏感，Assistant 发起 tool call 后，后面必须跟对应 tool result。如果 Working Memory 截断时把 Assistant tool call 截掉，只留下 tool result，就会报错。

我的处理是：Session 截取 Working Memory 后，如果窗口开头是带 `toolCallId` 的 Observation，就认为它是孤儿消息，直接丢弃，直到遇到普通 User 或 Assistant 消息。

### 难点 2：怎么防止上下文越来越大？

回答：

我分两层处理：

第一层是 Session 的 Working Memory，只取最近若干条消息。  
第二层是 Compactor，对临时上下文做压缩。远期大工具输出会被掩码，近期超大输出会保留头尾。

这样既不会每次都从零开始，也不会把所有历史都塞给模型。

### 难点 3：为什么工具要有边界？

回答：

Agent 能执行 bash、写文件，本质上是有风险的。所以工具层必须绑定工作区路径，并做路径 normalize 和边界校验，防止越权读写。Subagent 还进一步只给只读工具，降低爆炸半径。

### 难点 4：为什么需要错误自愈，不直接让模型自己看错误？

回答：

裸错误对模型来说信息密度不够。比如 `old_text` 不匹配，模型可能继续猜字符串。RecoveryManager 会把错误升级成带排查方向的 Observation，告诉模型先读文件、增加上下文或换命令。这样能减少无效重试。

### 难点 5：Subagent 和普通工具有什么区别？

回答：

从主 Agent 看，Subagent 仍然是一个普通工具，返回值也是工具 Observation。区别在于这个工具内部启动了一个独立 Agent 循环，自己可以多轮搜索、读取、总结。主 Agent 最终只看到压缩后的报告。

### 难点 6：CostTracker 为什么做成装饰器？

回答：

因为费用统计是横切关注点，不应该侵入 AgentEngine。CostTracker 实现同一个 `LLMProvider` 接口，包住真实 Provider。Engine 不需要知道中间多了一层，符合装饰器模式，也方便后续叠加日志、限流、熔断等能力。

## 可以主动讲的结果

- 跑通了真实 Provider，而不只是 Mock。
- 支持 OpenAI-compatible 和 Anthropic-compatible 两类协议。
- 工具调用可以并发执行。
- 支持终端 Reporter 和飞书 Reporter。
- 支持 Session 隔离，适合多用户、多群聊。
- 支持 Plan Mode 长程任务恢复。
- 支持 Subagent 只读探索，避免污染主上下文。
- 支持 Token 和费用统计，能看到真实调用成本。

## 简历项目描述

可以放简历上的版本：

基于 Java 实现 Agent Harness 实验项目，参考 Claude Code / OpenClaw 的工程思想，完成 ReAct 主循环、Provider 抽象、工具注册表、Session 记忆、动态 Prompt Composer、上下文压缩、错误恢复、Doom Loop 干预、Subagent 多智能体委派和 Token 成本监控等能力。项目支持 OpenAI-compatible / Anthropic-compatible Provider，接入本地文件、bash、编辑工具和飞书机器人展示层，验证了大模型工具调用在真实工作区中的稳定执行链路。

## 面试官常问问题

### Q：这个项目和普通 ChatGPT 调 API 有什么区别？

普通聊天只是问答。这个项目有工具调用、状态管理、错误恢复和成本监控。模型可以通过工具真实读写文件、执行命令，并根据 Observation 继续决策。

### Q：为什么选择 Java 实现？

一方面我主要技术栈是 Java，另一方面 Java 在后端工程化、并发、类型约束、服务化接入上比较适合做 Harness。Go 版思路偏极简，我把它翻译成 Java 时更关注接口边界、模块拆分和可维护性。

### Q：目前离生产还差什么？

可以坦诚讲：

- 工具权限还可以更细，比如危险命令审批。
- Session 现在主要在内存里，后续要 JSONL 或数据库持久化。
- Compactor 目前用字符估算，后续可以接真实 tokenizer 或基于 Provider Usage 自适应压缩。
- Subagent 目前是单类 Explorer，后续可以做不同角色的子 Agent。
- 价格表现在硬编码，后续应改成配置中心。

### Q：你在项目里最有技术含量的设计是什么？

推荐回答：

我觉得是稳定性控制这条线：Working Memory 避免上下文爆炸，Compactor 控制大输出，RecoveryManager 引导错误恢复，ReminderInjector 打断死循环，Subagent 隔离探索上下文。这几个模块组合起来，解决的是 Agent 从 Demo 走向可用时最容易出问题的地方。

## 最后总结句

如果面试最后让你总结项目，可以这样说：

这个项目让我对 Agent 工程化有了比较系统的理解：大模型只是大脑，真正难的是外面的 Harness。要让 Agent 稳定可用，需要处理工具边界、上下文管理、错误恢复、长程状态、多智能体隔离和成本观测。我这个 Java 版 go-tiny-claw 就是在把这些关键工程问题逐个拆开并落地验证。
