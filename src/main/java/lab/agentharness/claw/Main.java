package lab.agentharness.claw;

import java.nio.file.Path;
import java.util.logging.Logger;

import lab.agentharness.context.ContextManager;
import lab.agentharness.engine.AgentEngine;
import lab.agentharness.entry.approval.ConsoleApprovalGateway;
import lab.agentharness.provider.MockProvider;
import lab.agentharness.provider.ModelProvider;
import lab.agentharness.thinking.ThinkingModule;
import lab.agentharness.tools.ToolRegistry;
import lab.agentharness.tools.builtin.BashTool;
import lab.agentharness.tools.builtin.EditFileTool;
import lab.agentharness.tools.builtin.ReadFileTool;
import lab.agentharness.tools.builtin.WriteFileTool;
import lab.agentharness.tools.middleware.DangerousCommandMiddleware;

/**
 * CLI 启动入口，负责按顺序装配 Provider、工具注册表、上下文管理器和核心引擎。
 */
public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("欢迎来到 go-tiny-claw Java Harness 引擎启动序列");

        // 1. 初始化模型 Provider（大脑）
        ModelProvider provider = new MockProvider();

        // 2. 初始化 Tool Registry（手脚）
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(Path.of(".")));
        registry.register(new WriteFileTool(Path.of(".")));
        registry.register(new EditFileTool(Path.of(".")));
        registry.register(new BashTool(new DangerousCommandMiddleware(new ConsoleApprovalGateway())));

        // 3. 初始化上下文管理器（内存管理器）
        ContextManager contextManager = new ContextManager(Path.of("."));

        // 4. 组装并启动核心 Engine（操作系统心脏）
        AgentEngine engine = new AgentEngine(
                provider,
                registry,
                contextManager,
                new ThinkingModule());

        System.out.println("开始执行任务...");
        try {
            engine.run("帮我检查一下当前目录下的文件并输出一个 README.md 大纲");
        } catch (RuntimeException e) {
            LOG.severe("引擎运行崩溃: " + e.getMessage());
            throw e;
        }

        LOG.info("架构蓝图搭建完毕，等待各核心模块注入！");
    }
}
