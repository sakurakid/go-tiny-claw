package lab.agentharness.context;

import java.util.List;

import lab.agentharness.tools.ToolSpec;

public record RuntimeContext(
        String systemPrompt,
        List<ToolSpec> toolSpecs,
        String memorySnapshot,
        int estimatedTokens) {
}
