package lab.agentharness.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import lab.agentharness.schema.Schema;

/**
 * ToolRegistry 是工具的动态挂载表，负责把模型请求的 ToolCall 路由给对应的 BaseTool。
 */
public final class ToolRegistry implements Registry {
    private static final Logger LOG = Logger.getLogger(ToolRegistry.class.getName());

    private final Map<String, BaseTool> tools = new LinkedHashMap<>();

    public static ToolRegistry newRegistry() {
        return new ToolRegistry();
    }

    public static ToolRegistry demoRegistry(java.nio.file.Path workspace) {
        ToolRegistry registry = newRegistry();
        registry.register(new ReadFileTool(workspace));
        registry.register(new WriteFileTool(workspace));
        registry.register(new EditFileTool(workspace));
        registry.register(new BashTool(workspace));
        return registry;
    }

    @Override
    public void register(BaseTool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = tool.name();
        if (tools.containsKey(name)) {
            LOG.warning("[Registry] 工具 '" + name + "' 已经注册，将被覆盖。");
        }
        tools.put(name, tool);
        LOG.info("[Registry] 成功挂载工具: " + name);
    }

    @Override
    public List<Schema.ToolDefinition> getAvailableTools() {
        return tools.values().stream()
                .map(BaseTool::definition)
                .toList();
    }

    @Override
    public Schema.ToolResult execute(Schema.ToolCall call) {
        BaseTool tool = tools.get(call.name());
        if (tool == null) {
            return Schema.ToolResult.error(call.id(), "Error: 系统中不存在名为 '" + call.name() + "' 的工具。");
        }

        try {
            String output = tool.execute(call.arguments());
            return Schema.ToolResult.ok(call.id(), output);
        } catch (Exception e) {
            return Schema.ToolResult.error(call.id(), "Error executing " + call.name() + ": " + e.getMessage());
        }
    }
}
