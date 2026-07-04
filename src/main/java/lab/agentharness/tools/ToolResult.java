package lab.agentharness.tools;

/**
 * 工具执行结果，统一表达成功状态和返回给模型的文本输出。
 */
public record ToolResult(boolean success, String output) {
    public static ToolResult ok(String output) {
        return new ToolResult(true, output);
    }

    public static ToolResult failed(String output) {
        return new ToolResult(false, output);
    }
}
