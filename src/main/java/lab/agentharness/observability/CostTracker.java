package lab.agentharness.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import lab.agentharness.engine.Session;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.schema.Schema;

/**
 * CostTracker 是 LLMProvider 的装饰器：包住真实 Provider，额外记录耗时、Token 和会话累计费用。
 */
public final class CostTracker implements LLMProvider {
    private static final Logger LOG = Logger.getLogger(CostTracker.class.getName());
    private static final double USD_TO_CNY = 7.2;

    /**
     * 演示用价格表，单位是美元 / 1M tokens。
     */
    private static final Map<String, Pricing> PRICING = Map.ofEntries(
            Map.entry("glm-4.5-air", new Pricing(0.15, 0.15)),
            Map.entry("glm-4-flash", new Pricing(0.11, 0.11)),
            Map.entry("deepseek-v4-flash", new Pricing(0.28, 0.42)),
            Map.entry("claude-sonnet-4-5", new Pricing(3.00, 15.00)));

    private final LLMProvider nextProvider;
    private final String modelName;
    private final Session session;

    public CostTracker(LLMProvider nextProvider, String modelName, Session session) {
        this.nextProvider = Objects.requireNonNull(nextProvider, "nextProvider");
        this.modelName = normalizeModelName(modelName);
        this.session = session;
    }

    public static CostTracker newCostTracker(LLMProvider nextProvider, String modelName, Session session) {
        return new CostTracker(nextProvider, modelName, session);
    }

    @Override
    public String name() {
        return nextProvider.name() + "+cost-tracker";
    }

    @Override
    public Schema.Message generate(List<Schema.Message> messages, List<Schema.ToolDefinition> availableTools) {
        Instant start = Instant.now();
        try {
            Schema.Message response = nextProvider.generate(messages, availableTools);
            Duration latency = Duration.between(start, Instant.now());
            recordSuccess(response, latency);
            return response;
        } catch (RuntimeException e) {
            Duration latency = Duration.between(start, Instant.now());
            LOG.warning("[Tracker] API 调用失败 | 耗时: " + formatDuration(latency)
                    + " | 错误: " + e.getMessage());
            throw e;
        }
    }

    private void recordSuccess(Schema.Message response, Duration latency) {
        if (response == null || response.usage() == null) {
            LOG.info("[Tracker] API 调用完成，但 Provider 未返回 Usage | 耗时: " + formatDuration(latency));
            return;
        }

        int promptTokens = response.usage().promptTokens();
        int completionTokens = response.usage().completionTokens();
        double costCny = estimateCostCny(promptTokens, completionTokens);

        LOG.info(String.format(Locale.ROOT,
                "[Tracker] API 调用完成 | 耗时: %s | 输入: %d tk | 输出: %d tk | 估算费用: CNY %.6f",
                formatDuration(latency),
                promptTokens,
                completionTokens,
                costCny));

        if (session != null) {
            session.recordUsage(promptTokens, completionTokens, costCny);
            LOG.info(String.format(Locale.ROOT,
                    "[Tracker] 当前会话 (%s) 累计: 输入 %d tk | 输出 %d tk | 费用 CNY %.6f",
                    session.id(),
                    session.totalPromptTokens(),
                    session.totalCompletionTokens(),
                    session.totalCostCny()));
        }
    }

    private double estimateCostCny(int promptTokens, int completionTokens) {
        Pricing pricing = PRICING.get(modelName);
        if (pricing == null) {
            LOG.info("[Tracker] 未配置模型价格，费用按 0 统计: " + modelName);
            return 0.0;
        }

        double usd = (promptTokens * pricing.inputUsdPerMillion()
                + completionTokens * pricing.outputUsdPerMillion()) / 1_000_000.0;
        return usd * USD_TO_CNY;
    }

    private static String normalizeModelName(String modelName) {
        return modelName == null ? "" : modelName.toLowerCase(Locale.ROOT).trim();
    }

    private static String formatDuration(Duration duration) {
        return duration.toMillis() + " ms";
    }

    private record Pricing(double inputUsdPerMillion, double outputUsdPerMillion) {
    }
}
