# Main Loop 与 Schema 设计笔记

## 1. 统一血液：Schema

在 Agent Harness 里，真正流动的数据不是某个框架对象，而是上下文。大模型、工具、主循环之间每一轮都在交换消息、工具调用和工具结果。

不同模型 API 格式并不统一。Claude、OpenAI 兼容模型、本地模型都可能有自己的 message 和 tool call 结构。为了让 go-tiny-claw 内部保持稳定，我们先定义自己的标准数据结构：

```text
src/main/java/lab/agentharness/schema/Schema.java
```

当前包含：

- `Role`：消息角色，区分 system / user / assistant。
- `Message`：上下文中的单条消息。
- `ToolCall`：模型请求调用的工具。
- `ToolResult`：工具执行后的物理结果。
- `ToolDefinition`：暴露给模型看的工具定义。
- `RawJson`：Java 版 `json.RawMessage`。

`RawJson` 的核心意义是延迟解析。Main Loop 只知道模型要调用哪个工具，以及参数是一段 JSON；具体字段由具体工具自己解析。

## 2. 抽象 Provider 和 Tool 接口

在写 Main Loop 的循环之前，需要先抽出两个边界：

- 去哪里调用大模型。
- 去哪里执行工具。

Java 版 Provider 接口：

```java
public interface LLMProvider {
    String name();

    Schema.Message generate(
            List<Schema.Message> messages,
            List<Schema.ToolDefinition> availableTools);
}
```

Java 版工具注册表接口：

```java
public interface BaseTool {
    String name();

    Schema.ToolDefinition definition();

    String execute(Schema.RawJson arguments) throws Exception;
}
```

```java
public interface Registry {
    void register(BaseTool tool);

    List<Schema.ToolDefinition> getAvailableTools();

    Schema.ToolResult execute(Schema.ToolCall call);
}
```

`BaseTool` 是具体工具的接口，`Registry` 是动态挂载和路由分发的接口。这样 `AgentEngine` 不知道背后是真实 LLM SDK、MockProvider、本地模型，还是哪种工具注册表。它只依赖接口和 WorkDir：

```java
public AgentEngine(LLMProvider provider, Registry registry, Path workDir, boolean enableThinking)
```

## 3. 最终 Main Loop

`engine/AgentEngine.java` 对齐 Go 示例里的 `AgentEngine`：

- `provider`：大模型接口，对应大脑。
- `registry`：工具注册表接口，对应手脚。
- `workDir`：工作区物理边界，避免 Agent 没有活动范围。
- `enableThinking`：慢思考模式开关。

`run(userPrompt)` 做的事情：

1. 打印引擎启动日志，并锁定 `workDir`。
2. 初始化 `contextHistory`，写入 system message 和 user message。
3. 进入 while 循环，并用 `MAX_TURNS` 防止 Doom Loop。
4. 每轮获取 `registry.getAvailableTools()`。
5. 如果开启慢思考，先进入 Thinking Phase。
6. 恢复工具后进入 Action Phase。
7. 如果模型没有工具调用，说明任务完成，退出循环。
8. 如果模型请求工具调用，为每个 `ToolCall` 启动一个并发任务执行 `registry.execute(toolCall)`。
9. 所有工具执行完成后，按模型原始 ToolCall 顺序聚合 Observation。
10. 将工具返回封装成 `Schema.Message.observation(toolCall.id(), result.output())` 写回上下文。

Observation 必须携带 `ToolCallID`。它是模型在下一轮推理时关联“刚才那个 Action”和“工具返回结果”的线索。

并发执行只改变物理工具的执行方式，不改变上下文时间线。`AgentEngine` 会先预留一批固定顺序的 Observation 槽位，每个异步任务只写入自己的索引位置，所有任务完成后再一次性追加到 `contextHistory`。这样可以并发读取多个文件，同时保持模型看到的 Observation 顺序稳定。

当前 system message 还加入了一条轻量 Harness 边界：如果用户没有明确要求，不安装软件、不下载远程产物、不修改系统级配置。真实测试中模型发现本机缺 Go 后曾尝试安装 Go，这正是 bash 能力过强时容易出现的边界扩张；所以缺运行时应该如实报告，而不是自行安装。

## 4. 慢思考模式

慢思考模式把一轮 Turn 拆成两个阶段。

Phase 1：Thinking

```java
Schema.Message thinkResp = provider.generate(thinkingContext(contextHistory), List.of());
```

这里传入空工具列表，相当于剥夺工具访问权。`thinkingContext` 会额外注入一条运行时提醒：工具只是暂时隐藏，不代表系统没有工具；当前阶段只能规划，不要编造工具名、命令、API 调用、观察结果或最终答案。

Phase 2：Action

```java
Schema.Message actionResp = provider.generate(contextHistory, availableTools);
```

这时恢复工具列表。模型会顺着刚才追加到上下文里的 thinking trace，决定是否发起工具调用。

