package lab.agentharness.tools.builtin;

import java.nio.file.Files;
import java.nio.file.Path;

import lab.agentharness.tools.Tool;
import lab.agentharness.tools.ToolRequest;
import lab.agentharness.tools.ToolResult;
import lab.agentharness.tools.ToolSpec;

/**
 * 内置编辑工具，提供工作区内文件的最小文本替换能力。
 */
public final class EditFileTool implements Tool {
    private final Path workspace;

    public EditFileTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("edit_file", "在工作区内执行简单文本替换。");
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            Path target = resolveInWorkspace(request.argument("path"));
            String original = Files.readString(target);
            String updated = original.replace(request.argument("old"), request.argument("new"));
            Files.writeString(target, updated);
            return ToolResult.ok("编辑完成: " + target);
        } catch (Exception e) {
            return ToolResult.failed(e.getMessage());
        }
    }

    private Path resolveInWorkspace(String rawPath) {
        Path target = workspace.resolve(rawPath).normalize();
        if (!target.startsWith(workspace)) {
            throw new IllegalArgumentException("拒绝编辑工作区外文件: " + rawPath);
        }
        return target;
    }
}
