package lab.agentharness.entry.approval;

/**
 * 人工审批网关抽象，用来连接 CLI、飞书或其他 Human-in-the-loop 回调通道。
 */
public interface ApprovalGateway {
    boolean approve(String reason);
}
