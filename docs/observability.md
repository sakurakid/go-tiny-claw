# Observability 与费用统计

本模块给 Agent 增加一层轻量级仪表盘，用来观察每次大模型调用的耗时、Token 消耗和会话累计费用。

## 设计思路

核心思路是使用 Provider 装饰器，而不是把统计逻辑写进 `AgentEngine`。

`AgentEngine` 仍然只认识 `LLMProvider.generate(...)`。真实 Provider 负责请求大模型并解析原始响应，`CostTracker` 包在真实 Provider 外面，额外做三件事：

1. 记录 API 调用开始和结束时间，计算延迟。
2. 从 `Schema.Message.usage()` 读取输入/输出 Token。
3. 按模型价格估算费用，并累加到当前 `Session`。

这样 Engine 不需要知道“费用”这个概念，后续替换 Provider 或关闭统计也不会影响主循环。

## 数据流

```text
AgentEngine
  -> CostTracker.generate(...)
    -> RealProvider.generate(...)
      -> 调用大模型 API
      -> 从 API 原生 usage 字段提取 token
      -> 返回带 usage 的 Schema.Message
    -> CostTracker 计算耗时和费用
    -> Session.recordUsage(...)
  -> AgentEngine 继续 ReAct 主循环
```

## 关键实现

- `Schema.Usage`：承接大模型返回的 `promptTokens` 和 `completionTokens`。
- `Schema.Message.usage`：让每条 Assistant 响应都可以附带本次模型调用的消耗。
- `OpenAICompatibleProvider`：解析 `usage.prompt_tokens` 和 `usage.completion_tokens`。
- `AnthropicCompatibleProvider`：解析 `usage.input_tokens` 和 `usage.output_tokens`。
- `Session.recordUsage(...)`：线程安全地累计当前会话的总输入 Token、总输出 Token 和人民币估算费用。
- `CostTracker`：实现 `LLMProvider`，作为真实 Provider 的装饰器。
- `ObservabilitySmokeTest`：真实 Provider 冒烟测试入口。

## 运行测试

```powershell
mvn -q "-Dmain.class=lab.agentharness.claw.ObservabilitySmokeTest" exec:java
```

测试入口只挂载 `bash` 工具，让模型执行 `date` 命令。日志中会出现类似信息：

```text
[Tracker] API 调用完成 | 耗时: 1234 ms | 输入: 1000 tk | 输出: 120 tk | 估算费用: CNY 0.001210
[Tracker] 当前会话 (test_observability_001) 累计: 输入 1000 tk | 输出 120 tk | 费用 CNY 0.001210
================ 财务报表 ================
```

当前价格表是演示用硬编码，单位是美元 / 1M tokens，并使用固定汇率换算成人民币。后续可以把它改成配置文件或模型注册表。
