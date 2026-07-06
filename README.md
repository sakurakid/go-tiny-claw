# go-tiny-claw

一个用 Java 编写的 Agent Harness 练习 Demo。

这个仓库参考 tiny-claw / claw 风格的 Go 项目结构，但实现语言使用 Java。当前目标不是做完整框架，而是用尽量少的代码跑通 Harness 最核心的几件事：统一 Schema、Provider 抽象、Tool Registry、Main Loop、慢思考模式，以及面向飞书机器人的消息承接层。

## 项目结构

```text
go-tiny-claw/
├── pom.xml
├── README.md
├── docs/
│   └── main-loop-and-schema.md
└── src/main/java/lab/agentharness/
    ├── claw/
    │   ├── CompactorSmokeTest.java # 上下文压缩器真实 Provider 冒烟测试入口
    │   ├── FeishuMain.java        # 飞书长连接启动入口，适合本地开发接入机器人
    │   ├── Main.java              # Demo 入口，装配真实 Provider、ToolRegistry、AgentEngine
    │   ├── PlanModeSmokeTest.java # Plan Mode 长程任务与断点续传测试入口
    │   ├── ProviderThinkingCompare.java # 真实 Provider 慢思考对比入口
    │   ├── ProviderSmokeTest.java # 真实 Provider 冒烟测试入口
    │   ├── RecoverySmokeTest.java # 工具失败自愈提示注入测试入口
    │   ├── SessionMemorySmokeTest.java # 多 Session 与 Working Memory 冒烟测试入口
    │   └── SkillPromptSmokeTest.java # PromptComposer 与 SkillLoader 冒烟测试入口
    ├── context/
    │   ├── Compactor.java         # 请求模型前的上下文水位监控与压缩器
    │   ├── PromptComposer.java    # 动态组装 System Prompt
    │   ├── RecoveryManager.java   # 工具错误分析与系统救援指南注入
    │   ├── Skill.java             # 标准化技能结构
    │   └── SkillLoader.java       # 扫描并解析 .claw/skills/**/SKILL.md
    ├── engine/
    │   ├── AgentEngine.java       # Main Loop / ReAct 核心循环
    │   ├── Reporter.java          # 引擎向外部展示层输出状态的接口
    │   ├── Session.java           # 单个会话的历史消息与短期工作记忆
    │   ├── SessionManager.java    # 全局会话管理器，用于多用户/多终端隔离
    │   └── TerminalReporter.java  # 本地 CLI 的默认 Reporter
    ├── feishu/
    │   ├── FeishuBot.java         # 飞书长连接事件监听与 Agent 任务桥接
    │   └── FeishuReporter.java    # 将 Agent 状态发送回飞书会话
    ├── provider/
    │   ├── AnthropicCompatibleProvider.java # Anthropic-compatible 协议适配器
    │   ├── LLMProvider.java                 # LLM Provider 接口
    │   ├── MockProvider.java                # 本地 Mock，模拟 Thinking 与 Action
    │   └── OpenAICompatibleProvider.java    # OpenAI-compatible 协议适配器
    ├── schema/
    │   └── Schema.java            # 统一消息、工具调用、工具结果、工具定义
    └── tools/
        ├── BaseTool.java          # 所有本地工具的统一接口
        ├── BashTool.java          # 以 WorkDir 为默认目录执行终端命令
        ├── EditFileTool.java      # 对现有文件做局部字符串替换
        ├── ReadFileTool.java      # 读取工作区文件的真实工具
        ├── Registry.java          # 工具注册表接口
        ├── ToolRegistry.java      # 动态工具注册与分发
        └── WriteFileTool.java     # 创建或覆盖写入工作区文件
```

## 当前实现

