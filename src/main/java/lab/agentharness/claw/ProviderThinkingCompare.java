package lab.agentharness.claw;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import lab.agentharness.engine.AgentEngine;
import lab.agentharness.provider.AnthropicCompatibleProvider;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.provider.OpenAICompatibleProvider;
import lab.agentharness.schema.Schema;
import lab.agentharness.tools.Registry;

/**
 * 真实 Provider 对比入口，用同一个天气任务分别验证关闭/开启慢思考时的行为差异。
 */
public final class ProviderThinkingCompare {
    private static final Logger LOG = Logger.getLogger(ProviderThinkingCompare.class.getName());

    private ProviderThinkingCompare() {
    }

    public static void main(String[] args) {
        String providerName = env("CLAW_PROVIDER", "deepseek");
        String prompt = args.length == 0
                ? "我想去北京跑步，帮我查查天气适合吗？"
                : String.join(" ", args);
        Path workDir = Path.of("").toAbsolutePath().normalize();

        runCase(providerName, workDir, prompt, false);
        runCase(providerName, workDir, prompt, true);
    }

    private static void runCase(String providerName, Path workDir, String prompt, boolean enableThinking) {
        System.out.println();
        System.out.println("==================================================");
        System.out.println("真实 Provider 测试：enableThinking = " + enableThinking);
        System.out.println("==================================================");

        LLMProvider provider = provider(providerName);
        Registry registry = new MockWeatherRegistry();
        AgentEngine engine = AgentEngine.newAgentEngine(provider, registry, workDir, enableThinking);

        try {
            engine.run(prompt);
        } catch (RuntimeException e) {
            LOG.severe("真实 Provider 测试失败: " + e.getMessage());
            throw e;
        }
    }

    private static LLMProvider provider(String providerName) {
        return switch (providerName.toLowerCase()) {
            case "deepseek" -> OpenAICompatibleProvider.newDeepSeekProvider(env("DEEPSEEK_MODEL", "deepseek-v4-flash"));
            case "zhipu", "zhipu-openai" -> OpenAICompatibleProvider.newZhipuProvider(env("ZHIPU_MODEL", "glm-4-flash"));
            case "claude", "anthropic" -> AnthropicCompatibleProvider.newClaudeProvider(env("ANTHROPIC_MODEL", "claude-sonnet-4-5"));
            case "zhipu-claude", "zhipu-anthropic" -> AnthropicCompatibleProvider.newZhipuClaudeProvider(env("ZHIPU_CLAUDE_MODEL", "glm-4.5"));
            default -> throw new IllegalArgumentException("未知 Provider: " + providerName);
        };
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 伪造天气工具注册表，用来测试真实模型能否正确发起和接收工具调用。
     */
    private static final class MockWeatherRegistry implements Registry {
        @Override
        public List<Schema.ToolDefinition> getAvailableTools() {
            return List.of(new Schema.ToolDefinition(
                    "get_weather",
                    "获取指定城市的当前天气情况。",
                    Schema.RawJson.of("""
                            {
                              "type": "object",
                              "properties": {
                                "city": {
                                  "type": "string",
                                  "description": "城市名称，例如北京"
                                }
                              },
                              "required": ["city"]
                            }
                            """)));
        }

        @Override
        public Schema.ToolResult execute(Schema.ToolCall call) {
            LOG.info("  -> [Mock 工具执行] 获取天气中，调用参数: " + call.arguments());
            return Schema.ToolResult.ok(
                    call.id(),
                    "API 返回：北京今天是晴天，气温 25 度，微风，空气质量良好，适合轻松跑步。");
        }
    }
}
