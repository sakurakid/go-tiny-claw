package lab.agentharness.tools;

public record ToolResult(boolean success, String output) {
    public static ToolResult ok(String output) {
        return new ToolResult(true, output);
    }

    public static ToolResult failed(String output) {
        return new ToolResult(false, output);
    }
}