当前 `MockProvider` 的验证逻辑是：

- 如果 `availableTools` 为空，返回一段内部思考。
- 如果 `availableTools` 非空且还没有 Observation，返回一个 `read_file` ToolCall。
- 如果已经有 Observation，返回最终结果。

这让 demo 能展示完整链路：

```text
Thinking -> Action -> Observation -> Thinking -> Final Answer
```

## 5. 为什么保持目录简单

这个仓库现在只是练习 Demo，不需要提前做复杂封装。当前保留五个核心位置：

- `claw`：入口。
- `engine`：主循环。
- `provider`：模型接口。
- `schema`：统一协议。
- `tools`：工具注册与分发。

## 6. 动态 Registry 与极简工具集

这一步开始，工具层不再把 demo 工具藏在注册表内部，而是拆成独立工具类：

```text
src/main/java/lab/agentharness/tools/BaseTool.java
src/main/java/lab/agentharness/tools/ToolRegistry.java
src/main/java/lab/agentharness/tools/ReadFileTool.java
src/main/java/lab/agentharness/tools/WriteFileTool.java
src/main/java/lab/agentharness/tools/EditFileTool.java
src/main/java/lab/agentharness/tools/BashTool.java
```

`ToolRegistry` 只做三件事：

1. `register(tool)`：按工具名动态挂载工具。
2. `getAvailableTools()`：把所有工具的 `ToolDefinition` 暴露给 Provider。
3. `execute(call)`：按 `ToolCall.name()` 路由执行，并把异常统一封装成 `ToolResult`。

`ReadFileTool` 是第一个真实物理工具，设计上保留两个 Harness 防线：

- 路径边界：模型只能读取 `WorkDir` 内部文件，不能通过 `../` 跳出工作区。
- 内容截断：最多返回前 8000 字节，避免读取大日志导致上下文失控。

`WriteFileTool` 是改变文件系统的工具：

- 路径边界：写入目标必须仍在 `WorkDir` 内。
- 自动建目录：父目录不存在时自动创建。
- 覆盖写入：当前 demo 采用 create-or-replace，后续可以接入审批或 diff 确认。

`EditFileTool` 是更适合 Coding Agent 的局部修改工具：

- 唯一匹配：`old_text` 精确匹配多处时直接报错，要求模型提供更多上下文。
- 换行容错：将 CRLF 归一化为 LF 后再尝试匹配。
- 首尾空白容错：忽略 `old_text` 首尾空白后重试。
- 缩进容错：逐行 trim 后用滑动窗口寻找唯一代码块。

`edit_file` 的错误信息会原样通过 `ToolResult` 回到模型。比如“匹配到了多处”或“未找到 old_text”，模型下一轮可以先 `read_file`，再提供更精确的上下文重试。

`BashTool` 是改变运行环境的工具：

- 工作目录绑定：命令默认在 `WorkDir` 内执行。
- 时间预算：最多执行 30 秒，避免模型启动常驻服务后卡死主循环。
- 错误回传：非 0 退出不会让 Java 程序崩溃，而是把 exit code 和输出作为 Observation 返回，让模型自己分析报错并自我修正。
- 输出截断：最多返回前 8000 字节，避免命令输出撑爆上下文。

为什么可以这样做：Harness 不替模型理解 shell 语法，也不在 Java 里重新实现管道、重定向或 `&&`。它只把模型提出的命令交给操作系统的 shell 执行：Windows 下使用 `cmd.exe /c`，macOS/Linux 下使用 `bash -lc`。这样模型获得了组合本地能力的通用接口，而 Harness 负责给这个接口加上工作目录、时间和输出边界。

注意：`BashTool` 的 `workDir` 不是强沙箱。命令本身仍然可能通过 `cd` 或绝对路径访问工作区外部资源。真正的高危命令拦截、目录逃逸审计和人工审批应该放到后续 Middleware/Interceptors 层，而不是让基础工具类无限膨胀。

当前 `Main` 会挂载 `read_file / write_file / edit_file / bash`，开启慢思考，并要求模型在同一轮 Action 中一次性读取 `a.txt / b.txt / c.txt` 三个文件，再综合判断它们分别记录的领域信息。

## 7. 模型接入层：双协议 Provider

Java 侧可以直接使用官方 SDK：

- OpenAI Java SDK：`com.openai:openai-java`
- Anthropic Java SDK：`com.anthropic:anthropic-java`

当前 demo 为了保持简单，没有先绑定 SDK 类型，而是实现了两个轻量协议适配器：

- `OpenAICompatibleProvider`：适配 OpenAI Chat Completions 兼容协议。
- `AnthropicCompatibleProvider`：适配 Anthropic Messages 兼容协议。

这样做的原因是：我们项目内部已经有了统一的 `Schema.Message / ToolCall / ToolDefinition`，Provider 层只需要做双向翻译：

