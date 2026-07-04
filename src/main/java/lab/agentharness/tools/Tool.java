package lab.agentharness.tools;

/**
 * Harness 暴露给模型的工具抽象，统一描述工具元信息和执行入口。
 */
public interface Tool {
    ToolSpec spec();

    ToolResult execute(ToolRequest request);
}
