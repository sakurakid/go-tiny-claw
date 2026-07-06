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
 * RecoverySmokeTest 使用真实模型触发 edit_file 失败，并验证错误 Observation 会注入恢复建议。
 */
public final class RecoverySmokeTest {
    private static final Logger LOG = Logger.getLogger(RecoverySmokeTest.class.getName());
    private static final Path WORKSPACE = Path.of("workspace").toAbsolutePath().normalize();
    private static final String SESSION_ID = "test_recovery_001";

    private RecoverySmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(WORKSPACE);
        seedAuthFile(WORKSPACE.resolve("auth.java"));

        LLMProvider provider = Main.providerFromEnv();
        Registry registry = ToolRegistry.demoRegistry(WORKSPACE);

        // 关闭 Thinking 与 Plan Mode，专注观察工具失败后的单点自愈。
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, WORKSPACE, false, false);
        Session session = SessionManager.GLOBAL.getOrCreate(SESSION_ID, WORKSPACE);

        String prompt = """
                我当前目录下有一个 auth.java 文件。
                这是一个 Recovery 机制测试：请第一步直接使用 edit_file 工具修改 auth.java，不要先读取文件。
                请把下面这段代码中的判断条件改为同时允许 "admin"、"root" 和 "guest" 三种用户登录：

                // 鉴权入口函数
                func login(user string) bool {
                    // 检查用户名
                    if user == "admin" {
                        return true
                    }
                    return false
                }

                如果 edit_file 失败，请根据系统返回的救援指南继续修正。
                """;

        LOG.info("[RecoveryTest] 已写入测试文件: " + WORKSPACE.resolve("auth.java"));
        LOG.info("[RecoveryTest] 启动自愈测试任务...");

        session.append(Schema.Message.user(prompt));
        engine.run(session, new TerminalReporter());
    }

    private static void seedAuthFile(Path authFile) throws IOException {
        String content = """
                public final class Auth {
                    private Auth() {
                    }

                    // 鉴权入口函数：注意这里故意使用 Java 语法和复杂缩进，诱发错误 old_text 匹配失败。
                    public static boolean login(String user) {
                        if (user == null) {
                            return false;
                        }

                        String normalized = user.trim().toLowerCase();
                        if ("admin".equals(normalized)) {
                            return true;
                        }

                        return false;
                    }
                }
                """;
        Files.writeString(authFile, content, StandardCharsets.UTF_8);
    }
}
