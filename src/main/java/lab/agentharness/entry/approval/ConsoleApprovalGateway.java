package lab.agentharness.entry.approval;

import java.util.logging.Logger;

/**
 * 控制台审批占位实现，当前默认拒绝高危操作，后续可改成交互式确认。
 */
public final class ConsoleApprovalGateway implements ApprovalGateway {
    private static final Logger LOG = Logger.getLogger(ConsoleApprovalGateway.class.getName());

    @Override
    public boolean approve(String reason) {
        LOG.warning("Human-in-the-loop 审批占位实现默认拒绝: " + reason);
        return false;
    }
}
