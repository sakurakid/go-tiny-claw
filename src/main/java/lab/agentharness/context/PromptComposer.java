package lab.agentharness.context;

import java.util.List;
import java.util.stream.Collectors;

import lab.agentharness.tools.ToolSpec;

public final class PromptComposer {
    public String compose(String task, List<ToolSpec> toolSpecs, String memorySnapshot) {
        String tools = toolSpecs.stream()
                .map(spec -> "- " + spec.name() + ": " + spec.description())
                .collect(Collectors.joining(System.lineSeparator()));

        return """
                你运行在一个极简 Agent Harness 中。
                你负责推理和规划，Harness 负责上下文、工具、状态和安全边界。

                任务：
                %s

                可用工具：
                %s

                外部化记忆：
                %s
                """.formatted(task, tools, memorySnapshot);
    }
}
