package lab.agentharness.provider;

/**
 * 模型 Provider 返回的结果封装，后续可扩展为 tool call、token usage 和 finish reason。
 */
public record ModelResponse(String content) {
}
