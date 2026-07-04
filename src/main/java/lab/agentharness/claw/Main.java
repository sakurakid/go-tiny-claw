package lab.agentharness.claw;

import java.nio.file.Path;
import java.util.logging.Logger;

import lab.agentharness.engine.Loop;
import lab.agentharness.provider.LLMProvider;
import lab.agentharness.provider.MockProvider;
import lab.agentharness.tools.Registry;
import lab.agentharness.tools.ToolRegistry;

/**
 * CLI 启动入口，负责装配 MockProvider、ToolRegistry 和 Main Loop。
 */
public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("欢迎来到 go-tiny-claw Java Harness 引擎启动序列");

        // 1. 初始化模型 Provider（大脑）。Demo 阶段先用 Mock，不接真实 API。
        LLMProvider provider = new MockProvider();

        // 2. 初始化 Tool Registry（手脚）。工具参数以 RawJson 原样传递，具体工具自己解析。
        Registry registry = ToolRegistry.demoRegistry(Path.of("."));

        // 3. 组装并启动核心 Main Loop（操作系统心脏）
        Loop loop = new Loop(provider, registry);

        System.out.println("开始执行任务...");
        try {
            loop.run("帮我检查一下当前目录下的 README.md，并总结这个项目现在主要做什么");
        } catch (RuntimeException e) {
            LOG.severe("引擎运行崩溃: " + e.getMessage());
            throw e;
        }

        LOG.info("架构蓝图搭建完毕，等待各核心模块注入！");
    }
}
