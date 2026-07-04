# go-tiny-claw

一个用 Java 编写的 Agent Harness 练习 Demo。

这个仓库参考 tiny-claw / claw 风格的 Go 项目结构，但实现语言使用 Java。当前目标不是做一个完整框架，而是用最少代码跑通 Harness 最核心的几件事：统一上下文协议、Main Loop、Provider 抽象、Tool Registry。

## 当前重点

这一步先实现系统的统一血液：`schema`。

在 Harness 里，大模型、工具、主循环之间传递的数据都应该走同一套标准结构。不同模型厂商的 API 可以各不相同，但进入 go-tiny-claw 内部之后，都要被转换成自己的 `Message / ToolCall / ToolResult / ToolDefinition`。

这样做的好处是：

- Main Loop 不绑定 OpenAI 或 Claude 的具体 API 格式。
- 工具参数用 `RawJson` 原样传递，Main Loop 不解析具体参数。
- Provider 只负责把模型输出转换成内部 `Schema.Message`。
- Tool Registry 只负责把 `ToolCall` 分发给具体工具。

## 项目结构

```text
go-tiny-claw/
├── pom.xml
├── README.md
├── docs/
│   └── main-loop-and-schema.md
└── src/main/java/lab/agentharness/
    ├── claw/
    │   └── Main.java              # Demo 入口，装配 MockProvider、ToolRegistry、Loop
    ├── engine/
    │   └── Loop.java              # Main Loop / ReAct 核心循环
    ├── provider/
    │   ├── ModelProvider.java     # LLM Provider 接口
    │   └── MockProvider.java      # 本地 Mock，模拟工具调用
    ├── schema/
    │   └── Schema.java            # 统一消息、工具调用、工具结果、工具定义
    └── tools/
        └── ToolRegistry.java      # 工具注册与分发，内含 demo 工具
```

## 运行方式

```bash
mvn clean package
mvn exec:java
```

也可以直接运行 jar：

```bash
java -jar target/go-tiny-claw-0.1.0-SNAPSHOT.jar
```

## 当前 Demo 会做什么

1. `Main` 初始化 `MockProvider` 和 `ToolRegistry`。
2. `Loop` 创建系统消息和用户任务。
3. `MockProvider` 第一轮返回一个 `read_file` 的 `ToolCall`。
4. `Loop` 不解析参数，只把 `RawJson` 交给 `ToolRegistry`。
5. `ToolRegistry` 执行读取 `README.md`，返回 `ToolResult`。
6. `Loop` 把工具结果作为 Observation 写回上下文。
7. `MockProvider` 第二轮返回最终文本，任务结束。

## 后续练习

接下来可以继续补：

- 更真实的 ReAct 响应解析。
- OpenAI / Claude Provider 适配。
- 更完整的 JSON Schema 和参数校验。
- Bash 工具审批与危险命令拦截。
- Token 水位监控与上下文压缩。
