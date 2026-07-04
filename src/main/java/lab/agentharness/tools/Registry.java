package lab.agentharness.tools;

import java.util.List;

import lab.agentharness.schema.Schema;

/**
 * Registry 定义工具注册表的统一契约，隔离 Main Loop 和具体工具执行实现。
 */
public interface Registry {
    /**
     * 挂载一个新的工具到系统中。
     */
    void register(BaseTool tool);

    /**
     * 返回当前系统挂载的所有可用工具 Schema。
     */
    List<Schema.ToolDefinition> getAvailableTools();

    /**
     * 执行模型请求的工具调用，并返回本地物理执行结果。
     */
    Schema.ToolResult execute(Schema.ToolCall call);
}