```text
go-tiny-claw Schema -> 厂商 API Request
厂商 API Response -> go-tiny-claw Schema
```

OpenAI-compatible 当前支持：

- DeepSeek：`DEEPSEEK_API_KEY`，默认模型 `deepseek-v4-flash`
- 智谱：`ZHIPU_API_KEY`，默认模型 `glm-4-flash`

Anthropic-compatible 当前预留：

- Claude：`ANTHROPIC_API_KEY`
- 智谱 Claude-compatible：`ZHIPU_API_KEY`

冒烟测试入口：

```text
src/main/java/lab/agentharness/claw/ProviderSmokeTest.java
```

它不挂载工具，只发起一次纯模型请求，适合验证 key、base url、model 和消息翻译是否正常。

真实 Provider 的慢思考对比入口：

```text
src/main/java/lab/agentharness/claw/ProviderThinkingCompare.java
```

它挂载一个伪造的 `get_weather` 工具，用真实模型分别运行：

- `enableThinking = false`
- `enableThinking = true`

这样可以观察真实模型在“直接看到工具”和“先被剥夺工具进行规划”两种模式下，是否都会正确发起工具调用并利用 Observation 给出最终建议。

## 8. Reporter 与飞书消息承接层

早期 demo 直接在 `AgentEngine` 里 `System.out.println` 模型回复和工具状态，这只适合本地 CLI，不适合接入飞书、钉钉或 WebUI。现在引擎新增了展示层抽象：

```text
src/main/java/lab/agentharness/engine/Reporter.java
```

`Reporter` 定义四个事件：

- `onThinking()`：模型进入慢思考阶段。
- `onToolCall(toolName, args)`：模型决定调用工具。
- `onToolResult(toolName, result, isError)`：工具执行完毕。
- `onMessage(content)`：模型输出阶段性文本或最终回复。

`AgentEngine.run(userPrompt, reporter)` 会在主循环关键节点触发这些事件。Observation 仍然以完整内容写回模型上下文，但 Reporter 层只展示给人类看的摘要，避免大文件或长命令输出刷爆飞书消息。

本地 CLI 使用：

```text
src/main/java/lab/agentharness/engine/TerminalReporter.java
```

飞书模式使用：

```text
src/main/java/lab/agentharness/feishu/FeishuBot.java
src/main/java/lab/agentharness/feishu/FeishuReporter.java
src/main/java/lab/agentharness/claw/FeishuMain.java
```

`FeishuBot` 使用飞书官方 Java SDK 的 `LarkChannel` WebSocket 长连接能力监听消息。本地进程主动连到飞书开放平台，因此不需要公网回调地址。收到消息后，Bot 会把任务投递到独立线程，避免阻塞 SDK 的事件处理线程；每个会话创建一个 `FeishuReporter`，再调用同一个 `AgentEngine` 执行任务。

飞书后台的 Verification Token 和 Encrypt Key 也会被读取到 `LarkChannelOptions.WebhookOptions`。长连接模式不暴露 HTTP 回调地址，但 SDK 内部仍然复用事件 dispatcher 解析入站事件；当飞书事件订阅启用加密时，Encrypt Key 是必要配置。

这个拆分让引擎保持纯粹：它只知道“该报告什么事件”，不知道事件最后是打印到终端、发到飞书，还是渲染到 Web 页面。

## 9. PromptComposer 与 SkillLoader

早期 `AgentEngine` 直接硬编码 System Prompt。随着项目开始接入飞书、项目规范和可插拔技能，这种写法会让主循环越来越臃肿。因此新增上下文组装模块：

```text
src/main/java/lab/agentharness/context/PromptComposer.java
src/main/java/lab/agentharness/context/SkillLoader.java
src/main/java/lab/agentharness/context/Skill.java
```

`PromptComposer` 每次在 `AgentEngine.run` 开始时动态生成系统消息，顺序是：

1. 最小核心身份和纪律。
2. 工作区 `AGENTS.md` 中的项目专属指南。
3. 工作区 `.claw/skills/**/SKILL.md` 中的标准化技能。

`SkillLoader` 会递归扫描 `.claw/skills`，只处理文件名为 `SKILL.md` 的 Markdown 文件。每个技能支持 YAML Frontmatter：

```markdown
---
name: git-workflow
description: 当人类用户要求你“提交代码”、“保存变更”或执行 Git 相关操作时，必须使用此技能。
---
```

解析后，技能会以“技能名称 / 触发条件 / 执行指南”的结构注入 System Prompt。这样模型在看到用户任务时，可以根据 description 选择是否遵循对应技能正文，而不是把所有专项规则写死在引擎里。

本地提供了 `workspace` 目录作为隔离测试 WorkDir，并提供 `SkillPromptSmokeTest` 验证 prompt 组装结果。默认模式只检查和打印 System Prompt，不触发真实模型写文件；追加 `--run-agent` 才会用真实 Provider 执行示例任务。
