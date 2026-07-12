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
import lab.agentharness.observability.Trace;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.schema.Schema;
import lab.agentharness.tools.AgentRunner;
import lab.agentharness.tools.Registry;
import lab.agentharness.tools.ToolRegistry;

/**
 * AgentEngine 是 go-tiny-claw 的微型 OS 核心驱动，负责维持标准 ReAct 主循环。
 */
public final class AgentEngine implements AgentRunner {
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
     * RunSub 为 SubagentTool 启动一个一次性隔离子循环，只能使用调用方传入的受限 Registry。
     */
    @Override
    public String runSub(String taskPrompt, Registry readOnlyRegistry, Object reporter) {
        Objects.requireNonNull(taskPrompt, "taskPrompt");
        Objects.requireNonNull(readOnlyRegistry, "readOnlyRegistry");

        Trace.Span subSpan = Trace.startSpan("Subagent.Run");
        subSpan.addAttribute("task_prompt_preview", truncateForReporter(taskPrompt));
        try {
            Reporter subReporter = reporter instanceof Reporter casted ? casted : null;
            List<Schema.Message> contextHistory = new ArrayList<>();
            contextHistory.add(Schema.Message.system("""
                    你是一个专门负责深度探索的探路者 (Explorer Subagent)。
                    你的任务是根据主架构师的指令，在当前工作区内仔细阅读代码、查阅文件、搜索线索，并返回一段高度压缩的探索报告。

                    【核心纪律】
                    1. 你必须依靠内置工具寻找答案，禁止凭空猜测。
                    2. 你只能执行探索和读取，不要尝试修改、创建或删除文件。
                    3. 如果没有找到确切答案，继续使用 read_file 或 bash 搜索。
                    4. 找到明确线索后，停止调用工具，直接输出纯文本汇报。汇报要短、准、可供主 Agent 继续决策。
                    """));
            contextHistory.add(Schema.Message.user(taskPrompt));

            int maxSubTurns = 10;
            for (int turn = 1; turn <= maxSubTurns; turn++) {
                try (Trace.Span turnSpan = Trace.startSpan("Subagent.Turn-" + turn)) {
                    turnSpan.addAttribute("turn", turn);
                    turnSpan.addAttribute("history_message_count", contextHistory.size());
                    LOG.info("[Subagent] ========== [Turn " + turn + "] 开始 ==========");
                    Schema.Message actionResp = generateWithTrace(
                            "LLM.SubagentAction",
                            compactForProvider(contextHistory),
                            readOnlyRegistry.getAvailableTools());
                    if (actionResp == null) {
                        throw new IllegalStateException("子智能体 Action 阶段模型返回空消息。");
                    }

                    contextHistory.add(actionResp);
                    turnSpan.addAttribute("tool_call_count", actionResp.toolCalls().size());
                    if (!actionResp.hasToolCalls()) {
                        String summary = actionResp.content();
                        return summary == null || summary.isBlank()
                                ? "子智能体没有返回有效摘要。"
                                : summary;
                    }

                    List<ToolExecution> executions =
                            executeToolCallsInParallel(readOnlyRegistry, actionResp.toolCalls(), prefixReporter(subReporter));
                    contextHistory.addAll(observations(executions));
                }
            }

            throw new IllegalStateException("子智能体探索过于深入，超过 " + maxSubTurns + " 轮被强制召回，请给它更明确的指令。");
        } catch (RuntimeException e) {
            subSpan.addAttribute("error", e.getMessage());
            throw e;
        } finally {
            subSpan.close();
        }
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

        Trace.Span rootSpan = Trace.startSpan("Agent.Run");
        rootSpan.addAttribute("session_id", "cli");
        rootSpan.addAttribute("work_dir", workDir.toString());
        rootSpan.addAttribute("plan_mode", planMode);
        rootSpan.addAttribute("enable_thinking", enableThinking);
        try {
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

                try (Trace.Span turnSpan = Trace.startSpan("Turn-" + turnCount)) {
                    turnSpan.addAttribute("turn", turnCount);
                    LOG.info("========== [Turn " + turnCount + "] 开始 ==========");
                    List<Schema.ToolDefinition> availableTools = registry.getAvailableTools();
                    turnSpan.addAttribute("available_tools_count", availableTools.size());
                    turnSpan.addAttribute("history_message_count", contextHistory.size());

                    if (enableThinking) {
                        LOG.info("[Engine][Phase 1] 剥夺工具访问权，强制进入慢思考与规划阶段...");
                        callReporter(reporter, "onThinking", () -> reporter.onThinking());
                        Schema.Message thinkResp = generateWithTrace(
                                "LLM.Thinking",
                                compactForProvider(thinkingContext(contextHistory)),
                                List.of());
                        if (thinkResp == null) {
                            throw new IllegalStateException("Thinking 阶段模型返回空消息。");
                        }

                        if (thinkResp.content() != null && !thinkResp.content().isBlank()) {
                            contextHistory.add(thinkResp);
                        }
                    }

                    LOG.info("[Engine][Phase 2] 恢复工具挂载，等待模型采取行动...");
                    Schema.Message actionResp = generateWithTrace("LLM.Action", compactForProvider(contextHistory), availableTools);
                    if (actionResp == null) {
                        throw new IllegalStateException("Action 阶段模型返回空消息。");
                    }

                    contextHistory.add(actionResp);
                    turnSpan.addAttribute("tool_call_count", actionResp.toolCalls().size());
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
        } catch (RuntimeException e) {
            rootSpan.addAttribute("error", e.getMessage());
            throw e;
        } finally {
            rootSpan.close();
            exportTrace(rootSpan, workDir, "cli");
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

        Trace.Span rootSpan = Trace.startSpan("Agent.Run");
        rootSpan.addAttribute("session_id", session.id());
        rootSpan.addAttribute("work_dir", sessionWorkDir.toString());
        rootSpan.addAttribute("plan_mode", planMode);
        rootSpan.addAttribute("enable_thinking", enableThinking);
        try {
            LOG.info("[Engine] 唤醒会话 [" + session.id() + "]，锁定工作区: " + sessionWorkDir);
            LOG.info("[Engine] 慢思考模式 (Thinking Phase): " + enableThinking);
            LOG.info("[Engine] 计划模式 (Plan Mode): " + planMode);

            int turnCount = 0;
            while (true) {
                turnCount++;
                if (turnCount > maxTurns()) {
                    throw new IllegalStateException("Main Loop 超过最大轮数，疑似进入 Doom Loop。");
                }

                try (Trace.Span turnSpan = Trace.startSpan("Turn-" + turnCount)) {
                    turnSpan.addAttribute("turn", turnCount);
                    turnSpan.addAttribute("session_history_size", session.historySize());
                    List<Schema.Message> contextHistory = new ArrayList<>();
                    contextHistory.add(systemMessage);
                    contextHistory.addAll(session.getWorkingMemory(SESSION_CONTEXT_LIMIT));

                    LOG.info("========== [Session " + session.id() + " / Turn " + turnCount + "] 开始 ==========");
                    LOG.info("[Engine] Working Memory 消息数: " + (contextHistory.size() - 1)
                            + " / Session 历史总数: " + session.historySize());
                    turnSpan.addAttribute("working_memory_count", contextHistory.size() - 1);

                    List<Schema.ToolDefinition> availableTools = activeRegistry.getAvailableTools();
                    turnSpan.addAttribute("available_tools_count", availableTools.size());

                    if (enableThinking) {
                        LOG.info("[Engine][Phase 1] 剥夺工具访问权，强制进入慢思考与规划阶段...");
                        callReporter(reporter, "onThinking", () -> reporter.onThinking());
                        Schema.Message thinkResp = generateWithTrace(
                                "LLM.Thinking",
                                compactForProvider(thinkingContext(contextHistory)),
                                List.of());
                        if (thinkResp == null) {
                            throw new IllegalStateException("Thinking 阶段模型返回空消息。");
                        }

                        if (thinkResp.content() != null && !thinkResp.content().isBlank()) {
                            session.append(thinkResp);
                            contextHistory.add(thinkResp);
                        }
                    }

                    LOG.info("[Engine][Phase 2] 恢复工具挂载，等待模型采取行动...");
                    Schema.Message actionResp = generateWithTrace("LLM.Action", compactForProvider(contextHistory), availableTools);
                    if (actionResp == null) {
                        throw new IllegalStateException("Action 阶段模型返回空消息。");
                    }

                    session.append(actionResp);
                    contextHistory.add(actionResp);
                    turnSpan.addAttribute("tool_call_count", actionResp.toolCalls().size());
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
        } catch (RuntimeException e) {
            rootSpan.addAttribute("error", e.getMessage());
            throw e;
        } finally {
            rootSpan.close();
            exportTrace(rootSpan, sessionWorkDir, session.id());
        }
    }

    private Registry registryFor(Path sessionWorkDir) {
        Registry activeRegistry = sessionRegistryFactory.apply(sessionWorkDir);
        return Objects.requireNonNull(activeRegistry, "session registry");
    }

    private void exportTrace(Trace.Span rootSpan, Path traceWorkDir, String sessionId) {
        try {
            Path traceFile = Trace.exportTraceToFile(rootSpan, traceWorkDir, sessionId);
            LOG.info("[Tracing] 本次任务的执行回放链路已保存: " + traceFile);
        } catch (RuntimeException | java.io.IOException e) {
            LOG.warning("[Tracing] 导出链路追踪文件失败: " + e.getMessage());
        }
    }

    private int maxTurns() {
        return planMode ? PLAN_MODE_MAX_TURNS : DEFAULT_MAX_TURNS;
    }

    private List<Schema.Message> compactForProvider(List<Schema.Message> messages) {
        return compactor.compact(messages);
    }

    /**
     * 包装所有 LLM 调用的统一埋点入口，记录上下文规模、可用工具数、模型输出和 Usage。
     */
    private Schema.Message generateWithTrace(
            String spanName,
            List<Schema.Message> messages,
            List<Schema.ToolDefinition> availableTools) {
        Trace.Span span = Trace.startSpan(spanName);
        try {
            span.addAttribute("context_message_count", messages == null ? 0 : messages.size());
            span.addAttribute("context_chars", messages == null ? 0 : compactor.estimateLength(messages));
            span.addAttribute("available_tools_count", availableTools == null ? 0 : availableTools.size());

            Schema.Message response = provider.generate(messages, availableTools);
            if (response != null) {
                span.addAttribute("response_chars", response.content() == null ? 0 : response.content().length());
                span.addAttribute("tool_call_count", response.toolCalls().size());
                if (response.usage() != null) {
                    span.addAttribute("prompt_tokens", response.usage().promptTokens());
                    span.addAttribute("completion_tokens", response.usage().completionTokens());
                    span.addAttribute("total_tokens", response.usage().totalTokens());
                }
            }
            return response;
        } catch (RuntimeException e) {
            span.addAttribute("error", e.getMessage());
            throw e;
        } finally {
            span.close();
        }
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
                final Trace.Span parentSpan = Trace.currentSpan();
                futures.add(CompletableFuture.supplyAsync(
                        () -> Trace.callWithParent(parentSpan,
                                () -> executeOneTool(activeRegistry, index, toolCall, reporter)),
                        executor));
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

    private Reporter prefixReporter(Reporter reporter) {
        if (reporter == null) {
            return null;
        }

        return new Reporter() {
            @Override
            public void onThinking() {
                reporter.onThinking();
            }

            @Override
            public void onToolCall(String toolName, String args) {
                reporter.onToolCall("[Subagent] " + toolName, args);
            }

            @Override
            public void onToolResult(String toolName, String result, boolean isError) {
                reporter.onToolResult("[Subagent] " + toolName, result, isError);
            }

            @Override
            public void onMessage(String content) {
                reporter.onMessage("[Subagent]\n" + content);
            }
        };
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