- `Schema` 定义系统统一血液：`Message / ToolCall / ToolResult / ToolDefinition / RawJson`。
- `LLMProvider` 抽象大模型调用：`generate(messages, availableTools)`。
- `BaseTool` 规范具体工具：`name()`、`definition()` 和 `execute(arguments)`。
- `Registry` 抽象工具注册与分发：`register(tool)`、`getAvailableTools()` 和 `execute(call)`。
- `AgentEngine` 维护 ReAct 主循环：Reasoning -> Action -> Observation。
- `AgentEngine` 支持 Plan Mode，通过 System Prompt 强制 Agent 将长程任务计划写入 `PLAN.md` / `TODO.md`。
- `Reporter` 抽象引擎输出：`onThinking / onToolCall / onToolResult / onMessage`。
- `Session` 保存一次持续对话的历史消息，并通过 `getWorkingMemory(limit)` 提取短期工作记忆。
- `SessionManager` 按会话 ID 隔离不同用户、终端或群聊的上下文状态。
- `PromptComposer` 动态组装 System Prompt：核心纪律、`AGENTS.md` 和 `.claw/skills/**/SKILL.md`。
- `Compactor` 在请求大模型前估算上下文字符数，必要时掩码远期大输出并截断近期超大 Observation。
- `RecoveryManager` 在工具失败时分析错误特征，并把恢复建议注入 Observation，引导模型自我纠偏。
- `SkillLoader` 解析标准 Skill Markdown，支持 `name` 和 `description` YAML Frontmatter。
- `FeishuBot` 使用飞书官方 Java SDK 的 WebSocket 长连接模式监听用户消息，不需要公网回调地址。
- `enableThinking` 支持慢思考模式：先剥夺工具规划，再恢复工具执行。
- `OpenAICompatibleProvider` 支持 DeepSeek / 智谱等 OpenAI-compatible 服务。
- `AnthropicCompatibleProvider` 支持 Claude / 兼容 Anthropic Messages API 的服务。
- `ReadFileTool` 读取 `WorkDir` 内文件，并做路径边界校验与 8000 字节截断。
- `WriteFileTool` 创建或覆盖写入 `WorkDir` 内文件，并自动创建父目录。
- `EditFileTool` 对现有文件做局部替换，要求 `old_text` 唯一匹配，并提供换行/缩进容错。
- `BashTool` 以 `WorkDir` 为默认目录执行终端命令，带 30 秒超时、非 0 退出回传和 8000 字节截断。

## 慢思考模式

`AgentEngine` 构造时可以打开慢思考：

```java
AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, true);
```

开启后，每一轮 Turn 会拆成两段：

1. Phase 1 Thinking：传入空工具列表，模型看不到工具 Schema，只能输出规划。
2. Phase 2 Action：恢复工具列表，模型基于刚才的规划发起工具调用或给出最终回复。

这能模拟 Coding Agent 里的“先想清楚，再动手”。简单任务可以关闭它以减少 token，复杂代码任务可以打开它减少盲目工具调用。

## 当前 Demo 会做什么

1. `Main` 获取当前目录作为 `WorkDir` 物理边界。
2. `Main` 初始化真实 Provider 和 `ToolRegistry`。
3. `AgentEngine` 创建 `contextHistory`，写入 system message 和 user message。
4. `ToolRegistry` 挂载 `read_file / write_file / edit_file / bash` 极简工具集。
5. `Main` 开启慢思考，让模型先规划如何读取多个文件。
6. 模型在同一轮 Action 中一次性发起三个 `read_file` 调用，读取 `a.txt / b.txt / c.txt`。
7. `AgentEngine` 并发执行这批工具调用，再按原始顺序聚合 Observation。
8. `AgentEngine` 把每次工具结果作为 Observation 写回上下文，并保留 `ToolCallID`。
9. 模型根据三份文件内容总结它们分别记录的领域信息。

## 运行方式

项目会优先读取系统环境变量，其次读取本地 `.env.local`。`.env.local` 不会提交到 Git。

本机已经可以直接使用 `.env.local` 运行；其他机器可参考 `.env.local.example` 创建自己的本地配置。

