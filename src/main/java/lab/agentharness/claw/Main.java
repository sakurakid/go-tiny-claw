package lab.agentharness.claw;

import java.nio.file.Path;
import java.util.logging.Logger;

import lab.agentharness.config.AppConfig;
import lab.agentharness.engine.AgentEngine;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.provider.OpenAICompatibleProvider;
import lab.agentharness.tools.Registry;
import lab.agentharness.tools.ToolRegistry;

/**
 * CLI 启动入口，负责装配真实 Provider、动态 ToolRegistry 和 AgentEngine。
 */
public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("欢迎来到 go-tiny-claw Java Harness 引擎启动序列");

        // 1. 获取当前执行目录作为 WorkDir 物理边界。
        Path workDir = Path.of("").toAbsolutePath().normalize();

        // 2. 初始化真实的大脑。优先读取系统环境变量，其次读取本地 .env.local。
        LLMProvider provider = providerFromEnv();

        // 3. 初始化真实的 Tool Registry，并挂载 read_file/write_file/bash 极简工具集。
        Registry registry = ToolRegistry.demoRegistry(workDir);

        // 4. 实例化核心引擎。这里开启慢思考，先规划再并发读取多个文件。
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, true);

        String prompt = args.length == 0
                ? """
                我当前目录下有 a.txt, b.txt, c.txt 三个文件。
                为了节省时间，请你在同一个 Action 阶段一次性发起三个 read_file 工具调用，同时读取这三个文件。
                然后将它们的内容综合起来，告诉我它们分别记录了什么领域的信息。
                """
                : String.join(" ", args);
        System.out.println("开始执行任务...");
        try {
            engine.run(prompt);
        } catch (RuntimeException e) {
            LOG.severe("引擎运行崩溃: " + e.getMessage());
            throw e;
        }

        LOG.info("架构蓝图搭建完毕，等待各核心模块注入！");
    }

    private static LLMProvider providerFromEnv() {
        String providerName = AppConfig.get("CLAW_PROVIDER", hasText(AppConfig.get("ZHIPU_API_KEY")) ? "zhipu" : "deepseek");
        return switch (providerName.toLowerCase()) {
            case "zhipu", "zhipu-openai" -> OpenAICompatibleProvider.newZhipuProvider(AppConfig.get("ZHIPU_MODEL", "glm-4.5-air"));
            case "deepseek" -> OpenAICompatibleProvider.newDeepSeekProvider(AppConfig.get("DEEPSEEK_MODEL", "deepseek-v4-flash"));
            default -> throw new IllegalArgumentException("未知 Provider: " + providerName);
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
