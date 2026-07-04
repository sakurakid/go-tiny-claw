package lab.agentharness.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lab.agentharness.schema.Schema;

/**
 * WriteFileTool 是 Agent 写入工作区文件的基础工具，用 WorkDir 边界约束模型只能创建或覆盖项目内文件。
 */
public final class WriteFileTool implements BaseTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workDir;

    public WriteFileTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public Schema.ToolDefinition definition() {
        return new Schema.ToolDefinition(
                name(),
                "创建或覆盖写入一个文件。如果目录不存在会自动创建。请提供相对于工作区的相对路径。",
                Schema.RawJson.of("""
                        {
                          "type": "object",
                          "properties": {
                            "path": {
                              "type": "string",
                              "description": "要写入的文件路径，如 src/main.go"
                            },
                            "content": {
                              "type": "string",
                              "description": "要写入的完整文件内容"
                            }
                          },
                          "required": ["path", "content"]
                        }
                        """));
    }

    @Override
    public String execute(Schema.RawJson arguments) throws Exception {
        JsonNode root = MAPPER.readTree(arguments.value());
        String relativePath = root.path("path").asText("");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("参数解析失败: 缺少必填字段 path");
        }
        if (!root.has("content") || root.path("content").isNull()) {
            throw new IllegalArgumentException("参数解析失败: 缺少必填字段 content");
        }

        Path target = workDir.resolve(relativePath).normalize();
        if (!target.startsWith(workDir)) {
            throw new IllegalArgumentException("拒绝写入工作区外文件: " + relativePath);
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(
                target,
                root.path("content").asText(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        return "成功将内容写入到文件: " + relativePath;
    }
}
