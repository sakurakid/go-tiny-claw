# Subagent 多智能体委派

Subagent 的核心价值是把“探索上下文”和“主线决策上下文”物理隔离。主 Agent 负责目标、计划和最终写入；子 Agent 负责阅读大量文件、搜索线索、整理摘要。这样探索阶段产生的大量 Observation 不会污染主 Agent 的 Session 历史。

## 设计拆分

1. `tools.AgentRunner`
   - `tools` 包不能直接依赖 `engine` 包，否则会形成循环依赖。
   - 因此只定义一个最小接口：`runSub(taskPrompt, readOnlyRegistry, reporter)`。

2. `tools.SubagentTool`
   - 对主 Agent 暴露 `spawn_subagent` 工具。
   - 输入只有 `task_prompt`，要求主 Agent 给子智能体一条明确探索指令。
   - 工具内部调用 `AgentRunner`，等待子智能体完整跑完，再把摘要作为普通工具 Observation 返回给主 Agent。

3. `engine.AgentEngine.runSub`
   - 创建一次性的独立 `contextHistory`，不依赖外部 Session。
   - 使用专门的 Explorer System Prompt，要求子智能体必须用工具找答案，禁止凭空猜测。
   - 最多运行 10 轮，防止子智能体自己陷入长程探索螺旋。
   - 子智能体不调用工具时，把最后一条 Assistant 文本作为探索报告返回。

4. 只读工具沙箱
   - 子智能体默认只挂载 `read_file` 和 `bash`。
   - 不给 `write_file` / `edit_file`，避免探索任务误改文件。
   - 主 Agent 仍然使用全功能 Registry，负责最后的写入和提交。

## 运行方式

使用真实 Provider 跑多智能体协同测试：

```powershell
mvn -q "-Dmain.class=lab.agentharness.claw.SubagentSmokeTest" exec:java
```

测试入口会准备如下遗留目录：

```text
workspace/
├── fake1.go
├── fake2.go
└── legacy/
    └── v1/
        └── auth/
            └── config.txt
```

`config.txt` 中包含：

```text
核心密码是: super_secret_agent_password_42
```

预期行为：

1. 主 Agent 调用 `spawn_subagent`。
2. 子 Agent 使用只读工具搜索并读取 `config.txt`。
3. 子 Agent 返回精炼探索报告。
4. 主 Agent 使用 `write_file` 把密码写入 `workspace/answer.txt`。

## 架构收益

- 主上下文保持清爽：大量 read/search Observation 留在子循环里。
- 爆炸半径小：子智能体没有写文件工具。
- 能力可组合：主 Agent 可以把“探索”“验证”“总结”拆给多个子智能体。
- 后续可扩展：可以给不同类型子智能体配置不同 System Prompt 和工具沙箱。
