package lab.agentharness.engine;

/**
 * TerminalReporter 是本地 CLI 的默认展示层，实现方式刻意保持简单，便于后续和飞书 Reporter 对比。
 */
public final class TerminalReporter implements Reporter {
    @Override
    public void onThinking() {
        System.out.println();
        System.out.println("[Thinking] 模型正在推理...");
    }

    @Override
    public void onToolCall(String toolName, String args) {
        System.out.println("[ToolCall] " + toolName);
        System.out.println("  参数: " + compact(args, 150));
    }

    @Override
    public void onToolResult(String toolName, String result, boolean isError) {
        String status = isError ? "执行报错" : "执行成功";
        System.out.println("[ToolResult] " + toolName + " " + status);
        if (isError && result != null && !result.isBlank()) {
            System.out.println("  错误: " + compact(result, 300));
        }
    }

    @Override
    public void onMessage(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        System.out.println();
        System.out.println("[Agent]");
        System.out.println(content);
        System.out.println();
    }

    private static String compact(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String display = text.replace("\r", "\\r").replace("\n", "\\n");
        return display.length() <= maxChars
                ? display
                : display.substring(0, maxChars) + "... (已截断)";
    }
}
