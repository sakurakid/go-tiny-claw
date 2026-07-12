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
import lab.agentharness.tools.BashTool;
import lab.agentharness.tools.Registry;
import lab.agentharness.tools.ToolRegistry;
import lab.agentharness.tools.WriteFileTool;

/**
 * TraceSmokeTest 触发一次跨工具任务，用于验证 Agent.Run / Turn / LLM / Tool 的链路追踪树。
 */
public final class TraceSmokeTest {
    private static final Logger LOG = Logger.getLogger(TraceSmokeTest.class.getName());
    private static final String SESSION_ID = "test_trace_001";

    private TraceSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        Path workDir = Path.of("").toAbsolutePath().normalize().resolve("workspace");
        Files.createDirectories(workDir);

        LLMProvider provider = Main.providerFromEnv();

        Registry registry = ToolRegistry.newRegistry();
        registry.register(new BashTool(workDir));
        registry.register(new WriteFileTool(workDir));

        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, false, false);
        TerminalReporter reporter = new TerminalReporter();
        Session session = SessionManager.GLOBAL.getOrCreate(SESSION_ID, workDir);

        String prompt = """
                为了加快执行速度，请你在同一轮 Action 中并行完成下面两件事：
                1. 使用 bash 工具执行：ping -n 3 127.0.0.1 > nul && echo env_check_done
                2. 使用 write_file 工具，在当前目录创建 trace_test.md，内容写上：测试并发的写入

                请分别调用 bash 和 write_file 两个不同工具，不要把写文件合并进 bash 命令。
                完成后用一句中文总结。
                """;

        LOG.info("\n>>> 启动带 Tracing 链路追踪的测试...");
        session.append(Schema.Message.user(prompt));
        engine.run(session, reporter);

        LOG.info("[TraceTest] Trace JSON 已写入: " + workDir.resolve(".claw").resolve("traces"));
    }
}