```bash
mvn compile exec:java
```

`mvn compile exec:java` 的含义是：先编译 Java 源码，再通过 Maven 的 exec 插件启动 `lab.agentharness.claw.Main`，并自动带上项目依赖 classpath。它不是打 jar，也不是运行 jar。

如果已经编译过，也可以直接在 IDE 中点击 [Main.java](src/main/java/lab/agentharness/claw/Main.java) 的 `main` 方法运行。

## Skill 与动态 System Prompt

`AgentEngine` 不再硬编码整段 System Prompt，而是在每次 `run` 初始化上下文时调用 `PromptComposer` 动态组装：

1. 核心身份与最小纪律。
2. 当前 WorkDir 下的 `AGENTS.md` 项目专属指南。
3. 当前 WorkDir 下 `.claw/skills/**/SKILL.md` 中定义的标准化技能。

Skill 文件使用 YAML Frontmatter 描述触发条件，例如：

```markdown
---
name: git-workflow
description: 当人类用户要求你“提交代码”、“保存变更”或执行 Git 相关操作时，必须使用此技能。
---

# 提交流程 SOP
...
```

本仓库提供了一个隔离的 `workspace` 测试目录，可用下面命令验证 prompt 组装是否正常：

```bash
mvn -q "-Dmain.class=lab.agentharness.claw.SkillPromptSmokeTest" exec:java
```

如需让真实模型在 `workspace` 中执行示例任务，可显式追加 `--run-agent`。这个入口会初始化真实 Provider、ToolRegistry、AgentEngine，并注入 `TerminalReporter`。不传 prompt 时默认创建 `ping.java`：

```bash
mvn -q "-Dmain.class=lab.agentharness.claw.SkillPromptSmokeTest" -Dexec.args="--run-agent" exec:java
```

也可以直接传入自定义任务，例如让模型创建 `ping.go`：

```bash
mvn -q "-Dmain.class=lab.agentharness.claw.SkillPromptSmokeTest" -Dexec.args="--run-agent 我需要在当前目录下新建一个 ping.go，提供一个简单的 http ping 接口。写完之后，帮我把代码用 git 提交一下。" exec:java
```

## Session 与 Working Memory

`AgentEngine` 现在同时支持两种运行方式：

1. 固定工作区模式：`run(String, Reporter)` 仍然适合单次 CLI Demo，工作区和工具注册表在构造引擎时确定。
2. Session 模式：`run(Session, Reporter)` 从 `Session` 中恢复最近 20 条消息作为候选上下文，并按 `Session.workDir()` 动态组装 `PromptComposer` 与工具注册表。

Session 模式下，外层入口需要先把用户消息写入会话：

```java
Session session = SessionManager.GLOBAL.getOrCreate("chat_front_001", frontDir);
session.append(Schema.Message.user("帮我看看 README.md 里记录了什么密钥？"));
engine.run(session, new TerminalReporter());
```

`Session.getWorkingMemory(20)` 不会直接返回全量历史，而是从尾部截取最近消息。若窗口开头正好是工具 Observation，会自动丢弃这个孤儿消息，避免大模型 API 因工具调用历史不连续而拒绝请求。真正发给模型前，还会再经过 `Compactor`，保护最近 6 条消息并压缩早期大输出。

可以用真实 Provider 跑并发隔离测试：

```bash
mvn -q "-Dmain.class=lab.agentharness.claw.SessionMemorySmokeTest" exec:java
```

这个入口会生成两个本地工作区：`workspace_sessions/project_front` 和 `workspace_sessions/project_back`。前端 Session 会先读取 `README.md` 中的 `token_12345`，再用多轮闲聊把它挤出 Working Memory；后端 Session 会同时运行并验证自己看不到前端 Session 的历史。

## Recovery 错误自愈

