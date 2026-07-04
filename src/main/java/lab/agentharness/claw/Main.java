package lab.agentharness.claw;

import java.nio.file.Path;
import java.util.logging.Logger;

import lab.agentharness.engine.AgentEngine;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.provider.OpenAICompatibleProvider;
import lab.agentharness.tools.ReadFileTool;
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

        // 2. 初始化真实的大脑。默认优先智谱；也可以设置 CLAW_PROVIDER=deepseek 切到 DeepSeek。
        LLMProvider provider = providerFromEnv();

        // 3. 初始化真实的 Tool Registry，并挂载 read_file 工具。
        Registry registry = ToolRegistry.newRegistry();
        registry.register(new ReadFileTool(workDir));

        // 4. 实例化核心引擎。读取单个文件是简单任务，先关闭慢思考以减少一次模型调用。
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, false);

        String prompt = args.length == 0
                ? "请调用工具读取一下当前工作区目录下 hello.txt 文件的内容，并用一句话向我总结它说了什么。"
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
        String providerName = env("CLAW_PROVIDER", hasText(System.getenv("ZHIPU_API_KEY")) ? "zhipu" : "deepseek");
        return switch (providerName.toLowerCase()) {
            case "zhipu", "zhipu-openai" -> OpenAICompatibleProvider.newZhipuProvider(env("ZHIPU_MODEL", "glm-4.5-air"));
            case "deepseek" -> OpenAICompatibleProvider.newDeepSeekProvider(env("DEEPSEEK_MODEL", "deepseek-v4-flash"));
            default -> throw new IllegalArgumentException("未知 Provider: " + providerName);
        };
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return hasText(value) ? value : defaultValue;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
