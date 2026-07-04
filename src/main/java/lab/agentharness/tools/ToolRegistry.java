package lab.agentharness.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.spec().name(), tool);
    }

    public Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ToolExecutionException("未注册工具: " + name);
        }
        return tool;
    }

    public List<ToolSpec> toolSpecs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    public List<String> toolNames() {
        return new ArrayList<>(tools.keySet());
    }
}
