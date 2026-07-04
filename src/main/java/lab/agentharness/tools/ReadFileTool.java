package lab.agentharness.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lab.agentharness.schema.Schema;

/**
 * ReadFileTool 是 Agent 感知工作区文件系统的基础工具，只允许读取 WorkDir 边界内的文件并做长度截断。
 */
public final class ReadFileTool implements BaseTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BYTES = 8000;

    private final Path workDir;

    public ReadFileTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public Schema.ToolDefinition definition() {
        return new Schema.ToolDefinition(
                name(),
                "读取指定路径的文件内容。请提供相对工作区的路径。",
                Schema.RawJson.of("""
                        {
                          "type": "object",
                          "properties": {
                            "path": {
                              "type": "string",
                              "description": "要读取的文件路径，如 README.md 或 src/main/java/lab/agentharness/claw/Main.java"
                            }
                          },
                          "required": ["path"]
                        }
                        """));
    }

    @Override
    public String execute(Schema.RawJson arguments) throws Exception {
        String relativePath = pathArg(arguments);
        Path target = workDir.resolve(relativePath).normalize();
        if (!target.startsWith(workDir)) {
            throw new IllegalArgumentException("拒绝读取工作区外文件: " + relativePath);
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("目标不是可读取文件: " + relativePath);
        }

        byte[] content = Files.readAllBytes(target);
        if (content.length > MAX_BYTES) {
            byte[] prefix = Arrays.copyOf(content, MAX_BYTES);
            return new String(prefix, StandardCharsets.UTF_8)
                    + "\n\n...[由于内容过长，已被系统截断至前 " + MAX_BYTES + " 字节]...";
        }

        return new String(content, StandardCharsets.UTF_8);
    }

    private static String pathArg(Schema.RawJson arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments.value());
        String path = root.path("path").asText("");
        if (path.isBlank()) {
            throw new IllegalArgumentException("参数解析失败: 缺少必填字段 path");
        }
        return path;
    }
}
