package lab.agentharness.claw;

import java.util.List;

import lab.agentharness.provider.AnthropicCompatibleProvider;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.provider.OpenAICompatibleProvider;
import lab.agentharness.schema.Schema;

/**
 * Provider 冒烟测试入口，用环境变量选择真实模型适配器并发起一次无工具请求。
 */
public final class ProviderSmokeTest {
    private ProviderSmokeTest() {
    }

    public static void main(String[] args) {
        String providerName = env("CLAW_PROVIDER", "deepseek");
        String prompt = args.length == 0
                ? "北京天气怎么样？如果你不能获取实时天气，请直接说明。"
                : String.join(" ", args);

        LLMProvider provider = provider(providerName);
        Schema.Message response = provider.generate(List.of(
                Schema.Message.system("你是 go-tiny-claw 的 Provider 冒烟测试助手，请用中文简短回答。"),
                Schema.Message.user(prompt)), List.of());

        System.out.println("Provider: " + provider.name());
        System.out.println("Prompt: " + prompt);
        System.out.println("Response:");
        System.out.println(response.content());
        if (response.hasToolCalls()) {
            System.out.println("ToolCalls: " + response.toolCalls());
        }
    }

    private static LLMProvider provider(String providerName) {
        return switch (providerName.toLowerCase()) {
            case "deepseek" -> OpenAICompatibleProvider.newDeepSeekProvider(env("DEEPSEEK_MODEL", "deepseek-chat"));
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
}
