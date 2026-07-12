package lab.agentharness.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import lab.agentharness.observability.Trace;
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

    public static ToolRegistry readOnlyRegistry(java.nio.file.Path workspace) {
        ToolRegistry registry = newRegistry();
        registry.register(new ReadFileTool(workspace));
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
        try (Trace.Span span = Trace.startSpan("Tool.Execute")) {
            span.addAttribute("tool_name", call.name());
            span.addAttribute("arguments", String.valueOf(call.arguments()));

            BaseTool tool = tools.get(call.name());
            if (tool == null) {
                String output = "Error: 系统中不存在名为 '" + call.name() + "' 的工具。";
                span.addAttribute("error", output);
                return Schema.ToolResult.error(call.id(), output);
            }

            try {
                String output = tool.execute(call.arguments());
                span.addAttribute("output_preview", truncate(output, 100));
                span.addAttribute("output_chars", output == null ? 0 : output.length());
                return Schema.ToolResult.ok(call.id(), output);
            } catch (Exception e) {
                String output = "Error executing " + call.name() + ": " + e.getMessage();
                span.addAttribute("error", e.getMessage());
                span.addAttribute("output_preview", truncate(output, 100));
                return Schema.ToolResult.error(call.id(), output);
            }
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
