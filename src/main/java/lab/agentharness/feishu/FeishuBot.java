package lab.agentharness.feishu;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.LarkChannelFactory;
import com.lark.oapi.channel.config.LarkChannelOptions;
import com.lark.oapi.channel.config.LarkChannelOptions.WebhookOptions;
import com.lark.oapi.channel.model.BotIdentity;
import com.lark.oapi.channel.model.ChannelErrorEvent;
import com.lark.oapi.channel.model.NormalizedMessage;

import lab.agentharness.config.AppConfig;
import lab.agentharness.engine.AgentEngine;

/**
 * FeishuBot 封装飞书长连接事件监听，并把用户消息桥接到 AgentEngine。
 */
public final class FeishuBot {
    private static final Logger LOG = Logger.getLogger(FeishuBot.class.getName());

    private final LarkChannel channel;
    private final AgentEngine engine;
    private final ExecutorService runs;

    public FeishuBot(AgentEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.channel = createChannel();
        this.runs = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "feishu-agent-run");
            thread.setDaemon(true);
            return thread;
        });
        registerHandlers();
    }

    /**
     * 建立飞书 WebSocket 长连接，并阻塞当前线程保持进程存活。
     */
    public void startAndBlock() {
        BotIdentity identity = channel.connectSync();
        LOG.info("[Feishu] 长连接已建立，机器人: " + identity.getName() + " (" + identity.getOpenId() + ")");

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "feishu-bot-shutdown"));
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
        }
    }

    public void shutdown() {
        runs.shutdownNow();
        if (channel.isConnected()) {
            channel.disconnectSync();
        }
    }

    private void registerHandlers() {
        channel.<NormalizedMessage>on("message", this::handleMessage);
        channel.<ChannelErrorEvent>on("error", this::handleChannelError);
    }

    private void handleMessage(NormalizedMessage message) {
        String prompt = message.getContent() == null ? "" : message.getContent().trim();
        if (prompt.isBlank()) {
            return;
        }

        String chatId = message.getChatId();
        LOG.info("[Feishu] 收到会话 " + chatId + " 消息: " + prompt);
        runs.submit(() -> handleAgentRun(chatId, prompt));
    }

    private void handleAgentRun(String chatId, String prompt) {
        FeishuReporter reporter = new FeishuReporter(channel, chatId);
        reporter.onMessage("[Agent] 已收到任务，开始处理。");
        try {
            engine.run(prompt, reporter);
        } catch (RuntimeException e) {
            LOG.severe("[Feishu] Agent 运行崩溃: " + e.getMessage());
            reporter.onMessage("[Agent] 运行崩溃: " + e.getMessage());
        }
    }

    private void handleChannelError(ChannelErrorEvent event) {
        Throwable error = event.getError();
        LOG.warning("[Feishu] 事件处理失败: " + event.getEventName() + ", "
                + (error == null ? "unknown" : error.getMessage()));
    }

    private static LarkChannel createChannel() {
        String appId = AppConfig.get("FEISHU_APP_ID");
        String appSecret = AppConfig.get("FEISHU_APP_SECRET");
        if (!hasText(appId) || !hasText(appSecret)) {
            throw new IllegalStateException("请设置 FEISHU_APP_ID 和 FEISHU_APP_SECRET");
        }

        LarkChannelOptions.Builder builder = LarkChannelOptions.newBuilder(appId, appSecret)
                .transport("websocket");

        // 长连接内部仍会通过 SDK 的 EventDispatcher 处理事件，开启事件加密时需要传入这两个校验参数。
        String verifyToken = AppConfig.get("FEISHU_VERIFY_TOKEN");
        String encryptKey = AppConfig.get("FEISHU_ENCRYPT_KEY");
        if (hasText(verifyToken) || hasText(encryptKey)) {
            WebhookOptions webhookOptions = new WebhookOptions();
            webhookOptions.setVerificationToken(blankIfNull(verifyToken));
            webhookOptions.setEncryptKey(blankIfNull(encryptKey));
            builder.webhook(webhookOptions);
        }

        return LarkChannelFactory.createLarkChannel(builder.build());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
