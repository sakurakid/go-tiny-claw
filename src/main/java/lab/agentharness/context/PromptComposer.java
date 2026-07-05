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
    private final boolean planMode;
    private final SkillLoader skillLoader;

    public PromptComposer(Path workDir) {
        this(workDir, false);
    }

    public PromptComposer(Path workDir, boolean planMode) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.planMode = planMode;
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
                10. 调用 bash 工具时直接填写要执行的命令，不要再嵌套 powershell -Command；Windows 下可直接使用 git status && git add ... 这类 cmd 语法。
                11. 严禁口头宣称文件已创建、命令已执行或代码已提交；只有看到对应工具 Observation 后，才允许报告该动作完成。
                12. 当用户要求提交代码时，必须实际调用 bash 执行 git status、git add 和 git commit；没有 git commit 的 Observation，不得说提交成功。
                """);

        if (planMode) {
            appendPlanMode(promptBuilder);
        }

        appendAgentsMd(promptBuilder);

        String skillsContent = skillLoader.loadAll();
        if (!skillsContent.isBlank()) {
            promptBuilder.append(skillsContent);
        }

        return Schema.Message.system(promptBuilder.toString());
    }

    private void appendPlanMode(StringBuilder promptBuilder) {
        promptBuilder.append("""

                # 长程任务与状态外部化强制规范 (Plan Mode: ON)

                !!! 警告：本模式下，你绝对不能依赖自己的短期记忆。你必须将所有架构思路和执行进度持久化到物理文件中。 !!!

                当你收到一条新指令被唤醒时，你必须、且只能按照以下绝对顺序执行：

                ## STEP 1: 强制环境嗅探 (Bootstrapping)
                - 收到指令后，必须第一时间使用 bash 工具检查当前工作区根目录是否已经存在 `PLAN.md` 和 `TODO.md`。
                - 当前 Java BashTool 在 Windows 下会走 `cmd.exe /c`，优先使用 `dir`、`if exist PLAN.md`、`if exist TODO.md`；在类 Unix 环境可使用 `ls -la` 或 `test -f`。
                - 分支 A (全新任务)：如果这两个文件不存在，说明这是全新任务。必须使用 write_file 依次创建：
                  1. `PLAN.md`：写下你的需求理解、架构设计、技术选型、边界假设。
                  2. `TODO.md`：拆解出具体可执行步骤，必须使用标准 Markdown Checkbox，例如 `- [ ] 步骤1`。
                - 分支 B (断点续传/任务唤醒)：如果这两个文件已经存在，绝对不要覆盖它们。必须立即使用 read_file 阅读 `PLAN.md` 了解全局目标，并阅读 `TODO.md` 寻找第一个未完成的 `- [ ]` 任务，从那里继续。

                ## STEP 2: 严格单步执行与实时打勾
                - 开始执行 `TODO.md` 中第一个未完成任务。
                - 每当你通过 write_file、edit_file 或 bash 真正完成一个子任务后，必须立即停下来，优先使用 edit_file 工具或 bash 命令，把 `TODO.md` 中对应行从 `- [ ]` 修改为 `- [x]`。
                - 绝对不允许一口气写完所有代码最后再打勾。做完一步，必须打勾一步。

                ## STEP 3: 迷失时的自救
                - 如果遇到报错、上下文不确定或不知道下一步该干嘛，立即使用 read_file 重新读取 `TODO.md` 确认当前位置。
                - 如果任务范围过大，优先保持 PLAN/TODO 准确，再按 TODO 小步推进。
                """);
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
