package lab.agentharness.provider;

import java.util.List;

import lab.agentharness.schema.Schema;

/**
 * 本地假模型实现，模拟“先调用工具，再根据 Observation 返回最终答案”的 ReAct 行为。
 */
public final class MockProvider implements LLMProvider {
    @Override
    public String name() {
        return "mock-provider";
    }

    @Override
    public Schema.Message generate(List<Schema.Message> messages, List<Schema.ToolDefinition> availableTools) {
        boolean hasObservation = messages.stream().anyMatch(message -> message.toolCallId() != null);
        if (!hasObservation) {
            Schema.ToolCall readReadme = new Schema.ToolCall(
                    "call_readme_001",
                    "read_file",
                    Schema.RawJson.of("{\"path\":\"README.md\"}"));

            return Schema.Message.assistant(
                    "我先读取 README.md，拿到 Observation 后再总结项目当前状态。",
                    List.of(readReadme));
        }

        String observation = messages.stream()
                .filter(message -> message.toolCallId() != null)
                .reduce((first, second) -> second)
                .map(Schema.Message::content)
                .orElse("没有拿到工具返回。");

        return Schema.Message.assistant("""
                基于工具 Observation，这个项目目前是一个 Java 版 Agent Harness 练习 Demo。
                它正在用极简结构验证 Main Loop、Provider、Schema 和 Tool Registry 之间如何传递上下文。

                Observation 摘要：
                %s
                """.formatted(firstLines(observation, 6)));
    }

    private static String firstLines(String text, int maxLines) {
        String[] lines = text.split("\\R");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, maxLines); i++) {
            builder.append(lines[i]).append(System.lineSeparator());
        }
        return builder.toString().stripTrailing();
    }
}
