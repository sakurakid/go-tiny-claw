package lab.agentharness.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import lab.agentharness.schema.Schema;

/**
 * Compactor 负责在请求大模型前压缩临时上下文，避免工具大输出撑爆模型窗口。
 */
public final class Compactor {
    private static final Logger LOG = Logger.getLogger(Compactor.class.getName());

    private final int maxChars;
    private final int retainLastMessages;

    public Compactor(int maxChars, int retainLastMessages) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须大于 0");
        }
        if (retainLastMessages < 0) {
            throw new IllegalArgumentException("retainLastMessages 不能小于 0");
        }
        this.maxChars = maxChars;
        this.retainLastMessages = retainLastMessages;
    }

    public static Compactor newCompactor(int maxChars, int retainLastMessages) {
        return new Compactor(maxChars, retainLastMessages);
    }

    /**
     * 对准备发送给模型的消息做临时压缩；原始 Session 历史不会被修改。
     */
    public List<Schema.Message> compact(List<Schema.Message> messages) {
        Objects.requireNonNull(messages, "messages");

        int currentLength = estimateLength(messages);
        if (currentLength < maxChars) {
            return messages;
        }

        LOG.warning("[Compactor] 内存告警：当前上下文长度 (" + currentLength
                + " 字符) 超过阈值 (" + maxChars + ")，触发压缩清理...");

        List<Schema.Message> compacted = new ArrayList<>(messages.size());
        int protectStartIndex = Math.max(0, messages.size() - retainLastMessages);

        for (int i = 0; i < messages.size(); i++) {
            Schema.Message message = messages.get(i);
            if (message.role() == Schema.Role.SYSTEM) {
                compacted.add(message);
                continue;
            }

            boolean inWorkingMemory = i >= protectStartIndex;
            compacted.add(compactOne(message, inWorkingMemory));
        }

        int newLength = estimateLength(compacted);
        LOG.info("[Compactor] 压缩完成。上下文长度从 " + currentLength + " 降至 " + newLength + " 字符。");
        return compacted;
    }

    /**
     * 使用字符数估算上下文压力，避免引入复杂 BPE tokenizer 依赖。
     */
    public int estimateLength(List<Schema.Message> messages) {
        int length = 0;
        for (Schema.Message message : messages) {
            if (message.content() != null) {
                length += message.content().length();
            }
            for (Schema.ToolCall toolCall : message.toolCalls()) {
                length += safeLength(toolCall.name()) + safeLength(String.valueOf(toolCall.arguments()));
            }
        }
        return length;
    }

    private static Schema.Message compactOne(Schema.Message message, boolean inWorkingMemory) {
        String content = message.content();
        if (content == null || content.isBlank()) {
            return message;
        }

        if (isObservation(message)) {
            return compactObservation(message, content, inWorkingMemory);
        }

        if (message.role() == Schema.Role.ASSISTANT && !inWorkingMemory && content.length() > 200) {
            return withContent(message, "...[早期的推理思考过程已折叠]...");
        }

        return message;
    }

    private static Schema.Message compactObservation(
            Schema.Message message,
            String content,
            boolean inWorkingMemory) {
        if (!inWorkingMemory && content.length() > 200) {
            return withContent(message, "...[为了节省内存，早期的工具输出已被系统强制清理。原始长度: "
                    + content.length() + " 字符]...");
        }

        int maxKeep = 1000;
        if (inWorkingMemory && content.length() > maxKeep) {
            String head = content.substring(0, 500);
            String tail = content.substring(content.length() - 500);
            int omitted = content.length() - maxKeep;
            return withContent(message, head
                    + "\n\n...[内容过长，中间 " + omitted + " 字符已被系统截断]...\n\n"
                    + tail);
        }

        return message;
    }

    private static boolean isObservation(Schema.Message message) {
        return message.role() == Schema.Role.USER
                && message.toolCallId() != null
                && !message.toolCallId().isBlank();
    }

    private static Schema.Message withContent(Schema.Message message, String content) {
        return new Schema.Message(message.role(), content, message.toolCalls(), message.toolCallId());
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
