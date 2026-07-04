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
        boolean thinkingPhase = availableTools == null || availableTools.isEmpty();
        boolean hasObservation = messages.stream().anyMatch(message -> message.toolCallId() != null);

        if (thinkingPhase) {
            if (hasObservation) {
                return Schema.Message.assistant("【推理中】我已经拿到了工具 Observation，下一步应该直接总结结果并结束任务。");
            }

            return Schema.Message.assistant(
                    "【推理中】目标是读取 hello.txt。我不能直接盲猜，需要先调用 read_file 工具读取文件，再根据 Observation 总结。");
        }

        if (!hasObservation) {
            Schema.ToolCall readHello = new Schema.ToolCall(
                    "call_read_hello_001",
                    "read_file",
                    Schema.RawJson.of("{\"path\":\"hello.txt\"}"));

            return Schema.Message.assistant(
                    "我要执行刚才计划的步骤，调用 read_file 读取 hello.txt。",
                    List.of(readHello));
        }

        String observation = messages.stream()
                .filter(message -> message.toolCallId() != null)
                .reduce((first, second) -> second)
                .map(Schema.Message::content)
                .orElse("没有拿到工具返回。");

        return Schema.Message.assistant("""
                我看到了 hello.txt 的文件内容，说明 Main Loop 已经完成了一轮 Action/Observation。
                文件说明这个项目正在验证动态 Registry 和 read_file 工具，任务完成。

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