`RecoveryManager` 用来把工具失败从“裸错误”升级成“带下一步行动建议的 Observation”。它不会吞掉错误，也不会替模型重试，而是在 `AgentEngine` 收到失败的 `ToolResult` 后，根据工具名和错误文本注入 `[系统救援指南]`。

当前覆盖的典型场景：

1. `edit_file` 的 `old_text` 不匹配：提示先 `read_file` 获取最新内容后再编辑。
2. `edit_file` 命中多处：提示增加上下文代码，确保唯一匹配。
3. `read_file` / `write_file` 路径错误或越界：提示先用 `bash` 查看目录结构。
4. `bash` 命令不存在、语法错误、超时或非 0 退出：提示换用当前系统可用命令，或先做更小范围检查。

可以用真实 Provider 跑自愈测试：

```bash
mvn -q "-Dmain.class=lab.agentharness.claw.RecoverySmokeTest" exec:java
```

这个入口会在 `workspace/auth.java` 写入一个带复杂缩进的测试文件，然后诱导模型先用错误的 `old_text` 调用 `edit_file`。失败结果会带上 `[系统救援指南]`，模型随后应转向读取文件并重新编辑。

## Plan Mode 长程任务

Plan Mode 用于处理“完整项目功能”“多步骤重构”这类容易跨轮次、跨进程丢状态的任务。开启后，`PromptComposer` 会在 System Prompt 中追加计划模式纪律：

1. Agent 被唤醒后必须先用工具检查工作区是否存在 `PLAN.md` 和 `TODO.md`。
2. 如果不存在，先创建 `PLAN.md` 写清需求理解、架构设计、技术选型，再创建 `TODO.md` 拆解 Markdown Checkbox 任务。
3. 如果已经存在，绝对不能覆盖，必须读取两份文件并从第一个 `- [ ]` 继续。
4. 每完成一个 TODO 子任务，必须立刻把对应行改成 `- [x]`，不能最后统一打勾。

Java 入口通过构造引擎时传入 `planMode=true` 开启：

```java
AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, false, true);
```

可以用真实 Provider 在隔离工作区 `workspace_plan` 测试。第一次建议加 `--reset` 清理旧计划文件：

```bash
mvn -q "-Dmain.class=lab.agentharness.claw.PlanModeSmokeTest" -Dexec.args="--reset --prompt 帮我实现一个完整的安卓登录相关代码功能" exec:java
```

后续如果想模拟断点续传，不加 `--reset` 再运行一次即可：

```bash
mvn -q "-Dmain.class=lab.agentharness.claw.PlanModeSmokeTest" -Dexec.args="--prompt 继续完成上一次安卓登录功能任务" exec:java
```

`workspace_plan` 已加入 `.gitignore`，它是本地真实模型演练区，不会进入主仓库提交。

Plan Mode 的单次运行轮数上限比普通模式更高，但仍然有边界：普通任务默认 12 轮，Plan Mode 默认 32 轮。大项目如果一次没做完，测试入口会暂停并保留 `PLAN.md` / `TODO.md`，下一次运行会继续读取文件恢复任务。

## Compactor 内存压缩

`Compactor` 使用字符数量估算上下文压力，避免为了精确 token 计算引入复杂 BPE tokenizer 依赖。当前默认水位线是 3000 字符，并保护最近 6 条消息：

1. `System Prompt` 永远保留，不做压缩。
2. 远期工具 Observation 如果过长，会被替换成长度提示，避免旧日志反复占用模型窗口。
3. 近期工具 Observation 即使处于保护区，单条超过 1000 字符也会保留头尾各 500 字符，中间截断。
4. 远期 Assistant 长回复会折叠，但不会修改 `toolCalls`，保证工具调用链路仍然完整。

压缩只作用于本轮即将发送给 Provider 的临时上下文；`Session` 中保存的历史仍然是完整原文，后续可以再接 JSONL 持久化或更高级摘要策略。

可以用真实 Provider 跑大日志压缩测试：

