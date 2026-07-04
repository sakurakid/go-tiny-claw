# Main Loop 与 Schema 设计笔记

## 为什么先定义 Schema

在 Agent Harness 里，真正流动的数据不是某个框架对象，而是上下文。大模型、工具、主循环之间每一轮都在交换消息、工具调用和工具结果。

市面上的模型 API 格式并不统一。Claude、OpenAI 兼容模型、本地模型都可能有自己的 message 和 tool call 结构。为了让 go-tiny-claw 内部保持稳定，需要先定义一套自己的标准数据结构。

这个标准数据结构就是项目里的 `schema/Schema.java`。

## Schema 是系统的统一血液

当前 Java 版把 Go 示例里的 `message.go` 收敛到一个文件里：

```text
src/main/java/lab/agentharness/schema/Schema.java
```

它包含：

- `Role`：消息角色，区分 system / user / assistant。
- `Message`：上下文中的单条消息。
- `ToolCall`：模型请求调用的工具。
- `ToolResult`：工具执行后的物理结果。
- `ToolDefinition`：暴露给模型看的工具定义。
- `RawJson`：Java 版 `json.RawMessage`。

## RawJson 的意义

Go 版本里使用 `json.RawMessage`，目的是延迟解析工具参数。Java 版用 `Schema.RawJson` 表达同样的思想。

Main Loop 只知道模型要调用哪个工具，以及参数是一段 JSON：

```java
new Schema.ToolCall(
        "call_readme_001",
        "read_file",
        Schema.RawJson.of("{\"path\":\"README.md\"}"));
```

Main Loop 不关心 `read_file` 需要 `path`，也不关心 `bash` 需要 `command`。具体参数由具体工具自己解析。

这样可以保持解耦：

- Main Loop 负责调度。
- Provider 负责模型适配。
- Tool Registry 负责分发。
- Tool 负责理解自己的参数。

## 当前 Main Loop 流程

当前 Demo 的流程是：

```text
接收用户任务
  ↓
初始化 Context：system message + user message
  ↓
调用 Provider
  ↓
解析 Provider 返回的 Schema.Message
  ↓
如果没有 ToolCall：返回最终结果
  ↓
如果有 ToolCall：交给 ToolRegistry 执行
  ↓
把 ToolResult 作为 Observation 写回上下文
  ↓
进入下一轮 Turn
```

这对应 ReAct 的最小循环：

```text
Reasoning -> Action -> Observation -> Reasoning
```

## 为什么保持目录简单

这个仓库现在只是练习 Demo，不需要提前做复杂封装。当前保留五个核心位置：

- `claw`：入口。
- `engine`：主循环。
- `provider`：模型接口。
- `schema`：统一协议。
- `tools`：工具注册与分发。

等这条链路跑通之后，再考虑拆出 context、memory、middleware、feishu 等模块会更自然。
