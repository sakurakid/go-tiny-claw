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
import lab.agentharness.tools.ToolRegistry;

/**
 * CompactorSmokeTest 使用真实模型读取巨型日志，验证上下文压缩器会在下一轮推理前触发。
 */
public final class CompactorSmokeTest {
    private static final Logger LOG = Logger.getLogger(CompactorSmokeTest.class.getName());
    private static final int MOCK_LOG_CHARS = 4000;

    private CompactorSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        Path workDir = Path.of("").toAbsolutePath().normalize();
        Path mockLogPath = workDir.resolve("mock_log.txt");
        writeMockLog(mockLogPath);
        LOG.info("[CompactorTest] 已生成大日志文件: " + mockLogPath + " (" + MOCK_LOG_CHARS + " 字符)");

        LLMProvider provider = Main.providerFromEnv();
        Registry registry = ToolRegistry.demoRegistry(workDir);

        // 关闭 Thinking 以提速；核心验证点在 Action 后下一轮推理前的上下文压缩。
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, false);
        Session session = SessionManager.GLOBAL.getOrCreate("test_oom_protection_001", workDir);

        String prompt = """
                请严格按顺序完成以下三个步骤：
                1. 使用 bash 执行 echo "开始排查日志"
                2. 使用 read_file 工具读取当前目录下的巨大文件 mock_log.txt
                3. 使用 bash 执行 date 命令获取当前时间，并告诉我任务全部完成。
                """;

        session.append(Schema.Message.user(prompt));
        engine.run(session, new TerminalReporter());
    }

    private static void writeMockLog(Path path) throws IOException {
        StringBuilder builder = new StringBuilder(MOCK_LOG_CHARS + 256);
        int index = 0;
        while (builder.length() < MOCK_LOG_CHARS) {
            builder.append("ERROR ")
                    .append(index)
                    .append(" - mock service latency spike, trace_id=oom-test-")
                    .append(index)
                    .append(", payload=abcdefghijklmnopqrstuvwxyz0123456789")
                    .append(System.lineSeparator());
            index++;
        }

        Files.writeString(path, builder.substring(0, MOCK_LOG_CHARS), StandardCharsets.UTF_8);
    }
}
