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
    │   └── Main.java              # Demo 入口，装配 MockProvider、ToolRegistry、AgentEngine
    ├── engine/
    │   └── AgentEngine.java       # Main Loop / ReAct 核心循环
    ├── provider/
    │   ├── LLMProvider.java       # LLM Provider 接口
    │   └── MockProvider.java      # 本地 Mock，模拟工具调用
    ├── schema/
    │   └── Schema.java            # 统一消息、工具调用、工具结果、工具定义
    └── tools/
        ├── Registry.java          # 工具注册表接口
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

1. `Main` 获取当前目录作为 `WorkDir` 物理边界。
2. `Main` 初始化 `MockProvider` 和 `ToolRegistry`。
3. `AgentEngine` 创建系统消息和用户任务，形成 `contextHistory`。
4. `MockProvider` 第一轮返回一个 `bash` 的 `ToolCall`。
5. `AgentEngine` 不解析参数，只把 `RawJson` 交给 `ToolRegistry`。
6. `ToolRegistry` 执行列目录命令，返回 `ToolResult`。
7. `AgentEngine` 把工具结果作为 Observation 写回上下文，并保留 `ToolCallID`。
8. `MockProvider` 第二轮返回最终文本，任务结束。

## 第 2 步：Provider 和 Tool 接口

在进入真正的 for 循环之前，Engine 不能直接依赖某个模型 SDK 或某个具体工具实现，所以当前项目先抽象了两个接口：

- `LLMProvider`：定义 `generate(messages, availableTools)`，负责发起一次模型推理。
- `Registry`：定义 `getAvailableTools()` 和 `execute(call)`，负责提供工具 Schema 并执行模型发起的工具调用。

现在 `AgentEngine` 只依赖这两个接口：

```java
public AgentEngine(LLMProvider provider, Registry registry, Path workDir)
```

这让后续替换真实 OpenAI / Claude Provider，或者扩展新的工具注册表时，不需要改 Main Loop。

## 后续练习

接下来可以继续补：

- 更真实的 ReAct 响应解析。
- OpenAI / Claude Provider 适配。
- 更完整的 JSON Schema 和参数校验。
- Bash 工具审批与危险命令拦截。
- Token 水位监控与上下文压缩。
