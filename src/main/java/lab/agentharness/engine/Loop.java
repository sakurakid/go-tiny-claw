package lab.agentharness.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lab.agentharness.provider.ModelProvider;
import lab.agentharness.schema.Schema;
import lab.agentharness.tools.ToolRegistry;

/**
 * Main Loop / ReAct 核心循环，负责组装上下文、调用模型、执行工具并记录 Observation。
 */
public final class Loop {
    private static final int MAX_TURNS = 4;

    private final ModelProvider provider;
    private final ToolRegistry tools;

    public Loop(ModelProvider provider, ToolRegistry tools) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    public void run(String task) {
        List<Schema.Message> messages = new ArrayList<>();
        messages.add(Schema.Message.system("""
                你运行在 go-tiny-claw 的极简 Harness 中。
                你可以思考，也可以通过工具行动；工具返回会作为 Observation 写回上下文。
                高危操作不要直接执行，应该先请求人工确认。
                """));
        messages.add(Schema.Message.user(task));

        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            System.out.println();
            System.out.println("---- Turn " + turn + " ----");
            System.out.println("整理上下文与可用工具，调用模型 Provider: " + provider.name());

            Schema.Message assistant = provider.complete(messages, tools.definitions());
            messages.add(assistant);
            System.out.println("Assistant: " + assistant.content());

            if (!assistant.hasToolCalls()) {
                System.out.println();
                System.out.println("最终结果:");
                System.out.println(assistant.content());
                return;
            }

            for (Schema.ToolCall call : assistant.toolCalls()) {
                System.out.println("执行工具: " + call.name() + " " + call.arguments());
                Schema.ToolResult result = tools.execute(call);
                String observation = (result.isError() ? "ERROR: " : "") + result.output();
                messages.add(Schema.Message.observation(result.toolCallId(), observation));
                System.out.println("Observation: " + firstLines(observation, 4));
            }
        }

        throw new IllegalStateException("Main Loop 超过最大轮数，疑似进入 Doom Loop。");
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
