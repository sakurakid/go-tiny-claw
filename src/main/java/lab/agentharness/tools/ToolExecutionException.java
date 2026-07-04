package lab.agentharness.tools;

public final class ToolExecutionException extends RuntimeException {
    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
