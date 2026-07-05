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
    │   ├── FeishuMain.java        # 飞书长连接启动入口，适合本地开发接入机器人
    │   ├── Main.java              # Demo 入口，装配真实 Provider、ToolRegistry、AgentEngine
    │   ├── ProviderThinkingCompare.java # 真实 Provider 慢思考对比入口
    │   ├── ProviderSmokeTest.java # 真实 Provider 冒烟测试入口
    │   └── SkillPromptSmokeTest.java # PromptComposer 与 SkillLoader 冒烟测试入口
    ├── context/
    │   ├── PromptComposer.java    # 动态组装 System Prompt
    │   ├── Skill.java             # 标准化技能结构
    │   └── SkillLoader.java       # 扫描并解析 .claw/skills/**/SKILL.md
    ├── engine/
    │   ├── AgentEngine.java       # Main Loop / ReAct 核心循环
    │   ├── Reporter.java          # 引擎向外部展示层输出状态的接口
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
- `Reporter` 抽象引擎输出：`onThinking / onToolCall / onToolResult / onMessage`。
- `PromptComposer` 动态组装 System Prompt：核心纪律、`AGENTS.md` 和 `.claw/skills/**/SKILL.md`。
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

如需让真实模型在 `workspace` 中执行示例任务，可显式追加 `--run-agent`：

```bash
mvn -q "-Dmain.class=lab.agentharness.claw.SkillPromptSmokeTest" -Dexec.args="--run-agent" exec:java
```

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
