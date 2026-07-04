package lab.agentharness.tools;

import java.util.Map;

public record ToolRequest(String toolName, Map<String, String> arguments) {
    public String argument(String key) {
        return arguments.getOrDefault(key, "");
    }
}
