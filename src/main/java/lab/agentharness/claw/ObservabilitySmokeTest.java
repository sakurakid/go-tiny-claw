package lab.agentharness.claw;

import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;

import lab.agentharness.config.AppConfig;
import lab.agentharness.engine.AgentEngine;
import lab.agentharness.engine.Session;
import lab.agentharness.engine.SessionManager;
import lab.agentharness.engine.TerminalReporter;
import lab.agentharness.observability.CostTracker;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.schema.Schema;
import lab.agentharness.tools.BashTool;
import lab.agentharness.tools.Registry;
import lab.agentharness.tools.ToolRegistry;

/**
 * ObservabilitySmokeTest 使用真实 Provider 跑一次极小任务，验证耗时、Token 和费用统计链路。
 */
public final class ObservabilitySmokeTest {
    private static final Logger LOG = Logger.getLogger(ObservabilitySmokeTest.class.getName());

    private ObservabilitySmokeTest() {
    }

    public static void main(String[] args) {
        Path workDir = Path.of("").toAbsolutePath().normalize();
        String modelName = modelNameFromEnv();
        LLMProvider realProvider = Main.providerFromEnv();

        Session session = SessionManager.GLOBAL.getOrCreate("test_observability_001", workDir);
        LLMProvider trackedProvider = CostTracker.newCostTracker(realProvider, modelName, session);

        Registry registry = ToolRegistry.newRegistry();
        registry.register(new BashTool(workDir));

        AgentEngine engine = AgentEngine.newAgentEngine(trackedProvider, registry, workDir, false, false);
        TerminalReporter reporter = new TerminalReporter();

        String prompt = "请用 bash 查一下现在的日期；如果是 Windows 环境请执行 date /t，其他系统执行 date。最后用一句中文告诉我任务完成。";

        LOG.info("\n>>> 启动带仪表盘的可观测性测试...");
        session.append(Schema.Message.user(prompt));
        engine.run(session, reporter);

        LOG.info("\n================ 财务报表 ================");
        LOG.info("会话 ID: " + session.id());
        LOG.info("总消耗 Input Tokens: " + session.totalPromptTokens());
        LOG.info("总消耗 Output Tokens: " + session.totalCompletionTokens());
        LOG.info(String.format(Locale.ROOT, "总计费用: CNY %.6f", session.totalCostCny()));
        LOG.info("==========================================");
    }

    private static String modelNameFromEnv() {
        String providerName = AppConfig.get("CLAW_PROVIDER", hasText(AppConfig.get("ZHIPU_API_KEY")) ? "zhipu" : "deepseek");
        return switch (providerName.toLowerCase(Locale.ROOT)) {
            case "zhipu", "zhipu-openai" -> AppConfig.get("ZHIPU_MODEL", "glm-4.5-air");
            case "deepseek" -> AppConfig.get("DEEPSEEK_MODEL", "deepseek-v4-flash");
            default -> providerName;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
