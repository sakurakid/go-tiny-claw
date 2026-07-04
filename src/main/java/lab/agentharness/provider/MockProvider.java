package lab.agentharness.provider;

import java.util.stream.Collectors;

public final class MockProvider implements ModelProvider {
    @Override
    public String name() {
        return "mock-provider";
    }

    @Override
    public ModelResponse complete(AgentRequest request) {
        String tools = request.toolSpecs().stream()
                .map(spec -> "- " + spec.name() + ": " + spec.description())
                .collect(Collectors.joining(System.lineSeparator()));

        return new ModelResponse("""
                这是一次本地 Mock 推理，不会调用真实大模型 API。

                收到任务：
                %s

                Harness 已经完成三件事：
                1. 通过 ContextManager 组装系统提示词、工具说明和外部化记忆。
                2. 通过 ToolRegistry 暴露极简工具集。
                3. 通过 Middleware 为高危 bash 命令预留审批拦截。

                当前可用工具：
                %s

                下一步可以把 MockProvider 替换成 OpenAI/Claude 兼容 Provider，
                让 MainLoop 进入真正的 ReAct 工具调用循环。
                """.formatted(request.task(), tools));
    }
}
