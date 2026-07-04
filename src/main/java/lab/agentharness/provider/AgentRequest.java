package lab.agentharness.provider;

import java.util.List;

import lab.agentharness.tools.ToolSpec;

public record AgentRequest(
        String task,
        String systemPrompt,
        List<ToolSpec> toolSpecs,
        String memorySnapshot,
        String thinkingInstruction) {
}
