package lab.agentharness.tools;

/**
 * 工具描述信息，用于注入 Prompt 并让模型理解工具的名字和用途。
 */
public record ToolSpec(String name, String description) {
}
