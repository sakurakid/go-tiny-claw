package lab.agentharness.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import lab.agentharness.provider.LLMProvider;
import lab.agentharness.schema.Schema;
import lab.agentharness.tools.Registry;

/**
 * AgentEngine 是 go-tiny-claw 的微型 OS 核心驱动，负责维持标准 ReAct 主循环。
 */
public final class AgentEngine {
    private static final Logger LOG = Logger.getLogger(AgentEngine.class.getName());
    private static final int MAX_TURNS = 8;

    private final LLMProvider provider;
    private final Registry registry;
    private final Path workDir;

    public AgentEngine(LLMProvider provider, Registry registry, Path workDir) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.workDir = Objects.requireNonNull(workDir, "workDir").toAbsolutePath().normalize();
    }

    public static AgentEngine newAgentEngine(LLMProvider provider, Registry registry, Path workDir) {
        return new AgentEngine(provider, registry, workDir);
    }

    /**
     * 启动 Agent 生命周期：初始化上下文，循环推理，执行工具，并把 Observation 写回上下文。
     */
    public void run(String userPrompt) {
        LOG.info("[Engine] 引擎启动，锁定工作区: " + workDir);

        List<Schema.Message> contextHistory = new ArrayList<>();
        contextHistory.add(Schema.Message.system("""
                You are go-tiny-claw, an expert coding assistant.
                You can use tools inside the workspace, but must respect the WorkDir boundary.
                Think step by step, call tools when needed, and stop when the task is complete.
                """));
        contextHistory.add(Schema.Message.user(userPrompt));

        int turnCount = 0;
        while (true) {
            turnCount++;
            if (turnCount > MAX_TURNS) {
                throw new IllegalStateException("Main Loop 超过最大轮数，疑似进入 Doom Loop。");
            }

            LOG.info("========== [Turn " + turnCount + "] 开始 ==========");

            List<Schema.ToolDefinition> availableTools = registry.getAvailableTools();
            LOG.info("[Engine] 正在思考 (Reasoning)...");

            Schema.Message responseMsg = provider.generate(contextHistory, availableTools);
            if (responseMsg == null) {
                throw new IllegalStateException("模型返回空消息。");
            }

            contextHistory.add(responseMsg);
            if (responseMsg.content() != null && !responseMsg.content().isBlank()) {
                System.out.println("模型: " + responseMsg.content());
            }

            if (!responseMsg.hasToolCalls()) {
                LOG.info("[Engine] 任务完成，退出循环。");
                break;
            }

            LOG.info("[Engine] 模型请求调用 " + responseMsg.toolCalls().size() + " 个工具...");
            for (Schema.ToolCall toolCall : responseMsg.toolCalls()) {
                LOG.info("  -> 执行工具: " + toolCall.name() + ", 参数: " + toolCall.arguments());

                Schema.ToolResult result = registry.execute(toolCall);
                if (result.isError()) {
                    LOG.warning("  -> 工具执行报错: " + result.output());
                } else {
                    LOG.info("  -> 工具执行成功 (返回 " + bytes(result.output()) + " 字节)");
                }

                Schema.Message observationMsg = Schema.Message.observation(toolCall.id(), result.output());
                contextHistory.add(observationMsg);
            }
        }
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
