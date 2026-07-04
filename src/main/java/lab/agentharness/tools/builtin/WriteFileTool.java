package lab.agentharness.tools.builtin;

import java.nio.file.Files;
import java.nio.file.Path;

import lab.agentharness.tools.Tool;
import lab.agentharness.tools.ToolRequest;
import lab.agentharness.tools.ToolResult;
import lab.agentharness.tools.ToolSpec;

/**
 * 内置写文件工具，只允许在工作区内创建或覆盖文本文件。
 */
public final class WriteFileTool implements Tool {
    private final Path workspace;

    public WriteFileTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("write_file", "在工作区内写入文本文件。");
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            Path target = resolveInWorkspace(request.argument("path"));
            Files.createDirectories(target.getParent());
            Files.writeString(target, request.argument("content"));
            return ToolResult.ok("写入完成: " + target);
        } catch (Exception e) {
            return ToolResult.failed(e.getMessage());
        }
    }

    private Path resolveInWorkspace(String rawPath) {
        Path target = workspace.resolve(rawPath).normalize();
        if (!target.startsWith(workspace)) {
            throw new IllegalArgumentException("拒绝写入工作区外文件: " + rawPath);
        }
        return target;
    }
}
