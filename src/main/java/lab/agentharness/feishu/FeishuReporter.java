package lab.agentharness.feishu;

import java.util.logging.Logger;

import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.model.SendInput;

import lab.agentharness.engine.Reporter;

/**
 * FeishuReporter 把 AgentEngine 的状态事件转成飞书会话里的文本消息。
 */
public final class FeishuReporter implements Reporter {
    private static final Logger LOG = Logger.getLogger(FeishuReporter.class.getName());
    private static final int MAX_MESSAGE_CHARS = 3500;

    private final LarkChannel channel;
    private final String chatId;

    public FeishuReporter(LarkChannel channel, String chatId) {
        this.channel = channel;
        this.chatId = chatId;
    }

    @Override
    public void onThinking() {
        sendMsg("[Thinking] 模型正在慢思考...");
    }

    @Override
    public void onToolCall(String toolName, String args) {
        sendMsg("[ToolCall] 正在执行工具: " + toolName + "\n参数: " + trimForHuman(args, 600));
    }

    @Override
    public void onToolResult(String toolName, String result, boolean isError) {
        if (isError) {
            sendMsg("[ToolResult] 执行报错: " + toolName + "\n" + trimForHuman(result, 1000));
            return;
        }
        sendMsg("[ToolResult] 执行成功: " + toolName);
    }

    @Override
    public void onMessage(String content) {
        sendMsg(content);
    }

    /**
     * 飞书单条消息有长度限制，Reporter 只给人类看摘要，完整 Observation 仍会回到模型上下文。
     */
    private synchronized void sendMsg(String text) {
        try {
            channel.sendSync(chatId, SendInput.text(trimForHuman(text, MAX_MESSAGE_CHARS)));
        } catch (RuntimeException e) {
            LOG.warning("[Feishu] 发送消息失败: " + e.getMessage());
        }
    }

    private static String trimForHuman(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars
                ? text
                : text.substring(0, maxChars) + "... (已截断)";
    }
}
