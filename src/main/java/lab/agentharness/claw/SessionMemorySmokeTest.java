package lab.agentharness.claw;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import lab.agentharness.engine.AgentEngine;
import lab.agentharness.engine.Reporter;
import lab.agentharness.engine.Session;
import lab.agentharness.engine.SessionManager;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.schema.Schema;

/**
 * SessionMemorySmokeTest 使用真实模型验证多 Session 隔离与短期 Working Memory 截断。
 */
public final class SessionMemorySmokeTest {
    private static final Logger LOG = Logger.getLogger(SessionMemorySmokeTest.class.getName());
    private static final Path SESSION_ROOT = Path.of("workspace_sessions").toAbsolutePath().normalize();
    private static final String SECRET = "token_12345";

    private SessionMemorySmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        DemoProjects projects = prepareDemoProjects();
        LLMProvider provider = Main.providerFromEnv();

        // 引擎本身保持无工作区状态，每个 Session 唤醒时再按自己的 WorkDir 挂载工具。
        AgentEngine engine = AgentEngine.newSessionAgentEngine(provider, false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> runFrontSession(engine, projects.frontDir())));
            futures.add(executor.submit(() -> runBackSession(engine, projects.backDir())));

            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        LOG.info("[SessionTest] 全部 Session 测试结束，当前 Session 数: " + SessionManager.GLOBAL.size());
    }

    private static void runFrontSession(AgentEngine engine, Path frontDir) {
        Session session = SessionManager.GLOBAL.getOrCreate("chat_front_001", frontDir);
        Reporter reporter = new PrefixReporter("Session A");

        LOG.info("\n>>> [Session A / Turn 1]: 帮我看看 README.md 里记录了什么密钥？");
        session.append(Schema.Message.user("帮我看看 README.md 里记录了什么密钥？"));
        engine.run(session, reporter);

        // 人工压入闲聊消息，故意把第一轮读取到的密钥挤出 Working Memory 窗口。
        for (int i = 0; i < 6; i++) {
            session.append(Schema.Message.user("这只是一句闲聊占位符。"));
            session.append(Schema.Message.assistant("好的，收到闲聊。"));
        }

        LOG.info("\n>>> [Session A / Turn 2]: 请直接告诉我，刚才第一轮你查到的那个密钥是什么？");
        session.append(Schema.Message.user("请直接告诉我，刚才第一轮你查到的那个密钥是什么？不准调用工具！"));
        engine.run(session, reporter);
    }

    private static void runBackSession(AgentEngine engine, Path backDir) {
        sleepQuietly(1000);

        Session session = SessionManager.GLOBAL.getOrCreate("chat_back_002", backDir);
        Reporter reporter = new PrefixReporter("Session B");

        LOG.info("\n>>> [Session B]: 别人查到了一个密钥，你这里能看到吗？");
        session.append(Schema.Message.user("别人查到了一个密钥，你这里能看到吗？不准调用工具！"));
        engine.run(session, reporter);
    }

    private static DemoProjects prepareDemoProjects() throws IOException {
        Path frontDir = SESSION_ROOT.resolve("project_front");
        Path backDir = SESSION_ROOT.resolve("project_back");
        Files.createDirectories(frontDir);
        Files.createDirectories(backDir);
        Files.writeString(
                frontDir.resolve("README.md"),
                "这是项目 A 的 README，里面包含了一个密钥: " + SECRET + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return new DemoProjects(frontDir, backDir);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record DemoProjects(Path frontDir, Path backDir) {
    }

    private record PrefixReporter(String prefix) implements Reporter {
        @Override
        public void onThinking() {
            System.out.println("[" + prefix + "][Thinking] 模型正在推理...");
        }

        @Override
        public void onToolCall(String toolName, String args) {
            System.out.println("[" + prefix + "][ToolCall] " + toolName);
            System.out.println("[" + prefix + "]   参数: " + compact(args, 160));
        }

        @Override
        public void onToolResult(String toolName, String result, boolean isError) {
            String status = isError ? "执行报错" : "执行成功";
            System.out.println("[" + prefix + "][ToolResult] " + toolName + " " + status);
            if (isError && result != null && !result.isBlank()) {
                System.out.println("[" + prefix + "]   错误: " + compact(result, 300));
            }
        }

        @Override
        public void onMessage(String content) {
            if (content == null || content.isBlank()) {
                return;
            }
            System.out.println();
            System.out.println("[" + prefix + "][Agent]");
            System.out.println(content);
            System.out.println();
        }

        private static String compact(String text, int maxChars) {
            if (text == null) {
                return "";
            }
            String display = text.replace("\r", "\\r").replace("\n", "\\n");
            return display.length() <= maxChars
                    ? display
                    : display.substring(0, maxChars) + "... (已截断)";
        }
    }
}
