package lab.agentharness.memory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FileMemoryStore {
    private final Path workspace;

    public FileMemoryStore(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    public void appendTodo(String line) {
        append("TODO.md", "- " + line + System.lineSeparator());
    }

    public void appendPlan(String line) {
        append("PLAN.md", "- " + line + System.lineSeparator());
    }

    private void append(String fileName, String line) {
        try {
            Files.writeString(
                    workspace.resolve(fileName),
                    line,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException("写入外部化记忆失败: " + fileName, e);
        }
    }
}
