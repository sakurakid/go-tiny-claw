package lab.agentharness.schema;

import java.util.List;

/**
 * go-tiny-claw 的统一通信协议，承载 Main Loop、Provider 和 Tool 之间流动的上下文数据。
 */
public final class Schema {
    private Schema() {
    }

    /**
     * 消息角色，是 Harness 与大模型对话时最基础的身份标记。
     */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /**
     * 上下文中的单条消息，可表示系统提示、用户任务、模型回复或工具 Observation。
     */
    public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, Usage usage) {
        public Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {
            this(role, content, toolCalls, toolCallId, null);
        }

        public Message {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Message system(String content) {
            return new Message(Role.SYSTEM, content, List.of(), null);
        }

        public static Message user(String content) {
            return new Message(Role.USER, content, List.of(), null);
        }

        public static Message assistant(String content) {
            return new Message(Role.ASSISTANT, content, List.of(), null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message(Role.ASSISTANT, content, toolCalls, null);
        }

        public static Message observation(String toolCallId, String output) {
            return new Message(Role.USER, output, List.of(), toolCallId);
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }

        public Message withUsage(Usage usage) {
            return new Message(role, content, toolCalls, toolCallId, usage);
        }
    }

    /**
     * 大模型 API 返回的资源消耗元数据，用于可观测性和成本统计。
     */
    public record Usage(int promptTokens, int completionTokens) {
        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    /**
     * 模型请求调用的具体工具，Arguments 保留 JSON 原文，Main Loop 不解析工具参数。
     */
    public record ToolCall(String id, String name, RawJson arguments) {
    }

    /**
     * 工具本地执行后的物理结果，后续会作为 Observation 写回上下文。
     */
    public record ToolResult(String toolCallId, String output, boolean isError) {
        public static ToolResult ok(String toolCallId, String output) {
            return new ToolResult(toolCallId, output, false);
        }

        public static ToolResult error(String toolCallId, String output) {
            return new ToolResult(toolCallId, output, true);
        }
    }

    /**
     * 暴露给模型的工具元信息，InputSchema 同样保留 JSON 原文，方便适配不同模型 API。
     */
    public record ToolDefinition(String name, String description, RawJson inputSchema) {
    }

    /**
     * Java 版 json.RawMessage：只保存 JSON 字符串，延迟到具体工具或 Provider 适配器再解析。
     */
    public record RawJson(String value) {
        public static RawJson of(String value) {
            return new RawJson(value == null ? "{}" : value);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
