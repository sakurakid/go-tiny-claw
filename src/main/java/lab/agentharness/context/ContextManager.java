package lab.agentharness.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import lab.agentharness.tools.ToolSpec;

public final class ContextManager {
    private final Path workspace;
    private final PromptComposer promptComposer;
    private final TokenMonitor tokenMonitor;

    public ContextManager(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.promptComposer = new PromptComposer();
        this.tokenMonitor = new TokenMonitor();
    }

    public RuntimeContext build(String task, List<ToolSpec> toolSpecs) {
        String memorySnapshot = loadMemorySnapshot();
        String systemPrompt = promptComposer.compose(task, toolSpecs, memorySnapshot);
        int estimatedTokens = tokenMonitor.estimate(systemPrompt);

        return new RuntimeContext(systemPrompt, toolSpecs, memorySnapshot, estimatedTokens);
    }

    private String loadMemorySnapshot() {
        StringBuilder snapshot = new StringBuilder();
        appendIfExists(snapshot, workspace.resolve("PLAN.md"), "PLAN.md");
        appendIfExists(snapshot, workspace.resolve("TODO.md"), "TODO.md");

        if (snapshot.isEmpty()) {
            return "暂无 PLAN.md / TODO.md，当前运行从空白外部记忆开始。";
        }
        return snapshot.toString();
    }

    private static void appendIfExists(StringBuilder snapshot, Path path, String label) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            snapshot.append("## ").append(label).append(System.lineSeparator());
            snapshot.append(Files.readString(path)).append(System.lineSeparator());
        } catch (Exception e) {
            snapshot.append("## ").append(label).append(System.lineSeparator());
            snapshot.append("读取失败: ").append(e.getMessage()).append(System.lineSeparator());
        }
    }
}
