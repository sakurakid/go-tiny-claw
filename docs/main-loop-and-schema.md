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
public interface Registry {
    List<Schema.ToolDefinition> getAvailableTools();

    Schema.ToolResult execute(Schema.ToolCall call);
}
```

这样 `AgentEngine` 不知道背后是真实 LLM SDK、MockProvider、本地模型，还是哪种工具注册表。它只依赖接口和 WorkDir：

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
8. 如果模型请求工具调用，逐个执行 `registry.execute(toolCall)`。
9. 将工具返回封装成 `Schema.Message.observation(toolCall.id(), result.output())` 写回上下文。

Observation 必须携带 `ToolCallID`。它是模型在下一轮推理时关联“刚才那个 Action”和“工具返回结果”的线索。

## 4. 慢思考模式

慢思考模式把一轮 Turn 拆成两个阶段。

Phase 1：Thinking

```java
Schema.Message thinkResp = provider.generate(contextHistory, List.of());
```

这里传入空工具列表，相当于剥夺工具访问权。模型看不到任何工具 Schema，只能输出规划或思考过程。

Phase 2：Action

```java
Schema.Message actionResp = provider.generate(contextHistory, availableTools);
```

这时恢复工具列表。模型会顺着刚才追加到上下文里的 thinking trace，决定是否发起工具调用。

当前 `MockProvider` 的验证逻辑是：

- 如果 `availableTools` 为空，返回一段内部思考。
- 如果 `availableTools` 非空且还没有 Observation，返回一个 `bash` ToolCall。
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

## 6. 模型接入层：双协议 Provider

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
