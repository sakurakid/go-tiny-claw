package lab.agentharness.claw;

import java.nio.file.Path;
import java.util.logging.Logger;

import lab.agentharness.engine.AgentEngine;
import lab.agentharness.feishu.FeishuBot;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.tools.Registry;
import lab.agentharness.tools.ToolRegistry;

/**
 * 飞书长连接启动入口：本地进程主动连到飞书，不需要配置公网回调地址或内网穿透。
 */
public final class FeishuMain {
    private static final Logger LOG = Logger.getLogger(FeishuMain.class.getName());

    private FeishuMain() {
    }

    public static void main(String[] args) {
        Path workDir = Path.of("").toAbsolutePath().normalize();

        // 飞书模式复用同一套 Provider、Registry 和 AgentEngine，只替换外部 Reporter。
        LLMProvider provider = Main.providerFromEnv();
        Registry registry = ToolRegistry.demoRegistry(workDir);
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, true);

        LOG.info("[Feishu] go-tiny-claw 飞书长连接服务启动中...");
        FeishuBot bot = new FeishuBot(engine);
        bot.startAndBlock();
    }
}
