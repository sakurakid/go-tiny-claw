package lab.agentharness.tools;

/**
 * AgentRunner 是 SubagentTool 拉起引擎的最小接口，用于避免 tools 包直接依赖 engine 包。
 */
public interface AgentRunner {
    /**
     * 启动一个一次性的隔离子智能体任务，并返回它给主 Agent 的纯文本摘要。
     */
    String runSub(String taskPrompt, Registry readOnlyRegistry, Object reporter) throws Exception;
}
