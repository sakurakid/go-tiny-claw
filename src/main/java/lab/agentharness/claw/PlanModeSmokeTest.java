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
 * PlanModeSmokeTest 使用真实模型验证长程任务的 PLAN.md / TODO.md 外部化流程。
 */
public final class PlanModeSmokeTest {
    private static final Logger LOG = Logger.getLogger(PlanModeSmokeTest.class.getName());
    private static final Path PLAN_WORKSPACE = Path.of("workspace_plan").toAbsolutePath().normalize();
    private static final String SESSION_ID = "plan_mode_task_001";

    private PlanModeSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        Args parsedArgs = Args.parse(args);
        if (parsedArgs.prompt().isBlank()) {
            System.out.println("用法: mvn -q \"-Dmain.class=lab.agentharness.claw.PlanModeSmokeTest\" "
                    + "-Dexec.args=\"--prompt 你的任务指令\" exec:java");
            System.exit(1);
        }

        Files.createDirectories(PLAN_WORKSPACE);
        if (parsedArgs.resetPlanFiles()) {
            Files.deleteIfExists(PLAN_WORKSPACE.resolve("PLAN.md"));
            Files.deleteIfExists(PLAN_WORKSPACE.resolve("TODO.md"));
            LOG.info("[PlanMode] 已清理 PLAN.md / TODO.md，准备从全新任务开始。");
        }

        LLMProvider provider = Main.providerFromEnv();
        Registry registry = ToolRegistry.demoRegistry(PLAN_WORKSPACE);

        // 关闭 Thinking 以便更快验证 Plan Mode；计划纪律由 System Prompt 强制注入。
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, PLAN_WORKSPACE, false, true);
        Session session = SessionManager.GLOBAL.getOrCreate(SESSION_ID, PLAN_WORKSPACE);

        LOG.info("[PlanMode] 收到指令: " + parsedArgs.prompt());
        LOG.info("[PlanMode] 工作区: " + PLAN_WORKSPACE);

        session.append(Schema.Message.user(parsedArgs.prompt()));
        try {
            engine.run(session, new TerminalReporter());
        } catch (IllegalStateException e) {
            if (!e.getMessage().contains("Main Loop 超过最大轮数")) {
                throw e;
            }
            LOG.warning("[PlanMode] 本次运行达到单次轮数上限，已暂停。再次运行同一入口会从 TODO.md 继续。");
        }
    }

    private record Args(String prompt, boolean resetPlanFiles) {
        static Args parse(String[] args) {
            StringBuilder prompt = new StringBuilder();
            boolean reset = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--reset".equals(arg)) {
                    reset = true;
                    continue;
                }

                if (("--prompt".equals(arg) || "-prompt".equals(arg)) && i + 1 < args.length) {
                    append(prompt, args[++i]);
                    continue;
                }

                append(prompt, arg);
            }

            return new Args(prompt.toString().trim(), reset);
        }

        private static void append(StringBuilder prompt, String text) {
            if (prompt.length() > 0) {
                prompt.append(' ');
            }
            prompt.append(text);
        }
    }
}
