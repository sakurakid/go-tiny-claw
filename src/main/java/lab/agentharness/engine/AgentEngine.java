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
import java.util.function.Function;
import java.util.logging.Logger;

import lab.agentharness.context.Compactor;
import lab.agentharness.context.PromptComposer;
import lab.agentharness.context.RecoveryManager;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.schema.Schema;
import lab.agentharness.tools.Registry;
import lab.agentharness.tools.ToolRegistry;

/**
 * AgentEngine 是 go-tiny-claw 的微型 OS 核心驱动，负责维持标准 ReAct 主循环。
 */
public final class AgentEngine {
    private static final Logger LOG = Logger.getLogger(AgentEngine.class.getName());
    private static final int DEFAULT_MAX_TURNS = 12;
    private static final int PLAN_MODE_MAX_TURNS = 32;
    private static final int SESSION_CONTEXT_LIMIT = 20;
    private static final int COMPACTION_MAX_CHARS = 3000;
    private static final int COMPACTION_RETAIN_LAST_MESSAGES = 6;

    private final LLMProvider provider;
    private final Registry registry;
    private final Path workDir;
    private final boolean enableThinking;
    private final boolean planMode;
    private final PromptComposer composer;
    private final Compactor compactor;
    private final RecoveryManager recoveryManager;
    private final ReminderInjector reminderInjector;
    private final Function<Path, Registry> sessionRegistryFactory;

    public AgentEngine(LLMProvider provider, Registry registry, Path workDir, boolean enableThinking) {
        this(provider, registry, workDir, enableThinking, false);
    }

