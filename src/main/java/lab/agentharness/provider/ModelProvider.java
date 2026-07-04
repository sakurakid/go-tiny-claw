package lab.agentharness.provider;

import java.util.List;

import lab.agentharness.schema.Schema;

/**
 * 大模型适配器抽象，用统一 Schema 屏蔽 OpenAI、Claude 或本地 Mock 的 API 差异。
 */
public interface ModelProvider {
    String name();

    Schema.Message complete(List<Schema.Message> messages, List<Schema.ToolDefinition> tools);
}
