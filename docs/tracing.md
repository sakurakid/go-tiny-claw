# Tracing 链路追踪

Tracing 用来把一次 Agent 执行过程保存成一棵可以回放的决策树。它记录 Run、Turn、模型调用和工具调用的层级关系、耗时和关键元数据，方便排查“模型为什么这么决策”“哪个工具最慢”“哪一轮上下文最大”。

## 为什么 Java 版不用 Context

Go 版本可以通过 `context.Context` 把当前 Span 往下传。当前 Java 项目的 `LLMProvider` 和 `Registry` 接口没有 Context 参数，所以这里采用轻量的 `ThreadLocal` 保存当前 Span。

普通单线程调用时，`Trace.startSpan(...)` 会自动把新 Span 挂到当前 Span 下；并发工具执行时，线程池不会自动继承 ThreadLocal，因此 `AgentEngine` 会用 `Trace.callWithParent(...)` 显式把当前 Turn Span 传给工具线程。

## Span 树结构

典型输出结构如下：

```text
Agent.Run
  └── Turn-1
      ├── LLM.Action
      ├── Tool.Execute (bash)
      └── Tool.Execute (write_file)
  └── Turn-2
      └── LLM.Action
```

每个 Span 会记录：

- `name`：节点名称，例如 `Agent.Run`、`Turn-1`、`LLM.Action`、`Tool.Execute`。
- `start_time` / `end_time` / `duration_ms`：开始时间、结束时间和耗时。
- `attributes`：调试元数据，例如 session id、工具参数、上下文消息数、token、输出摘要。
- `children`：子 Span 列表。

## 埋点位置

- `AgentEngine.run(...)`：创建根节点 `Agent.Run`，任务结束后导出 trace JSON。
- 每一轮循环：创建 `Turn-N`。
- 每次模型调用：创建 `LLM.Thinking`、`LLM.Action` 或 `LLM.SubagentAction`。
- 每次工具执行：`ToolRegistry.execute(...)` 创建 `Tool.Execute`。
- `CostTracker`：如果当前线程存在 LLM Span，会把 provider、model、token 和估算费用写入 Span 属性。

## 导出位置

每次根 Span 结束后都会导出到当前工作区：

```text
.claw/traces/trace_<sessionId>_<timestamp>.json
```

`TraceSmokeTest` 使用 `workspace` 作为工作区，因此文件会生成在：

```text
workspace/.claw/traces/
```

该目录已加入 `.gitignore`，不会污染提交。

## 运行测试

```powershell
mvn -q "-Dmain.class=lab.agentharness.claw.TraceSmokeTest" exec:java
```

测试入口会要求模型在同一轮 Action 中分别调用：

1. `bash`：模拟耗时的环境检查。
2. `write_file`：创建 `trace_test.md`。

运行完成后，可以打开最新的 JSON 文件，检查是否存在 `Agent.Run -> Turn -> LLM.Action -> Tool.Execute` 的决策树，以及两个工具 Span 是否平行挂在同一个 Turn 下。
