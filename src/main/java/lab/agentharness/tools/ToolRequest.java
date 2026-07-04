package lab.agentharness.tools;

import java.util.Map;

/**
 * 工具调用请求，保存工具名和模型传入的字符串参数。
 */
public record ToolRequest(String toolName, Map<String, String> arguments) {
    public String argument(String key) {
        return arguments.getOrDefault(key, "");
    }
}
