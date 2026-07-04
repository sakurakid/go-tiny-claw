package lab.agentharness.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lab.agentharness.schema.Schema;

/**
 * 极简工具注册表，负责保存工具定义，并把模型发起的 ToolCall 分发到本地工具。
 */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public static ToolRegistry demoRegistry(Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(workspace));
        registry.register(new BashTool());
        return registry;
    }

    public void register(Tool tool) {
        tools.put(tool.definition().name(), tool);
    }

    public List<Schema.ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    public Schema.ToolResult execute(Schema.ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return Schema.ToolResult.error(call.id(), "未注册工具: " + call.name());
        }

        try {
            return Schema.ToolResult.ok(call.id(), tool.execute(call.arguments()));
        } catch (Exception e) {
            return Schema.ToolResult.error(call.id(), e.getMessage());
        }
    }

    /**
     * 本地工具接口。工具自己解析 RawJson，Main Loop 只负责分发。
     */
    public interface Tool {
        Schema.ToolDefinition definition();

        String execute(Schema.RawJson arguments) throws Exception;
    }

    /**
     * 读取工作区内文件的 demo 工具。
     */
    private static final class ReadFileTool implements Tool {
        private final Path workspace;

        private ReadFileTool(Path workspace) {
            this.workspace = workspace.toAbsolutePath().normalize();
        }

        @Override
        public Schema.ToolDefinition definition() {
            return new Schema.ToolDefinition(
                    "read_file",
                    "读取工作区内的文本文件。",
                    Schema.RawJson.of("""
                            {
                              "type": "object",
                              "properties": {
                                "path": {"type": "string", "description": "相对工作区的文件路径"}
                              },
                              "required": ["path"]
                            }
                            """));
        }

        @Override
        public String execute(Schema.RawJson arguments) throws Exception {
            String path = JsonArg.string(arguments, "path");
            Path target = workspace.resolve(path).normalize();
            if (!target.startsWith(workspace)) {
                throw new IllegalArgumentException("拒绝读取工作区外文件: " + path);
            }
            return Files.readString(target);
        }
    }

    /**
     * 执行终端命令的 demo 工具，带一个很薄的高危命令拦截。
     */
    private static final class BashTool implements Tool {
        private static final Duration TIMEOUT = Duration.ofSeconds(10);
        private static final List<String> DENY_LIST = List.of("rm -rf", "del /f", "format ", "shutdown", "git reset --hard");

        @Override
        public Schema.ToolDefinition definition() {
            return new Schema.ToolDefinition(
                    "bash",
                    "执行一个只读或低风险终端命令，高危命令会被拒绝。",
                    Schema.RawJson.of("""
                            {
                              "type": "object",
                              "properties": {
                                "command": {"type": "string", "description": "要执行的终端命令"}
                              },
                              "required": ["command"]
                            }
                            """));
        }

        @Override
        public String execute(Schema.RawJson arguments) throws Exception {
            String command = JsonArg.string(arguments, "command");
            String lowerCommand = command.toLowerCase();
            if (DENY_LIST.stream().anyMatch(lowerCommand::contains)) {
                throw new IllegalArgumentException("高危命令已被 Harness 拦截: " + command);
            }

            Process process = new ProcessBuilder(shell(command)).start();
            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("命令执行超时: " + command);
            }

            String stdout = read(process.getInputStream());
            String stderr = read(process.getErrorStream());
            if (process.exitValue() != 0) {
                throw new IllegalStateException(stderr.isBlank() ? stdout : stderr);
            }
            return stdout;
        }

        private static String[] shell(String command) {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
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

    /**
     * 极简 JSON 参数读取器，只为 demo 解析字符串字段，避免 Main Loop 理解具体工具参数。
     */
    private static final class JsonArg {
        private JsonArg() {
        }

        private static String string(Schema.RawJson json, String field) {
            Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
            Matcher matcher = pattern.matcher(json.value());
            if (!matcher.find()) {
                throw new IllegalArgumentException("工具参数缺少字段: " + field);
            }
            return matcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n");
        }
    }
}
