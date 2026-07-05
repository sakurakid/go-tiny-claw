package lab.agentharness.engine;

/**
 * Reporter 定义 Agent 引擎向外界输出状态的契约，用来隔离 CLI、飞书或 WebUI 等展示层。
 */
public interface Reporter {
    /**
     * 当模型进入慢思考阶段时触发。
     */
    void onThinking();

    /**
     * 当模型准备执行某个工具调用时触发。
     */
    void onToolCall(String toolName, String args);

    /**
     * 当工具执行结束并返回物理结果时触发。
     */
    void onToolResult(String toolName, String result, boolean isError);

    /**
     * 当模型输出阶段性文本或最终回复时触发。
     */
    void onMessage(String content);
}