    public AgentEngine(LLMProvider provider, Registry registry, Path workDir, boolean enableThinking, boolean planMode) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.workDir = Objects.requireNonNull(workDir, "workDir").toAbsolutePath().normalize();
        this.enableThinking = enableThinking;
        this.planMode = planMode;
        this.composer = new PromptComposer(this.workDir, this.planMode);
        this.compactor = Compactor.newCompactor(COMPACTION_MAX_CHARS, COMPACTION_RETAIN_LAST_MESSAGES);
        this.recoveryManager = RecoveryManager.newRecoveryManager();
        this.reminderInjector = ReminderInjector.newReminderInjector();
        this.sessionRegistryFactory = ignored -> this.registry;
    }

    private AgentEngine(
            LLMProvider provider,
            Function<Path, Registry> sessionRegistryFactory,
            boolean enableThinking,
            boolean planMode) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.registry = null;
        this.workDir = null;
        this.enableThinking = enableThinking;
        this.planMode = planMode;
        this.composer = null;
        this.compactor = Compactor.newCompactor(COMPACTION_MAX_CHARS, COMPACTION_RETAIN_LAST_MESSAGES);
        this.recoveryManager = RecoveryManager.newRecoveryManager();
        this.reminderInjector = ReminderInjector.newReminderInjector();
        this.sessionRegistryFactory = Objects.requireNonNull(sessionRegistryFactory, "sessionRegistryFactory");
    }

    public static AgentEngine newAgentEngine(
            LLMProvider provider,
            Registry registry,
            Path workDir,
            boolean enableThinking) {
        return new AgentEngine(provider, registry, workDir, enableThinking);
    }

    public static AgentEngine newAgentEngine(
            LLMProvider provider,
            Registry registry,
            Path workDir,
            boolean enableThinking,
            boolean planMode) {
        return new AgentEngine(provider, registry, workDir, enableThinking, planMode);
    }

    /**
     * 创建无固定工作区的引擎，具体工具边界由每个 Session 自己决定。
     */
    public static AgentEngine newSessionAgentEngine(LLMProvider provider, boolean enableThinking) {
        return newSessionAgentEngine(provider, enableThinking, false);
    }

    public static AgentEngine newSessionAgentEngine(LLMProvider provider, boolean enableThinking, boolean planMode) {
        return new AgentEngine(provider, ToolRegistry::demoRegistry, enableThinking, planMode);
    }

    public boolean planMode() {
        return planMode;
    }

    /**
     * 启动 Agent 生命周期：初始化上下文，循环推理，执行工具，并把 Observation 写回上下文。
     */
    public void run(String userPrompt) {
        run(userPrompt, new TerminalReporter());
    }

    /**
     * 启动 Agent 生命周期，并通过 Reporter 把关键状态输出给外部展示层。
     */
    public void run(String userPrompt, Reporter reporter) {
        if (registry == null || workDir == null || composer == null) {
            throw new IllegalStateException("当前 AgentEngine 未绑定固定工作区，请改用 run(Session, Reporter)。");
        }

        LOG.info("[Engine] 引擎启动，锁定工作区: " + workDir);
        LOG.info("[Engine] 慢思考模式 (Thinking Phase): " + enableThinking);
        LOG.info("[Engine] 计划模式 (Plan Mode): " + planMode);

        List<Schema.Message> contextHistory = new ArrayList<>();
        contextHistory.add(composer.build());
        contextHistory.add(Schema.Message.user(userPrompt));

        int turnCount = 0;
        while (true) {
            turnCount++;
            if (turnCount > maxTurns()) {
                throw new IllegalStateException("Main Loop 超过最大轮数，疑似进入 Doom Loop。");
            }

            LOG.info("========== [Turn " + turnCount + "] 开始 ==========");
            List<Schema.ToolDefinition> availableTools = registry.getAvailableTools();

            if (enableThinking) {
                LOG.info("[Engine][Phase 1] 剥夺工具访问权，强制进入慢思考与规划阶段...");
                callReporter(reporter, "onThinking", () -> reporter.onThinking());
                Schema.Message thinkResp = provider.generate(compactForProvider(thinkingContext(contextHistory)), List.of());
                if (thinkResp == null) {
                    throw new IllegalStateException("Thinking 阶段模型返回空消息。");
                }

                if (thinkResp.content() != null && !thinkResp.content().isBlank()) {
                    contextHistory.add(thinkResp);
                }
            }

            LOG.info("[Engine][Phase 2] 恢复工具挂载，等待模型采取行动...");
            Schema.Message actionResp = provider.generate(compactForProvider(contextHistory), availableTools);
            if (actionResp == null) {
                throw new IllegalStateException("Action 阶段模型返回空消息。");
            }

            contextHistory.add(actionResp);
            if (actionResp.content() != null && !actionResp.content().isBlank()) {
                callReporter(reporter, "onMessage", () -> reporter.onMessage(actionResp.content()));
            }

            if (!actionResp.hasToolCalls()) {
                LOG.info("[Engine] 模型未请求调用工具，任务宣告完成。");
                break;
            }

            List<ToolExecution> executions = executeToolCallsInParallel(registry, actionResp.toolCalls(), reporter);
            LOG.info("[Engine] 所有并发工具执行完毕，开始聚合观察结果 (Observation)...");
            contextHistory.addAll(observations(executions));
            contextHistory.addAll(reminders(executions));
        }
    }

    /**
     * 从 Session 恢复短期记忆运行 Agent；用户输入需先由外层 append 到 Session。
     */
    public void run(Session session, Reporter reporter) {
        Objects.requireNonNull(session, "session");

        Path sessionWorkDir = session.workDir();
        Registry activeRegistry = registryFor(sessionWorkDir);
        PromptComposer activeComposer = new PromptComposer(sessionWorkDir, planMode);
        Schema.Message systemMessage = activeComposer.build();

        LOG.info("[Engine] 唤醒会话 [" + session.id() + "]，锁定工作区: " + sessionWorkDir);
        LOG.info("[Engine] 慢思考模式 (Thinking Phase): " + enableThinking);
        LOG.info("[Engine] 计划模式 (Plan Mode): " + planMode);

        int turnCount = 0;
        while (true) {
            turnCount++;
            if (turnCount > maxTurns()) {
                throw new IllegalStateException("Main Loop 超过最大轮数，疑似进入 Doom Loop。");
            }

            List<Schema.Message> contextHistory = new ArrayList<>();
            contextHistory.add(systemMessage);
            contextHistory.addAll(session.getWorkingMemory(SESSION_CONTEXT_LIMIT));

            LOG.info("========== [Session " + session.id() + " / Turn " + turnCount + "] 开始 ==========");
            LOG.info("[Engine] Working Memory 消息数: " + (contextHistory.size() - 1)
                    + " / Session 历史总数: " + session.historySize());

            List<Schema.ToolDefinition> availableTools = activeRegistry.getAvailableTools();

            if (enableThinking) {
                LOG.info("[Engine][Phase 1] 剥夺工具访问权，强制进入慢思考与规划阶段...");
                callReporter(reporter, "onThinking", () -> reporter.onThinking());
                Schema.Message thinkResp = provider.generate(compactForProvider(thinkingContext(contextHistory)), List.of());
                if (thinkResp == null) {
                    throw new IllegalStateException("Thinking 阶段模型返回空消息。");
                }

                if (thinkResp.content() != null && !thinkResp.content().isBlank()) {
                    session.append(thinkResp);
                    contextHistory.add(thinkResp);
                }
            }

            LOG.info("[Engine][Phase 2] 恢复工具挂载，等待模型采取行动...");
            Schema.Message actionResp = provider.generate(compactForProvider(contextHistory), availableTools);
            if (actionResp == null) {
                throw new IllegalStateException("Action 阶段模型返回空消息。");
            }

            session.append(actionResp);
            contextHistory.add(actionResp);
            if (actionResp.content() != null && !actionResp.content().isBlank()) {
                callReporter(reporter, "onMessage", () -> reporter.onMessage(actionResp.content()));
            }

            if (!actionResp.hasToolCalls()) {
                LOG.info("[Engine] 模型未请求调用工具，本次会话任务挂起等待下一条人类指令。");
                break;
            }

            List<ToolExecution> executions =
                    executeToolCallsInParallel(activeRegistry, actionResp.toolCalls(), reporter);
            LOG.info("[Engine] 所有并发工具执行完毕，开始聚合观察结果 (Observation)...");
            session.append(observations(executions));
            session.append(reminders(executions));
        }
    }

    private Registry registryFor(Path sessionWorkDir) {
        Registry activeRegistry = sessionRegistryFactory.apply(sessionWorkDir);
        return Objects.requireNonNull(activeRegistry, "session registry");
    }

    private int maxTurns() {
        return planMode ? PLAN_MODE_MAX_TURNS : DEFAULT_MAX_TURNS;
    }

    private List<Schema.Message> compactForProvider(List<Schema.Message> messages) {
        return compactor.compact(messages);
    }

    private List<ToolExecution> executeToolCallsInParallel(
            Registry activeRegistry,
            List<Schema.ToolCall> toolCalls,
            Reporter reporter) {
        LOG.info("[Engine] 模型请求并发调用 " + toolCalls.size() + " 个工具...");

        int workerCount = Math.min(toolCalls.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<CompletableFuture<ToolExecution>> futures = new ArrayList<>(toolCalls.size());
            for (int i = 0; i < toolCalls.size(); i++) {
                final int index = i;
                final Schema.ToolCall toolCall = toolCalls.get(i);
                futures.add(CompletableFuture.supplyAsync(
                        () -> executeOneTool(activeRegistry, index, toolCall, reporter), executor));
            }

            List<ToolExecution> executions = new ArrayList<>(toolCalls.size());
            for (CompletableFuture<ToolExecution> future : futures) {
                executions.add(future.join());
            }
            return executions;
        } finally {
            executor.shutdown();
        }
    }

    private ToolExecution executeOneTool(
            Registry activeRegistry,
            int index,
            Schema.ToolCall toolCall,
            Reporter reporter) {
        try {
            LOG.info("  -> [Tool-" + index + "] 触发并行执行: " + toolCall.name() + ", 参数: " + toolCall.arguments());
            callReporter(reporter, "onToolCall",
                    () -> reporter.onToolCall(toolCall.name(), String.valueOf(toolCall.arguments())));

            Schema.ToolResult result = activeRegistry.execute(toolCall);
            String finalOutput = result.output();
            if (result.isError()) {
                finalOutput = recoveryManager.analyzeAndInject(toolCall.name(), result.output());
                LOG.warning("  -> [Tool-" + index + "] 工具执行报错，已尝试注入救援指南: " + finalOutput);
            } else {
                LOG.info("  -> [Tool-" + index + "] 工具执行成功 (返回 " + bytes(result.output()) + " 字节)");
            }
            String displayOutput = truncateForReporter(finalOutput);
            callReporter(reporter, "onToolResult",
                    () -> reporter.onToolResult(toolCall.name(), displayOutput, result.isError()));

            Schema.ToolResult finalResult = result.isError()
                    ? Schema.ToolResult.error(toolCall.id(), finalOutput)
                    : Schema.ToolResult.ok(toolCall.id(), finalOutput);
            return new ToolExecution(toolCall, finalResult, Schema.Message.observation(toolCall.id(), finalOutput));
        } catch (CompletionException e) {
            throw e;
        } catch (RuntimeException e) {
            String output = "Error executing " + toolCall.name() + ": " + e.getMessage();
            output = recoveryManager.analyzeAndInject(toolCall.name(), output);
            LOG.warning("  -> [Tool-" + index + "] 工具执行异常，已尝试注入救援指南: " + output);
            String displayOutput = truncateForReporter(output);
            callReporter(reporter, "onToolResult",
                    () -> reporter.onToolResult(toolCall.name(), displayOutput, true));
            Schema.ToolResult finalResult = Schema.ToolResult.error(toolCall.id(), output);
            return new ToolExecution(toolCall, finalResult, Schema.Message.observation(toolCall.id(), output));
        }
    }

    private List<Schema.Message> observations(List<ToolExecution> executions) {
        return executions.stream()
                .map(ToolExecution::observation)
                .toList();
    }

    private List<Schema.Message> reminders(List<ToolExecution> executions) {
        List<Schema.Message> reminders = new ArrayList<>();
        for (ToolExecution execution : executions) {
            Schema.Message reminder = reminderInjector.checkAndInject(execution.toolCall(), execution.result());
            if (reminder != null) {
                reminders.add(reminder);
            }
        }
        return reminders;
    }

    private static void callReporter(Reporter reporter, String eventName, Runnable event) {
        if (reporter == null) {
            return;
        }
        try {
            event.run();
        } catch (RuntimeException e) {
            LOG.warning("[Reporter] " + eventName + " 回调失败: " + e.getMessage());
        }
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String truncateForReporter(String text) {
        if (text == null) {
            return "";
        }
        int maxChars = 200;
        return text.length() <= maxChars
                ? text
                : text.substring(0, maxChars) + "... (已截断)";
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

    private record ToolExecution(
            Schema.ToolCall toolCall,
            Schema.ToolResult result,
            Schema.Message observation) {
    }
}
