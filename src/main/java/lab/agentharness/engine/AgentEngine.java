package lab.agentharness.engine;

import java.util.Objects;
import java.util.logging.Logger;

import lab.agentharness.context.ContextManager;
import lab.agentharness.context.RuntimeContext;
import lab.agentharness.provider.AgentRequest;
import lab.agentharness.provider.ModelProvider;
import lab.agentharness.provider.ModelResponse;
import lab.agentharness.thinking.ThinkingModule;
import lab.agentharness.tools.ToolRegistry;

public final class AgentEngine {
    private static final Logger LOG = Logger.getLogger(AgentEngine.class.getName());

    private final ModelProvider provider;
    private final ToolRegistry registry;
    private final ContextManager contextManager;
    private final ThinkingModule thinkingModule;

    public AgentEngine(
            ModelProvider provider,
            ToolRegistry registry,
            ContextManager contextManager,
            ThinkingModule thinkingModule) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
        this.thinkingModule = Objects.requireNonNull(thinkingModule, "thinkingModule");
    }

    public void run(String task) {
        LOG.info("MainLoop: 创建一次最小 ReAct 运行上下文");

        RuntimeContext runtimeContext = contextManager.build(task, registry.toolSpecs());
        AgentRequest request = new AgentRequest(
                task,
                runtimeContext.systemPrompt(),
                runtimeContext.toolSpecs(),
                runtimeContext.memorySnapshot(),
                thinkingModule.beforeActionInstruction());

        ModelResponse response = provider.complete(request);

        System.out.println();
        System.out.println("模型 Provider: " + provider.name());
        System.out.println("已注册工具: " + registry.toolNames());
        System.out.println("上下文 Token 估算: " + runtimeContext.estimatedTokens());
        System.out.println("慢思考指令: " + request.thinkingInstruction());
        System.out.println();
        System.out.println("Mock 模型输出:");
        System.out.println(response.content());
    }
}
