package lab.agentharness.claw;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
            runAgentDemo(workDir);
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

    private static void runAgentDemo(Path workDir) {
        LLMProvider provider = new LocalSkillDemoProvider();
        Registry registry = ToolRegistry.demoRegistry(workDir);
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, true);
        engine.run(DEMO_PROMPT, new TerminalReporter());
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

    /**
     * 本地 deterministic provider，用固定 ToolCall 验证 Skill 场景下的引擎、工具和 Reporter 链路。
     */
    private static final class LocalSkillDemoProvider implements LLMProvider {
        @Override
        public String name() {
            return "local-skill-demo-provider";
        }

        @Override
        public Schema.Message generate(List<Schema.Message> messages, List<Schema.ToolDefinition> availableTools) {
            boolean thinkingPhase = availableTools == null || availableTools.isEmpty();
            if (thinkingPhase) {
                return Schema.Message.assistant("【推理中】需要先读取工作区规范和技能指令，然后创建 ping.java 并按 git-workflow 提交。");
            }

            if (!hasObservation(messages, "call_write_ping")) {
                return Schema.Message.assistant(
                        "我会先查看 git 状态，然后创建 ping.java。",
                        List.of(
                                toolCall("call_git_status_before", "bash",
                                        rawJson("command", "git status --short --branch")),
                                toolCall("call_write_ping", "write_file",
                                        rawJson("path", "ping.java", "content", pingJava()))));
            }

            if (!hasObservation(messages, "call_commit_ping")) {
                return Schema.Message.assistant(
                        "文件已经创建，接下来按 git-workflow 检查状态、暂存并提交。",
                        List.of(toolCall("call_commit_ping", "bash", rawJson(
                                "command",
                                "git status --short && git add ping.java && "
                                        + "git diff --cached --quiet && echo No staged changes to commit "
                                        + "|| git commit -m \"🚀 feat: 增加 ping 接口\""))));
            }

            return Schema.Message.assistant("本地执行链路测试完成：已创建 ping.java，并完成 git-workflow 提交流程。");
        }

        private static boolean hasObservation(List<Schema.Message> messages, String toolCallId) {
            return messages.stream().anyMatch(message -> toolCallId.equals(message.toolCallId()));
        }

        private static Schema.ToolCall toolCall(String id, String name, Schema.RawJson arguments) {
            return new Schema.ToolCall(id, name, arguments);
        }

        private static Schema.RawJson rawJson(String key, String value) {
            return Schema.RawJson.of("{\"" + escapeJson(key) + "\":\"" + escapeJson(value) + "\"}");
        }

        private static Schema.RawJson rawJson(String firstKey, String firstValue, String secondKey, String secondValue) {
            return Schema.RawJson.of("{\""
                    + escapeJson(firstKey) + "\":\"" + escapeJson(firstValue) + "\",\""
                    + escapeJson(secondKey) + "\":\"" + escapeJson(secondValue) + "\"}");
        }

        private static String escapeJson(String text) {
            return text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");
        }

        private static String pingJava() {
            return """
                    import com.sun.net.httpserver.HttpServer;
                    import java.io.OutputStream;
                    import java.net.InetSocketAddress;
                    import java.nio.charset.StandardCharsets;

                    public final class ping {
                        private ping() {
                        }

                        public static void main(String[] args) throws Exception {
                            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
                            server.createContext("/ping", exchange -> {
                                byte[] body = "{\\"code\\":0,\\"message\\":\\"pong\\"}".getBytes(StandardCharsets.UTF_8);
                                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                                exchange.sendResponseHeaders(200, body.length);
                                try (OutputStream output = exchange.getResponseBody()) {
                                    output.write(body);
                                }
                            });
                            server.start();
                            System.out.println("ping server listening on http://localhost:8080/ping");
                        }
                    }
                    """;
        }
    }
}
