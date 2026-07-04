package lab.agentharness.provider;

import java.util.List;

import lab.agentharness.tools.ToolSpec;

/**
 * 发送给模型 Provider 的完整请求快照，包含任务、系统提示词、工具说明和外部化记忆。
 */
public record AgentRequest(
        String task,
        String systemPrompt,
        List<ToolSpec> toolSpecs,
        String memorySnapshot,
        String thinkingInstruction) {
}
