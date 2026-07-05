package lab.agentharness.engine;

/**
 * TerminalReporter 是本地 CLI 的默认展示层，实现方式刻意保持简单，便于后续和飞书 Reporter 对比。
 */
public final class TerminalReporter implements Reporter {
    @Override
    public void onThinking() {
        System.out.println("[Thinking] 模型正在慢思考...");
    }

    @Override
    public void onToolCall(String toolName, String args) {
        System.out.println("[ToolCall] " + toolName + " 参数: " + args);
    }

    @Override
    public void onToolResult(String toolName, String result, boolean isError) {
        String status = isError ? "执行报错" : "执行成功";
        System.out.println("[ToolResult] " + toolName + " " + status + ": " + result);
    }

    @Override
    public void onMessage(String content) {
        System.out.println("[Message] " + content);
    }
}
