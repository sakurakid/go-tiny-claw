# go-tiny-claw

一个用 Java 编写的 Agent Harness 练习项目。

这个仓库不是生产级 Agent 框架，而是我用来学习和复现 Agent Harness 思路的简易 Demo。原始参考思路来自 Go 版本的 tiny-claw / claw 风格工程，但这里会用 Java 重新实现一遍，重点练习 Harness 这一层如何组织模型、上下文、工具、状态和安全边界。

## 项目定位

我想通过这个项目练习一个核心判断：

> 传统 Framework Layer 正在坍塌进 Harness。

早期 Agent Framework 往往依赖 Chain、DAG、Node、Edge 这类厚抽象，把业务流程写死在代码里。现在模型本身已经具备较强的规划和工具调用能力，所以更值得练习的是底层 Harness：它不替模型思考，而是给模型提供一个更稳的运行环境。

在这个 Demo 里，大模型像 CPU，Context 像内存，Tool 像外设，Harness 像一个小型操作系统：

- 管理上下文，而不是盲目塞工具描述。
- 暴露极简工具，而不是堆一堆复杂能力。
- 把 PLAN / TODO 外置到文件系统，而不是藏在黑盒状态机里。
- 对 bash、写文件等能力加拦截点，后续接 Human-in-the-loop 审批。

## 当前实现

目前已经落了一个能跑起来的 Java 最小骨架：

- CLI 入口：`lab.agentharness.claw.Main`
- 核心引擎：`AgentEngine`
- 模型适配：`ModelProvider` + `MockProvider`
- 上下文工程：`ContextManager`、`PromptComposer`、`TokenMonitor`
- 工具系统：`ToolRegistry`、`read_file`、`write_file`、`edit_file`、`bash`
- 安全拦截：`DangerousCommandMiddleware`
- 人工审批占位：`ApprovalGateway`、`ConsoleApprovalGateway`
- 文件记忆占位：`FileMemoryStore`
- 飞书集成占位：`FeishuAgentOpsClient`

## 项目结构

```text
go-tiny-claw/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            └── lab/
                └── agentharness/
                    ├── claw/       # Java 版入口，对应 Go 示例里的 cmd/claw/main.go
                    ├── engine/     # MainLoop / ReAct 核心循环
                    ├── provider/   # 大模型 Provider 抽象与 Mock 实现
                    ├── context/    # Prompt 动态组装、Token 估算、外部记忆读取
                    ├── tools/      # Tool Registry、内置工具、Middleware
                    ├── memory/     # PLAN / TODO 文件记忆占位
                    ├── entry/      # 人工审批入口占位
                    ├── feishu/     # 飞书 AgentOps 集成占位
                    └── thinking/   # 行动前慢思考模块
```

这里暂时没有 `src/test/java`。它是 Maven 标准测试目录，后续开始补单元测试时再加回来。

## Java 版入口

Go 版本示例大概是：

```go
func main() {
    fmt.Println("欢迎来到 go-tiny-claw 引擎启动序列")
    // 初始化 Provider
    // 初始化 Tool Registry
    // 初始化 Context Manager
    // 组装 Engine 并启动
}
```

当前 Java 版本对应在：

```text
src/main/java/lab/agentharness/claw/Main.java
```

它会完成：

1. 初始化 `MockProvider`，先不接真实大模型。
2. 注册 `read_file / write_file / edit_file / bash` 四个极简工具。
3. 初始化 `ContextManager`。
4. 组装 `AgentEngine`。
5. 跑一次本地 Mock 任务，验证启动链路。

## 运行方式

```bash
mvn clean package
mvn exec:java
```

也可以直接运行打出来的 jar：

```bash
java -jar target/go-tiny-claw-0.1.0-SNAPSHOT.jar
```

## 后续练习方向

1. 把 `MockProvider` 替换成 OpenAI / Claude 兼容 Provider。
2. 让 `AgentEngine` 支持真正的 ReAct tool-call 循环。
3. 给 `bash` 和文件写入动作增加更细的审批策略。
4. 引入 `PLAN.md` / `TODO.md`，把运行状态外置。
5. 增加上下文压缩器，模拟 Token 水位线回收。
6. 补飞书审批回调，练习 Human-in-the-loop。
7. 再补 `src/test/java`，用测试锁住工具、上下文和拦截器行为。

## 备注

仓库名仍然叫 `go-tiny-claw`，是为了保留参考来源和练习上下文；实际实现会以 Java 为主。
