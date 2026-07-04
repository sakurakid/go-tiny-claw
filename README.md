# go-tiny-claw

一个用 Java 编写的 Agent Harness 练习 Demo。

这个仓库参考 tiny-claw / claw 风格的 Go 项目结构，但实现语言使用 Java。当前目标不是做完整框架，而是用尽量少的代码跑通 Harness 最核心的几件事：统一 Schema、Provider 抽象、Tool Registry、Main Loop，以及慢思考模式。

## 项目结构

```text
go-tiny-claw/
├── pom.xml
├── README.md
├── docs/
│   └── main-loop-and-schema.md
└── src/main/java/lab/agentharness/
    ├── claw/
    │   ├── Main.java              # Demo 入口，装配 MockProvider、ToolRegistry、AgentEngine
    │   └── ProviderSmokeTest.java # 真实 Provider 冒烟测试入口
    ├── engine/
    │   └── AgentEngine.java       # Main Loop / ReAct 核心循环
    ├── provider/
    │   ├── AnthropicCompatibleProvider.java # Anthropic-compatible 协议适配器
    │   ├── LLMProvider.java                 # LLM Provider 接口
    │   ├── MockProvider.java                # 本地 Mock，模拟 Thinking 与 Action
    │   └── OpenAICompatibleProvider.java    # OpenAI-compatible 协议适配器
    ├── schema/
    │   └── Schema.java            # 统一消息、工具调用、工具结果、工具定义
    └── tools/
        ├── Registry.java          # 工具注册表接口
        └── ToolRegistry.java      # 工具注册与分发，内含 demo 工具
```

## 当前实现

- `Schema` 定义系统统一血液：`Message / ToolCall / ToolResult / ToolDefinition / RawJson`。
- `LLMProvider` 抽象大模型调用：`generate(messages, availableTools)`。
- `Registry` 抽象工具注册与分发：`getAvailableTools()` 和 `execute(call)`。
- `AgentEngine` 维护 ReAct 主循环：Reasoning -> Action -> Observation。
- `enableThinking` 支持慢思考模式：先剥夺工具规划，再恢复工具执行。
- `OpenAICompatibleProvider` 支持 DeepSeek / 智谱等 OpenAI-compatible 服务。
- `AnthropicCompatibleProvider` 支持 Claude / 兼容 Anthropic Messages API 的服务。

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
2. `Main` 初始化 `MockProvider` 和 `ToolRegistry`。
3. `AgentEngine` 创建 `contextHistory`，写入 system message 和 user message。
4. Turn 1 Thinking：`MockProvider` 输出内部规划。
5. Turn 1 Action：`MockProvider` 请求调用 `bash` 列目录。
6. `ToolRegistry` 执行命令，返回 `ToolResult`。
7. `AgentEngine` 把工具结果作为 Observation 写回上下文，并保留 `ToolCallID`。
8. Turn 2 Thinking：`MockProvider` 根据 Observation 规划总结。
9. Turn 2 Action：`MockProvider` 返回最终文本，任务结束。

## 运行方式

```bash
mvn clean package
mvn exec:java
```

也可以直接运行 jar：

```bash
java -jar target/go-tiny-claw-0.1.0-SNAPSHOT.jar
```

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

## 后续练习

- 更真实的 ReAct 响应解析。
- OpenAI / Claude Provider 适配。
- 更完整的 JSON Schema 和参数校验。
- Bash 工具审批与危险命令拦截。
- Token 水位监控与上下文压缩。
