package lab.agentharness.context;

import java.util.List;

import lab.agentharness.tools.ToolSpec;

/**
 * 一次运行时上下文快照，承载系统提示词、工具列表、外部记忆和 token 估算结果。
 */
public record RuntimeContext(
        String systemPrompt,
        List<ToolSpec> toolSpecs,
        String memorySnapshot,
        int estimatedTokens) {
}
