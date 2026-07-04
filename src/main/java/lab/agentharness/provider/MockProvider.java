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
            Schema.ToolCall listFiles = new Schema.ToolCall(
                    "call_list_files_001",
                    "bash",
                    Schema.RawJson.of("{\"command\":\"" + listCommand() + "\"}"));

            return Schema.Message.assistant(
                    "让我来看看当前工作区下有什么文件。",
                    List.of(listFiles));
        }

        String observation = messages.stream()
                .filter(message -> message.toolCallId() != null)
                .reduce((first, second) -> second)
                .map(Schema.Message::content)
                .orElse("没有拿到工具返回。");

        return Schema.Message.assistant("""
                我看到了当前目录的文件列表，说明 Main Loop 已经完成了一轮 Action/Observation。
                当前项目包含 Maven 配置、README、docs 和 src 目录，任务完成。

                Observation 摘要：
                %s
                """.formatted(firstLines(observation, 6)));
    }

    private static String listCommand() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "dir /b" : "ls -la";
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
