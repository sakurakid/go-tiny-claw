package lab.agentharness.provider;

/**
 * 大模型适配器抽象，用来屏蔽 OpenAI、Claude 或本地 Mock 等不同 Provider 的调用差异。
 */
public interface ModelProvider {
    String name();

    ModelResponse complete(AgentRequest request);
}