```bash
mvn -q "-Dmain.class=lab.agentharness.claw.CompactorSmokeTest" exec:java
```

这个入口会在项目根目录生成一个 4000 字符的 `mock_log.txt`，要求 Agent 使用 `read_file` 读取它。工具结果写回 Session 后，下一轮推理前会触发 `Compactor` 日志，验证大 Observation 被截断后再交给模型。

## 飞书长连接机器人

项目已经接入飞书官方 Java SDK：

```xml
<dependency>
    <groupId>com.larksuite.oapi</groupId>
    <artifactId>oapi-sdk</artifactId>
    <version>2.8.3</version>
</dependency>
```

飞书模式使用 SDK 的 WebSocket 长连接能力，本地进程主动连接飞书开放平台，因此不需要配置本地公网 URL，也不需要内网穿透。飞书后台需要开启机器人能力、事件订阅的长连接模式，并订阅接收消息事件。

`.env.local` 中增加飞书配置：

```bash
FEISHU_APP_ID=cli_xxx
FEISHU_APP_SECRET=你的 app secret
FEISHU_VERIFY_TOKEN=你的 Verification Token
FEISHU_ENCRYPT_KEY=你的 Encrypt Key
```

`FEISHU_VERIFY_TOKEN` 和 `FEISHU_ENCRYPT_KEY` 对应飞书事件订阅里的 Verification Token 与 Encrypt Key。长连接模式虽然不需要公网回调地址，但 SDK 内部仍会用事件 dispatcher 解析和校验入站事件；如果飞书后台开启了事件加密，必须配置 Encrypt Key。

启动飞书长连接入口：

```bash
mvn -q -Dmain.class=lab.agentharness.claw.FeishuMain exec:java
```

启动成功后，终端会打印机器人身份和长连接状态。之后在飞书里给机器人发消息，`FeishuBot` 会为当前会话创建 `FeishuReporter`，并把慢思考、工具调用、工具结果和最终回复发回飞书。

注意：如果改用 HTTP Webhook 模式，才需要配置类似 `/webhook/event` 的公网回调地址；当前长连接方案没有这个地址。

## Provider 冒烟测试

真实模型调用不走 Mock，可以用 `ProviderSmokeTest`：

```bash
# DeepSeek，默认 model=deepseek-v4-flash
set DEEPSEEK_API_KEY=你的 key
set CLAW_PROVIDER=deepseek
mvn -q -Dmain.class=lab.agentharness.claw.ProviderSmokeTest -Dexec.args="北京天气怎么样？" exec:java

# 智谱 OpenAI-compatible，默认 model=glm-4-flash
set ZHIPU_API_KEY=你的 key
set CLAW_PROVIDER=zhipu
mvn -q -Dmain.class=lab.agentharness.claw.ProviderSmokeTest -Dexec.args="你好，介绍一下你自己" exec:java
```

也可以通过环境变量覆盖模型名：

```bash
set DEEPSEEK_MODEL=deepseek-v4-flash
set ZHIPU_MODEL=glm-4-flash
```

## 真实 Provider 慢思考对比

用同一个真实 Provider 和同一个 mock 天气工具，分别跑关闭/开启慢思考：

```bash
set DEEPSEEK_API_KEY=你的 key
set CLAW_PROVIDER=deepseek
mvn -q -Dmain.class=lab.agentharness.claw.ProviderThinkingCompare exec:java
```

默认任务是：

```text
我想去北京跑步，帮我查查天气适合吗？
```

对比入口会跑两次：

- `enableThinking = false`：模型直接在 Action 阶段看到工具。
- `enableThinking = true`：模型先在没有工具的 Thinking 阶段规划，再恢复工具执行。

## 后续练习

- 更真实的 ReAct 响应解析。
- OpenAI / Claude Provider 适配。
- 更完整的 JSON Schema 和参数校验。
- Bash 工具审批与危险命令拦截。
- Token 水位监控与上下文压缩。
