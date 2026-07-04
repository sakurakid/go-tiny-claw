package lab.agentharness.tools.builtin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import lab.agentharness.tools.Tool;
import lab.agentharness.tools.ToolRequest;
import lab.agentharness.tools.ToolResult;
import lab.agentharness.tools.ToolSpec;
import lab.agentharness.tools.middleware.ToolMiddleware;

public final class BashTool implements Tool {
    private final ToolMiddleware middleware;
    private final Duration timeout = Duration.ofSeconds(10);

    public BashTool(ToolMiddleware middleware) {
        this.middleware = middleware;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("bash", "执行终端命令，高危命令会先经过 Middleware 拦截。");
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        middleware.beforeExecute(request);

        try {
            Process process = new ProcessBuilder(shellCommand(request.argument("command"))).start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.failed("命令执行超时: " + request.argument("command"));
            }

            String stdout = read(process.getInputStream());
            String stderr = read(process.getErrorStream());
            String output = stdout + (stderr.isBlank() ? "" : System.lineSeparator() + stderr);
            return process.exitValue() == 0 ? ToolResult.ok(output) : ToolResult.failed(output);
        } catch (Exception e) {
            return ToolResult.failed(e.getMessage());
        }
    }

    private static String[] shellCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new String[] {"cmd.exe", "/c", command};
        }
        return new String[] {"bash", "-lc", command};
    }

    private static String read(java.io.InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
