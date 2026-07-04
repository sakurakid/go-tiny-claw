package lab.agentharness.provider;

import java.util.List;

import lab.agentharness.schema.Schema;

/**
 * LLMProvider 定义与大模型通信的统一契约，隔离 Claude、OpenAI 或本地 Mock 的底层差异。
 */
public interface LLMProvider {
    String name();

    /**
     * 接收当前上下文历史和可用工具列表，并发起一次模型推理。
     * availableTools 为空时表示 Thinking Phase，模型只能输出规划，不应请求工具。
     */
    Schema.Message generate(List<Schema.Message> messages, List<Schema.ToolDefinition> availableTools);
}
