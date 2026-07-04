package lab.agentharness.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final boolean enableThinking;

    public AgentEngine(LLMProvider provider, Registry registry, Path workDir, boolean enableThinking) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.workDir = Objects.requireNonNull(workDir, "workDir").toAbsolutePath().normalize();
        this.enableThinking = enableThinking;
    }

    public static AgentEngine newAgentEngine(
            LLMProvider provider,
            Registry registry,
            Path workDir,
            boolean enableThinking) {
        return new AgentEngine(provider, registry, workDir, enableThinking);
    }

    /**
     * 启动 Agent 生命周期：初始化上下文，循环推理，执行工具，并把 Observation 写回上下文。
     */
    public void run(String userPrompt) {
        LOG.info("[Engine] 引擎启动，锁定工作区: " + workDir);
        LOG.info("[Engine] 慢思考模式 (Thinking Phase): " + enableThinking);

        List<Schema.Message> contextHistory = new ArrayList<>();
        contextHistory.add(Schema.Message.system("""
                You are go-tiny-claw, an expert coding assistant.
                You can use tools inside the workspace, but must respect the WorkDir boundary.
                Think step by step, call tools when needed, and stop when the task is complete.
                Do not install software, download remote artifacts, or change system-level configuration unless the user explicitly asks.
                If a required runtime is missing, report the limitation instead of trying to install it.
                Output plain text only. Do not use emoji, keycap symbols, decorative icons, or markdown tables.
                Use simple numbered lists like "1. ..." when summarizing steps.
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

            if (enableThinking) {
                LOG.info("[Engine][Phase 1] 剥夺工具访问权，强制进入慢思考与规划阶段...");
                Schema.Message thinkResp = provider.generate(thinkingContext(contextHistory), List.of());
                if (thinkResp == null) {
                    throw new IllegalStateException("Thinking 阶段模型返回空消息。");
                }

                if (thinkResp.content() != null && !thinkResp.content().isBlank()) {
                    System.out.println("内部思考 Trace: " + thinkResp.content());
                    contextHistory.add(thinkResp);
                }
            }

            LOG.info("[Engine][Phase 2] 恢复工具挂载，等待模型采取行动...");
            Schema.Message actionResp = provider.generate(contextHistory, availableTools);
            if (actionResp == null) {
                throw new IllegalStateException("Action 阶段模型返回空消息。");
            }

            contextHistory.add(actionResp);
            if (actionResp.content() != null && !actionResp.content().isBlank()) {
                System.out.println("对外回复: " + actionResp.content());
            }

            if (!actionResp.hasToolCalls()) {
                LOG.info("[Engine] 模型未请求调用工具，任务宣告完成。");
                break;
            }

            List<Schema.Message> observationMsgs = executeToolCallsInParallel(actionResp.toolCalls());
            LOG.info("[Engine] 所有并发工具执行完毕，开始聚合观察结果 (Observation)...");
            contextHistory.addAll(observationMsgs);
        }
    }

    private List<Schema.Message> executeToolCallsInParallel(List<Schema.ToolCall> toolCalls) {
        LOG.info("[Engine] 模型请求并发调用 " + toolCalls.size() + " 个工具...");

        int workerCount = Math.min(toolCalls.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<CompletableFuture<Schema.Message>> futures = new ArrayList<>(toolCalls.size());
            for (int i = 0; i < toolCalls.size(); i++) {
                final int index = i;
                final Schema.ToolCall toolCall = toolCalls.get(i);
                futures.add(CompletableFuture.supplyAsync(() -> executeOneTool(index, toolCall), executor));
            }

            List<Schema.Message> observations = new ArrayList<>(toolCalls.size());
            for (CompletableFuture<Schema.Message> future : futures) {
                observations.add(future.join());
            }
            return observations;
        } finally {
            executor.shutdown();
        }
    }

    private Schema.Message executeOneTool(int index, Schema.ToolCall toolCall) {
        try {
            LOG.info("  -> [Tool-" + index + "] 触发并行执行: " + toolCall.name() + ", 参数: " + toolCall.arguments());

            Schema.ToolResult result = registry.execute(toolCall);
            if (result.isError()) {
                LOG.warning("  -> [Tool-" + index + "] 工具执行报错: " + result.output());
            } else {
                LOG.info("  -> [Tool-" + index + "] 工具执行成功 (返回 " + bytes(result.output()) + " 字节)");
            }

            return Schema.Message.observation(toolCall.id(), result.output());
        } catch (CompletionException e) {
            throw e;
        } catch (RuntimeException e) {
            String output = "Error executing " + toolCall.name() + ": " + e.getMessage();
            LOG.warning("  -> [Tool-" + index + "] 工具执行异常: " + output);
            return Schema.Message.observation(toolCall.id(), output);
        }
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static List<Schema.Message> thinkingContext(List<Schema.Message> contextHistory) {
        List<Schema.Message> thinkingMessages = new ArrayList<>(contextHistory);
        thinkingMessages.add(Schema.Message.user("""
                【Thinking Phase 指令】
                当前阶段只是暂时隐藏工具列表，不代表系统没有工具。
                请只输出简短规划，不要说“没有工具”，不要编造工具名、命令、API 调用、观察结果或最终答案。
                等待 Action Phase 恢复工具后，再根据可用工具采取行动。
                """));
        return thinkingMessages;
    }
}
