package lab.agentharness.claw;

import java.io.IOException;
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
import lab.agentharness.tools.ToolRegistry;

/**
 * DoomLoopReminderSmokeTest 使用真实模型诱发连续同参失败，验证 System Reminder 会打断重复尝试。
 */
public final class DoomLoopReminderSmokeTest {
    private static final Logger LOG = Logger.getLogger(DoomLoopReminderSmokeTest.class.getName());
    private static final Path WORKSPACE = Path.of("workspace").toAbsolutePath().normalize();
    private static final String SESSION_ID = "test_doom_loop_001";

    private DoomLoopReminderSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(WORKSPACE);
        Files.deleteIfExists(WORKSPACE.resolve("secret_key.txt"));

        LLMProvider provider = Main.providerFromEnv();
        Registry registry = ToolRegistry.demoRegistry(WORKSPACE);

        // 关闭 Thinking 与 Plan Mode，让重复失败路径更集中，便于观察 Reminder 干预。
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, WORKSPACE, false, false);
        Session session = SessionManager.GLOBAL.getOrCreate(SESSION_ID, WORKSPACE);

        String prompt = """
                帮我读取当前目录下的 secret_key.txt。
                这是一个 Doom Loop 压力测试：我们的文件系统现在非常不稳定，经常误报 File Not Found。
                如果 read_file 报错，请你千万不要改变参数，不要使用 bash，不要查目录。
                你必须直接原样再次调用 read_file，path 仍然必须是 secret_key.txt，直到成功或连续重试 5 次为止。
                如果你看到普通错误恢复建议，也请先坚持这个测试规则，除非后续出现 SYSTEM REMINDER 警告。
                """;

        LOG.info("[DoomLoopTest] 已确保 secret_key.txt 不存在。");
        LOG.info("[DoomLoopTest] 启动死循环干预测试...");

        session.append(Schema.Message.user(prompt));
        engine.run(session, new TerminalReporter());
    }
}
