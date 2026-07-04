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

        // 4. 实例化核心引擎。这里关闭慢思考，体验 YOLO 急速模式。
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, false);

        String prompt = args.length == 0
                ? """
                请帮我执行以下操作：
                1. 先用 read_file 读取当前工作区根目录下的 EditTarget.java。
                2. 用 edit_file 做局部替换，不要重写整个文件：把 Greeter.message() 方法里的返回字符串从 "Hello from original edit target." 改成 "Hello from edit_file tool!"。
                3. 用 bash 执行 javac EditTarget.java && java EditTarget，确认输出已经变成 Hello from edit_file tool!。
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
