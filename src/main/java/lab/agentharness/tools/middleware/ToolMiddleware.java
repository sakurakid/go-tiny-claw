package lab.agentharness.tools.middleware;

import lab.agentharness.tools.ToolRequest;

/**
 * 工具中间件抽象，用来在工具真正执行前插入审计、审批或拦截逻辑。
 */
public interface ToolMiddleware {
    void beforeExecute(ToolRequest request);
}
