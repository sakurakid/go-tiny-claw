package lab.agentharness.tools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lab.agentharness.schema.Schema;

/**
 * BashTool 是 Agent 执行工作区命令的工具，用超时、工作目录绑定和输出截断给 Shell 能力加上 Harness 边界。
 */
public final class BashTool implements BaseTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BYTES = 8000;

    private final Path workDir;

    public BashTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public Schema.ToolDefinition definition() {
        return new Schema.ToolDefinition(
                name(),
                "在当前工作区执行终端命令。支持链式命令(如 &&)，返回标准输出(stdout)和标准错误(stderr)。",
                Schema.RawJson.of("""
                        {
                          "type": "object",
                          "properties": {
                            "command": {
                              "type": "string",
                              "description": "要执行的终端命令，例如: ls -la、mvn test 或 go test ./..."
                            }
                          },
                          "required": ["command"]
                        }
                        """));
    }

    @Override
    public String execute(Schema.RawJson arguments) throws Exception {
        String command = commandArg(arguments);
        ProcessBuilder processBuilder = new ProcessBuilder(shell(command))
                .directory(workDir.toFile())
                .redirectErrorStream(true);

        Process process = processBuilder.start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));

        boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            String output = outputFuture.get(2, TimeUnit.SECONDS);
            return truncate(output)
                    + "\n[警告: 命令执行超时(30s)，已被系统强制终止。如果是启动常驻服务，请尝试将其转入后台。]";
        }

        String output = outputFuture.get(2, TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
            return "执行报错: exit code " + process.exitValue() + "\n输出:\n" + truncate(output);
        }
        if (output.isBlank()) {
            return "命令执行成功，无终端输出。";
        }

        return truncate(output);
    }

    private static String commandArg(Schema.RawJson arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments.value());
        String command = root.path("command").asText("");
        if (command.isBlank()) {
            throw new IllegalArgumentException("参数解析失败: 缺少必填字段 command");
        }
        return command;
    }

    private static String[] shell(String command) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return new String[] {"cmd.exe", "/c", command};
        }
        return new String[] {"bash", "-lc", command};
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取命令输出失败: " + e.getMessage();
        }
    }

    private static String truncate(String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return output;
        }
        byte[] prefix = Arrays.copyOf(bytes, MAX_BYTES);
        return new String(prefix, StandardCharsets.UTF_8)
                + "\n\n...[终端输出过长，已截断至前 " + MAX_BYTES + " 字节]...";
    }
}
