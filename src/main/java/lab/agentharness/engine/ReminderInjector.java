package lab.agentharness.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import lab.agentharness.schema.Schema;

/**
 * ReminderInjector 监控重复失败的工具调用，并在模型陷入 Doom Loop 时注入强力提醒。
 */
public final class ReminderInjector {
    private static final Logger LOG = Logger.getLogger(ReminderInjector.class.getName());
    private static final int DOOM_LOOP_THRESHOLD = 3;

    private final Map<String, Integer> consecutiveFailures = new HashMap<>();

    public static ReminderInjector newReminderInjector() {
        return new ReminderInjector();
    }

    /**
     * 分析本轮工具执行结果；连续同参失败达到阈值时返回一条高优先级 user reminder。
     */
    public synchronized Schema.Message checkAndInject(Schema.ToolCall toolCall, Schema.ToolResult result) {
        String fingerprint = fingerprint(toolCall);

        if (!result.isError()) {
            if (!consecutiveFailures.isEmpty()) {
                LOG.info("[Reminder] 工具执行成功，清空连续失败计数器。");
            }
            consecutiveFailures.clear();
            return null;
        }

        int failCount = consecutiveFailures.merge(fingerprint, 1, Integer::sum);
        LOG.warning("[Reminder] 监控到工具 " + toolCall.name()
                + " 执行失败，该参数特征连续失败次数: " + failCount);

        if (failCount < DOOM_LOOP_THRESHOLD) {
            return null;
        }

        LOG.warning("[Reminder] 触发死循环干预，注入强力修正指令。");
        return Schema.Message.user("""
                [SYSTEM REMINDER 警告]
                你似乎陷入了死循环。你刚刚连续 %d 次使用相同参数调用 `%s` 工具，并且都失败了。
                请立即停止这种无效的重试。你的注意力被当前报错过度吸引了。

                你必须立刻改变策略：
                1. 停止猜测和重复相同参数。
                2. 跳出当前局部思路，换一种工具、路径或验证方式。
                3. 如果无法通过系统工具解决当前问题，请直接结束任务并向用户说明需要什么人工帮助，而不是继续消耗 API 资源盲试。
                """.formatted(failCount, toolCall.name()));
    }

    private static String fingerprint(Schema.ToolCall toolCall) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(toolCall.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(toolCall.arguments()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 MD5", e);
        }
    }
}
