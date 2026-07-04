package lab.agentharness.tools;

/**
 * 工具层异常，用于表达未注册工具、越界访问或审批拒绝等可诊断错误。
 */
public final class ToolExecutionException extends RuntimeException {
    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
