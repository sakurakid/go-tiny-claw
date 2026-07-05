package lab.agentharness.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import lab.agentharness.schema.Schema;

/**
 * PromptComposer 根据工作区环境动态组装 System Prompt，包括核心纪律、AGENTS.md 和 Skills。
 */
public final class PromptComposer {
    private final Path workDir;
    private final SkillLoader skillLoader;

    public PromptComposer(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.skillLoader = new SkillLoader(this.workDir);
    }

    /**
     * 返回完整的系统消息，供 AgentEngine 在每次 run 初始化上下文时注入。
     */
    public Schema.Message build() {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("""
                # 核心身份
                你名叫 go-tiny-claw，一个由驾驭工程驱动的资深研发助手。
                你具备极简主义哲学，拒绝废话。你能通过系统提供的内置工具，创建、读取、修改和执行工作区中的代码。

                # 核心纪律 (CRITICAL)
                1. 如需检查文件是否存在，请使用 bash 的 ls 或 test -f，而不是对目录使用 read_file。
                2. 创建新文件时，务必使用 write_file，并同时提供 path 和 content 参数。
                3. 编辑文件前务必先读取现有文件，以理解上下文。
                4. 无论何时你需要写代码或创建文件，都要直接使用 write_file 工具。
                5. 遇到工具执行报错时，仔细阅读 stderr，尝试自己修正命令并重试。
                6. 始终用中文回复，以便传达你的进展和想法。
                7. 必须尊重 WorkDir 边界；不要安装软件、下载远程产物或修改系统级配置，除非用户明确要求。
                8. 如果缺少必要运行时，请如实报告限制，不要自行安装。
                9. 输出纯文本即可，不要使用 markdown 表格。
                """);

        appendAgentsMd(promptBuilder);

        String skillsContent = skillLoader.loadAll();
        if (!skillsContent.isBlank()) {
            promptBuilder.append(skillsContent);
        }

        return Schema.Message.system(promptBuilder.toString());
    }

    private void appendAgentsMd(StringBuilder promptBuilder) {
        Path agentsMdPath = workDir.resolve("AGENTS.md").normalize();
        if (!agentsMdPath.startsWith(workDir) || !Files.isRegularFile(agentsMdPath)) {
            return;
        }

        try {
            String content = Files.readString(agentsMdPath, StandardCharsets.UTF_8);
            promptBuilder.append("\n# 项目专属指南 (来自 AGENTS.md)\n");
            promptBuilder.append("以下是当前工作区特有的架构规范与注意事项，你的行为必须绝对符合以下要求：\n");
            promptBuilder.append("```markdown\n");
            promptBuilder.append(content.strip()).append('\n');
            promptBuilder.append("```\n");
        } catch (IOException ignored) {
            // AGENTS.md 是可选增强上下文，读取失败时保持核心 Prompt 可用。
        }
    }
}
