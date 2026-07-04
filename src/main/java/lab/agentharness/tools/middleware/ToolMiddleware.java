package lab.agentharness.tools.middleware;

import lab.agentharness.tools.ToolRequest;

public interface ToolMiddleware {
    void beforeExecute(ToolRequest request);
}
