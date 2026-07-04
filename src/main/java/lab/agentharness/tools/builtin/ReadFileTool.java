package lab.agentharness.tools.builtin;

import java.nio.file.Files;
import java.nio.file.Path;

import lab.agentharness.tools.Tool;
import lab.agentharness.tools.ToolRequest;
import lab.agentharness.tools.ToolResult;
import lab.agentharness.tools.ToolSpec;

/**
 * 内置读文件工具，只允许读取工作区内的文本文件。
 */
public final class ReadFileTool implements Tool {
    private final Path workspace;

    public ReadFileTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("read_file", "读取工作区内的文本文件。");
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            Path target = resolveInWorkspace(request.argument("path"));
            return ToolResult.ok(Files.readString(target));
        } catch (Exception e) {
            return ToolResult.failed(e.getMessage());
        }
    }

    private Path resolveInWorkspace(String rawPath) {
        Path target = workspace.resolve(rawPath).normalize();
        if (!target.startsWith(workspace)) {
            throw new IllegalArgumentException("拒绝读取工作区外文件: " + rawPath);
        }
        return target;
    }
}
