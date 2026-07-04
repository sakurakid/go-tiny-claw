package lab.agentharness.context;

/**
 * Token 水位监控占位实现，当前用粗略字符数估算，后续可替换为真实 tokenizer。
 */
public final class TokenMonitor {
    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
