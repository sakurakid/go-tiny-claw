package lab.agentharness.claw;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import lab.agentharness.engine.AgentEngine;
import lab.agentharness.engine.Session;
import lab.agentharness.engine.SessionManager;
import lab.agentharness.engine.TerminalReporter;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.schema.Schema;
import lab.agentharness.tools.Registry;
import lab.agentharness.tools.SubagentTool;
import lab.agentharness.tools.ToolRegistry;

/**
 * SubagentSmokeTest 使用真实模型验证主 Agent 委派只读子智能体探索，再由主 Agent 写回结果。
 */
public final class SubagentSmokeTest {
    private static final Logger LOG = Logger.getLogger(SubagentSmokeTest.class.getName());
    private static final Path WORKSPACE = Path.of("workspace").toAbsolutePath().normalize();
    private static final String SESSION_ID = "test_subagent_001";

    private SubagentSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        seedLegacyWorkspace();

        LLMProvider provider = Main.providerFromEnv();
        TerminalReporter reporter = new TerminalReporter();

        Registry readOnlyRegistry = ToolRegistry.readOnlyRegistry(WORKSPACE);
        Registry mainRegistry = ToolRegistry.demoRegistry(WORKSPACE);

        AgentEngine engine = AgentEngine.newAgentEngine(provider, mainRegistry, WORKSPACE, false, false);
        mainRegistry.register(new SubagentTool(engine, readOnlyRegistry, reporter));

        Session session = SessionManager.GLOBAL.getOrCreate(SESSION_ID, WORKSPACE);
        String prompt = """
                我需要你在这个遗留项目里，找到那个“核心密码”。
                为了防止污染主上下文，请你务必派出子智能体（spawn_subagent）去执行探索任务。
                你可以让子智能体使用 bash 去查找当前目录及其所有子目录下名为 config.txt 的文件。
                子智能体拿到密码向你汇报后，请你亲自使用 write_file 工具，将密码写在根目录的 answer.txt 里。
                """;

        LOG.info("[SubagentTest] 已准备遗留项目结构: " + WORKSPACE);
        LOG.info("[SubagentTest] 启动多智能体协同测试...");

        session.append(Schema.Message.user(prompt));
        engine.run(session, reporter);
    }

    private static void seedLegacyWorkspace() throws IOException {
        Path authDir = WORKSPACE.resolve("legacy").resolve("v1").resolve("auth");
        Files.createDirectories(authDir);
        Files.writeString(
                authDir.resolve("config.txt"),
                "核心密码是: super_secret_agent_password_42" + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(WORKSPACE.resolve("fake1.go"), "这是一个空文件" + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(WORKSPACE.resolve("fake2.go"), "这也是一个空文件" + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.deleteIfExists(WORKSPACE.resolve("answer.txt"));
    }
}
