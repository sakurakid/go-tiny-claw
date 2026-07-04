package lab.agentharness.tools.middleware;

import java.util.List;
import java.util.Locale;

import lab.agentharness.entry.approval.ApprovalGateway;
import lab.agentharness.tools.ToolExecutionException;
import lab.agentharness.tools.ToolRequest;

/**
 * 高危命令拦截器，识别破坏性 bash 命令并要求人工审批。
 */
public final class DangerousCommandMiddleware implements ToolMiddleware {
    private static final List<String> DANGEROUS_PATTERNS = List.of(
            "rm -rf",
            "del /f",
            "format ",
            "shutdown",
            "reg delete",
            "git reset --hard");

    private final ApprovalGateway approvalGateway;

    public DangerousCommandMiddleware(ApprovalGateway approvalGateway) {
        this.approvalGateway = approvalGateway;
    }

    @Override
    public void beforeExecute(ToolRequest request) {
        String command = request.argument("command").toLowerCase(Locale.ROOT);
        boolean dangerous = DANGEROUS_PATTERNS.stream().anyMatch(command::contains);
        if (!dangerous) {
            return;
        }

        boolean approved = approvalGateway.approve("检测到高危命令: " + request.argument("command"));
        if (!approved) {
            throw new ToolExecutionException("高危命令未获审批，已拦截。");
        }
    }
}
