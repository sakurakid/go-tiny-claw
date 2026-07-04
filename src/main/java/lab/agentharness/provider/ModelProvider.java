package lab.agentharness.provider;

public interface ModelProvider {
    String name();

    ModelResponse complete(AgentRequest request);
}
