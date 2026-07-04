package lab.agentharness.tools;

public interface Tool {
    ToolSpec spec();

    ToolResult execute(ToolRequest request);
}
