package lab.agentharness.claw;

import java.nio.file.Files;
import java.nio.file.Path;

import lab.agentharness.context.PromptComposer;
import lab.agentharness.engine.AgentEngine;
import lab.agentharness.engine.TerminalReporter;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.schema.Schema;
import lab.agentharness.tools.Registry;
import lab.agentharness.tools.ToolRegistry;

/**
 * SkillPromptSmokeTest 用 workspace 目录验证 AGENTS.md 与 SKILL.md 是否被动态注入系统提示词。
 */
public final class SkillPromptSmokeTest {
    private static final String DEMO_PROMPT = """
            我需要在当前目录下新建一个 ping.java，提供一个简单的 http ping 接口。
            写完之后，帮我把代码用 git 提交一下。
            """;

    private SkillPromptSmokeTest() {
    }

    public static void main(String[] args) {
        Path workDir = Path.of("workspace").toAbsolutePath().normalize();
        ensureWorkspace(workDir);

        if (args.length > 0 && "--run-agent".equals(args[0])) {
            runAgentDemo(workDir, Main.providerFromEnv(), promptFromArgs(args));
            return;
        }

        Schema.Message systemMessage = new PromptComposer(workDir).build();
        String prompt = systemMessage.content();
        requireContains(prompt, "项目专属指南");
        requireContains(prompt, "可用专业技能");
        requireContains(prompt, "git-workflow");
        requireContains(prompt, "提交流程 SOP");

        System.out.println("Skill Prompt 组装成功。");
        System.out.println("WorkDir: " + workDir);
        System.out.println("System Prompt 预览:");
        System.out.println(preview(prompt, 1800));
    }

    private static void runAgentDemo(Path workDir, LLMProvider provider, String prompt) {
        Registry registry = ToolRegistry.demoRegistry(workDir);
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, true);
        engine.run(prompt, new TerminalReporter());
    }

    private static void ensureWorkspace(Path workDir) {
        if (!Files.isDirectory(workDir)) {
            throw new IllegalStateException("缺少 workspace 测试目录: " + workDir);
        }
    }

    private static void requireContains(String text, String expected) {
        if (!text.contains(expected)) {
            throw new IllegalStateException("System Prompt 未包含预期内容: " + expected);
        }
    }

    private static String preview(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n... (预览已截断)";
    }

    private static String promptFromArgs(String[] args) {
        if (args.length <= 1) {
            return DEMO_PROMPT;
        }

        StringBuilder prompt = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) {
                prompt.append(' ');
            }
            prompt.append(args[i]);
        }
        return prompt.toString();
    }
}
